package app.falak.player.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a portal's list doesn't keep for its films and series (8.33): their story and details, and
 * what playing or opening them needs. Kept beside the saved copy ([PortalExtras]).
 */
@Serializable
data class PortalExtra(
    val plot: String = "",
    val year: String = "",
    val director: String = "",
    val cast: String = "",
    val genre: String = "",
    val poster: String = "",
    val rating: String = "",
    val duration: String = "",
    /** The portal's play command (a series kept in the films list: the one for all its episodes). */
    val cmd: String = "",
    /** Episode numbers of a series kept in the films list as one season. */
    val episodes: List<Int> = emptyList(),
    /** "vod" or "series": the part of the portal its seasons are asked from. */
    val module: String = "",
)

/**
 * MAC portals (Stalker / Ministra middleware, made for MAG boxes; 8.33, the owner asked): the
 * pieces that need no network, kept apart so they can be tested.
 */
object Portal {
    /** What a MAG box says it is: some portals answer nothing else. */
    const val MAG_UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
    const val X_UA = "Model: MAG250; Link: WiFi"

    /** Several films lists are read at once, this many at a time (portals don't like more). */
    const val PARALLEL = 4

    /** At most this many films and series are read (a portal's list is read 14–20 at a time). */
    const val MAX_TITLES = 60_000

    const val REFUSED = "The portal didn't accept this MAC address. Check it's the one your provider activated for you."

    /** "00:1a:79:12:34:56", "001A79123456", "00-1A-79-…" → "00:1A:79:12:34:56"; null when it isn't 12 hex digits. */
    fun normalizeMac(input: String): String? {
        val hex = input.filter { it.isLetterOrDigit() }.uppercase(Locale.ROOT)
        if (hex.length != 12 || hex.any { it !in "0123456789ABCDEF" }) return null
        return hex.chunked(2).joinToString(":")
    }

    /**
     * The portal's address as the provider wrote it ("http://host:8080/c/",
     * "http://host/stalker_portal/c/", "host:80") → where its API may be, the likeliest first.
     */
    fun endpoints(input: String): List<String> {
        var s = input.trim()
        if (s.isEmpty()) return emptyList()
        if (!s.contains("://")) s = "http://$s"
        val url = s.toHttpUrlOrNull() ?: return emptyList()
        val defaultPort = if (url.scheme == "https") 443 else 80
        val root = "${url.scheme}://${url.host}" + (if (url.port != defaultPort) ":${url.port}" else "")
        val path = url.encodedPath
        val out = LinkedHashSet<String>()
        if (path.endsWith(".php")) {
            // Given as the API itself.
            out += root + path
        } else {
            // ".../stalker_portal/c/" → ".../stalker_portal/server/load.php"
            val dir = path.trimEnd('/').removeSuffix("/c").trimEnd('/')
            if (dir.isNotEmpty()) {
                out += "$root$dir/server/load.php"
                out += "$root$dir/portal.php"
            }
        }
        out += "$root/portal.php"
        out += "$root/stalker_portal/server/load.php"
        out += "$root/server/load.php"
        return out.toList()
    }

    /** The page a MAG box would be on for [endpoint] (sent as the Referer). */
    fun refererFor(endpoint: String): String {
        val dir = endpoint.substringBeforeLast('/')
        return dir.removeSuffix("/server") + "/c/"
    }

    /** A MAG box's serial number and ids, made from the MAC as MAG players do. */
    class Identity(val sn: String, val deviceId: String, val signature: String, val hw2: String)

    fun identity(mac: String): Identity {
        val sn = hex("MD5", mac).take(13)
        return Identity(sn, hex("SHA-256", sn), hex("SHA-256", sn + mac), hex("SHA-1", mac))
    }

    private fun hex(algorithm: String, s: String): String =
        MessageDigest.getInstance(algorithm).digest(s.toByteArray()).joinToString("") { "%02X".format(it) }

    /** A token of our own, for portals that don't hand one out. */
    fun newToken(): String = (1..32).map { "0123456789ABCDEF"[(Math.random() * 16).toInt()] }.joinToString("")

    /** The portal said no to this box (an answer that isn't JSON). */
    fun refused(text: String): Boolean =
        text.contains("Authorization failed", ignoreCase = true) || text.contains("Access denied", ignoreCase = true)

    /** The "js" part of an answer; null when the answer isn't one. */
    fun js(text: String): JsonElement? {
        val start = text.indexOf('{')
        if (start < 0) return null
        val root = try {
            Json.parseToJsonElement(text.substring(start)) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        return root["js"]
    }

    /** The link in a play command: "ffmpeg http://…" → "http://…" ("" when there is none). */
    fun cmdUrl(cmd: String): String =
        cmd.trim().split(Regex("\\s+")).firstOrNull { it.contains("://") }.orEmpty()

    /** A play command's link that plays without asking the portal (not "http://localhost/ch/…"). */
    fun direct(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host ?: return false
        return host != "localhost" && host != "127.0.0.1"
    }

    /** The file type a play command points at ("ts" when it doesn't say): HLS must be told apart. */
    fun extOf(cmd: String): String {
        val c = cmd.lowercase(Locale.ROOT)
        if (c.contains("m3u8")) return "m3u8"
        val tail = cmdUrl(cmd).substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
        return tail.takeIf { it.length in 2..4 && it.all { ch -> ch.isLetterOrDigit() } } ?: "ts"
    }

    /** A logo or poster: whole links as they are, paths on the portal's server made whole, others dropped. */
    fun picture(raw: String, root: String): String {
        val s = raw.trim()
        return when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.startsWith("/") && root.isNotEmpty() -> root + s
            else -> ""
        }
    }

    /** "http://host:8080/stalker_portal/server/load.php" → "http://host:8080". */
    fun rootOf(endpoint: String): String {
        val u = endpoint.toHttpUrlOrNull() ?: return ""
        val defaultPort = if (u.scheme == "https") 443 else 80
        return "${u.scheme}://${u.host}" + (if (u.port != defaultPort) ":${u.port}" else "")
    }

    /** Genres or categories: id → name ("*", the portal's "All", left out). */
    fun categories(js: JsonElement?): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val list = js as? JsonArray ?: ((js as? JsonObject)?.get("data") as? JsonArray) ?: return out
        for (el in list) {
            val o = el as? JsonObject ?: continue
            val id = o.str("id")
            if (id.isEmpty() || id == "*") continue
            out[id] = o.str("title").ifBlank { o.str("name") }.ifBlank { "Category $id" }
        }
        return out
    }

    /** One page of a list: its items, how many there are in all and how many a page holds. */
    class Page(val items: List<JsonObject>, val total: Int, val perPage: Int) {
        val pages: Int get() = if (perPage <= 0 || total <= 0) 1 else (total + perPage - 1) / perPage
    }

    fun page(js: JsonElement?): Page {
        val o = js as? JsonObject
        val data = (o?.get("data") as? JsonArray) ?: (js as? JsonArray)
        val items = data?.mapNotNull { it as? JsonObject }.orEmpty()
        return Page(items, o?.str("total_items")?.toIntOrNull() ?: items.size, o?.str("max_page_items")?.toIntOrNull() ?: items.size)
    }

    /** "2023-05-12 10:22:33" (the portal's "added") → epoch seconds; 0 when it can't be read. */
    fun addedOf(s: String): Long {
        val t = s.trim()
        if (t.isEmpty()) return 0
        t.toLongOrNull()?.let { return if (it > 100_000_000_000L) it / 1000 else it }
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(t)?.time?.div(1000) ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /** A series' episode numbers as the portal lists them ([1,2,3] or ["1","2"]). */
    fun episodes(o: JsonObject): List<Int> =
        (o["series"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }.orEmpty()

    fun isSeries(o: JsonObject): Boolean = o.str("is_series") == "1" || episodes(o).isNotEmpty()

    /** A season's number from its name ("Season 3", "Сезон 3", "S03"), else [fallback]. */
    fun seasonNumber(o: JsonObject, fallback: Int): Int {
        o.str("season_number").toIntOrNull()?.let { return it }
        val name = o.str("name").ifBlank { o.str("title") }
        return Regex("(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: fallback
    }

    /**
     * A channel's archive depth in days (8.35, catch-up): 0 when the portal doesn't mark it
     * (`tv_archive`, or `enable_tv_archive` / `archive` on some). `tv_archive_duration` is hours on
     * many portals (168 = a week) and days on others: more than 31 or a whole number of days in
     * hours (24, 48…) reads as hours. Marked without a depth: one day. At most 31.
     */
    fun archiveDays(o: JsonObject): Int {
        if (listOf("tv_archive", "enable_tv_archive", "archive").none { o.str(it).trim().lowercase() in setOf("1", "true") }) return 0
        val depth = o.str("tv_archive_duration").trim().toDoubleOrNull() ?: 0.0
        val days = when {
            depth.isNaN() || depth <= 0 -> 1
            depth > 31 || depth % 24 == 0.0 -> kotlin.math.ceil(depth / 24).toInt()
            else -> kotlin.math.ceil(depth).toInt()
        }
        return days.coerceIn(1, 31)
    }

    /** What a portal's archive link asks for: "channel id@programme start in seconds". */
    fun archiveCmd(channelId: String, startSec: Long): String = "$channelId@$startSec"

    /**
     * The programme in a day's archive list (`get_simple_data_table`) that starts at [startSec]
     * (within 90 s; the nearest): its id, and false when the portal says it wasn't recorded.
     */
    fun archiveEntry(items: List<JsonObject>, startSec: Long): Pair<String, Boolean>? {
        val best = items.mapNotNull { o ->
            val s = o.str("start_timestamp").toLongOrNull() ?: return@mapNotNull null
            val id = o.str("id").trim()
            if (id.isEmpty() || kotlin.math.abs(s - startSec) > 90) null else Triple(id, kotlin.math.abs(s - startSec), o.str("mark_archive").trim() != "0")
        }.minByOrNull { it.second } ?: return null
        return best.first to best.third
    }

    /** An end date as portals write it ("2026-12-31 23:59:59", "December 31, 2026, 11:59 pm"…) → epoch ms. */
    fun endDate(s: String): Long? {
        val t = s.trim()
        if (t.isEmpty() || t.equals("unlimited", true) || t == "0000-00-00 00:00:00") return null
        t.toLongOrNull()?.let { return if (it in 1_000_000_000L..99_999_999_999L) it * 1000 else null }
        for (f in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd", "MMMM d, yyyy, h:mm a", "MMMM d, yyyy h:mm a", "MMMM d, yyyy", "dd.MM.yyyy")) {
            try {
                val d = java.text.SimpleDateFormat(f, Locale.ENGLISH).apply { isLenient = false }.parse(t)
                if (d != null) return d.time
            } catch (e: Exception) {
                // the next way of writing it
            }
        }
        return null
    }

    internal fun JsonObject.str(key: String): String {
        val p = this[key] as? JsonPrimitive ?: return ""
        return if (p is JsonNull) "" else p.content
    }
}

/**
 * One MAC portal: a session (handshake, profile), its lists and play links. Calls block: run
 * them off the main thread. A session the portal forgot is started again once.
 */
class PortalApi(
    private val http: OkHttpClient,
    portalUrl: String,
    mac: String,
    userAgent: String = "",
) {
    val mac: String = Portal.normalizeMac(mac)
        ?: throw IOException("That MAC address isn't right: it has 12 letters and digits, like 00:1A:79:12:34:56")
    private val candidates = Portal.endpoints(portalUrl).ifEmpty { throw IOException("That portal address isn't right") }
    private val ua = userAgent.ifBlank { Portal.MAG_UA }
    private val ident = Portal.identity(this.mac)
    private val lock = Any()

    @Volatile private var endpoint: String? = null
    @Volatile private var token: String = ""

    /** The portal's server (logos and posters given as paths are on it). */
    val root: String get() = Portal.rootOf(endpoint ?: candidates.first())

    private fun request(ep: String, params: List<Pair<String, String>>): String {
        val url = ep.toHttpUrlOrNull()?.newBuilder() ?: throw IOException("That portal address isn't right")
        for ((k, v) in params) url.addQueryParameter(k, v)
        url.addQueryParameter("JsHttpRequest", "1-xml")
        val tz = TimeZone.getDefault().id
        val cookie = "mac=" + java.net.URLEncoder.encode(mac, "UTF-8") + "; stb_lang=en; timezone=" + java.net.URLEncoder.encode(tz, "UTF-8")
        val b = Request.Builder().url(url.build())
            .header("User-Agent", ua)
            .header("X-User-Agent", Portal.X_UA)
            .header("Referer", Portal.refererFor(ep))
            .header("Accept", "*/*")
            .header("Cookie", if (token.isEmpty()) cookie else "$cookie; token=$token")
        if (token.isNotEmpty()) b.header("Authorization", "Bearer $token")
        http.newCall(b.build()).execute().use { res ->
            if (!res.isSuccessful) throw HttpStatusException(res.code)
            return res.body?.string().orEmpty()
        }
    }

    /** Says hello to the portal (finding where its API is) and gets a session. */
    fun connect() = synchronized(lock) {
        var last: IOException? = null
        val order = listOfNotNull(endpoint) + candidates.filter { it != endpoint }
        for (ep in order) {
            token = ""
            val mine = Portal.newToken()
            val js = try {
                Portal.js(request(ep, listOf("type" to "stb", "action" to "handshake", "token" to mine, "prehash" to "0"))) as? JsonObject
            } catch (e: HttpStatusException) {
                last = e
                null
            } catch (e: IOException) {
                last = e
                // The server isn't there at all: no use trying its other paths.
                if (e is java.net.UnknownHostException || e is java.net.ConnectException) break
                null
            } ?: continue // not a portal there
            // Found it: what the profile says (a refused MAC) is the answer.
            with(Portal) { token = js.str("token").ifEmpty { mine } }
            endpoint = ep
            profile(ep)
            return@synchronized
        }
        throw when (val e = last) {
            null -> IOException("No MAC portal answers at that address. Check the link your provider gave.")
            is HttpStatusException -> IOException("No MAC portal answers at that address (HTTP ${e.code}). Check the link your provider gave.")
            else -> e
        }
    }

    private fun profile(ep: String) {
        val now = System.currentTimeMillis() / 1000
        val metrics = """{"mac":"$mac","sn":"${ident.sn}","model":"MAG250","type":"STB","uid":"","random":""}"""
        val text = request(ep, listOf(
            "type" to "stb", "action" to "get_profile", "hd" to "1",
            "ver" to "ImageDescription: 0.2.18-r23-250; ImageDate: Thu Sep 13 11:31:16 EEST 2018; PORTAL version: 5.6.2; API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x58c",
            "num_banks" to "2", "sn" to ident.sn, "stb_type" to "MAG250", "client_type" to "STB", "image_version" to "218",
            "video_out" to "hdmi", "device_id" to ident.deviceId, "device_id2" to ident.deviceId, "signature" to ident.signature,
            "auth_second_step" to "1", "hw_version" to "1.7-BD-00", "not_valid_token" to "0", "metrics" to metrics,
            "hw_version_2" to ident.hw2, "timestamp" to now.toString(), "api_signature" to "262", "prehash" to "",
        ))
        if (Portal.refused(text)) throw IOException(Portal.REFUSED)
        // Some portals hand out a new token with the profile.
        (Portal.js(text) as? JsonObject)?.let { js -> with(Portal) { js.str("token").takeIf { it.isNotEmpty() }?.let { token = it } } }
    }

    /** One call to the portal's API; a forgotten session is started again once. */
    fun call(vararg params: Pair<String, String>): JsonElement? {
        for (attempt in 0..1) {
            if (endpoint == null || token.isEmpty()) connect()
            val ep = endpoint ?: throw IOException("No MAC portal answers at that address")
            val text = try {
                request(ep, params.toList())
            } catch (e: HttpStatusException) {
                if (attempt == 0 && (e.code == 401 || e.code == 403)) {
                    token = ""
                    continue
                }
                throw e
            }
            if (Portal.refused(text)) {
                if (attempt == 0) {
                    token = ""
                    continue
                }
                throw IOException(Portal.REFUSED)
            }
            return Portal.js(text)
        }
        throw IOException(Portal.REFUSED)
    }

    /** Checks the portal takes this MAC (the Add window): a session and its live TV genres. */
    fun check() {
        connect()
        val js = call("type" to "itv", "action" to "get_genres")
        if (js == null) throw IOException("The portal answered, but not like a MAC portal. Check the link your provider gave.")
    }

    /** Every page of a list (the first one says how many there are); the rest are read [Portal.PARALLEL] at a time. */
    private fun allPages(params: List<Pair<String, String>>, limit: Int, onItems: (List<JsonObject>) -> Unit) {
        fun get(p: Int) = Portal.page(call(*(params + ("p" to p.toString())).toTypedArray()))
        val first = get(1)
        onItems(first.items)
        if (first.items.isEmpty()) return
        var read = first.items.size
        var p = 2
        while (p <= first.pages && read < limit) {
            val page = get(p)
            if (page.items.isEmpty()) break
            onItems(page.items)
            read += page.items.size
            p++
        }
    }

    /**
     * Live channels, films and series. [link] makes a channel's or film's link (module, episode,
     * command, file type). Films and series are optional: a portal that fails there still gives
     * its live TV. Returns the list and what it leaves out ([PortalExtra], by item id).
     */
    fun loadAll(
        reuse: Reuse = Reuse.NONE,
        progress: ((LoadProgress) -> Unit)? = null,
        link: (module: String, series: Int, cmd: String, ext: String) -> String,
    ): Pair<List<Channel>, Map<String, PortalExtra>> {
        val out = ArrayList<Channel>()
        val ids = HashSet<String>()
        val extras = HashMap<String, PortalExtra>()
        val counted = AtomicInteger(0)
        fun tell(n: Int) {
            val now = counted.addAndGet(n)
            if (progress != null && (now / 500) != ((now - n) / 500)) progress(LoadProgress(now))
        }

        // ---- live TV ----
        val genres = try {
            Portal.categories(call("type" to "itv", "action" to "get_genres"))
        } catch (e: IOException) {
            if (e.message == Portal.REFUSED) throw e
            LinkedHashMap()
        }
        var live = Portal.page(call("type" to "itv", "action" to "get_all_channels")).items
        if (live.isEmpty()) {
            // Portals without the whole list at once: page by page.
            val got = ArrayList<JsonObject>()
            allPages(listOf("type" to "itv", "action" to "get_ordered_list", "genre" to "*", "force_ch_link_check" to "", "fav" to "0", "sortby" to "number", "hd" to "0"), 50_000) { got += it }
            live = got
        }
        val root = root
        with(Portal) {
            for (o in live) {
                val sid = o.str("id")
                val id = "l$sid"
                if (sid.isEmpty() || !ids.add(id)) continue
                val cmd = o.str("cmd")
                out += reuse.of(Channel(
                    id = id,
                    name = o.str("name").ifBlank { "Channel $sid" },
                    group = genres[o.str("tv_genre_id")] ?: "Uncategorized",
                    url = link("itv", 0, cmd, extOf(cmd)),
                    logo = picture(o.str("logo"), root),
                    tvgId = o.str("xmltv_id"),
                    kind = Kind.LIVE,
                    catchupDays = archiveDays(o),
                    catchupType = if (archiveDays(o) > 0) "stb" else "",
                ))
            }
        }
        tell(out.size)

        // ---- films, and series kept with them; then the series part, when the portal has one ----
        val budget = AtomicInteger(Portal.MAX_TITLES)
        for (module in listOf("vod", "series")) {
            try {
                val cats = Portal.categories(call("type" to module, "action" to "get_categories"))
                if (cats.isEmpty()) continue
                val pool = Executors.newFixedThreadPool(Portal.PARALLEL)
                try {
                    val jobs = cats.map { (catId, catName) ->
                        pool.submit(Callable {
                            val got = ArrayList<JsonObject>()
                            if (budget.get() > 0) {
                                allPages(listOf("type" to module, "action" to "get_ordered_list", "category" to catId, "genre" to catId,
                                    "sortby" to "added", "fav" to "0", "hd" to "0", "not_ended" to "0", "abc" to "*", "years" to "*", "search" to ""),
                                    budget.get().coerceAtLeast(1)) { items ->
                                    got += items
                                    budget.addAndGet(-items.size)
                                    tell(items.size)
                                }
                            }
                            catName to got
                        })
                    }
                    for (job in jobs) {
                        val (catName, items) = try {
                            job.get()
                        } catch (e: ExecutionException) {
                            continue // one category that fails leaves the others
                        }
                        for (o in items) addTitle(o, catName, module, root, out, ids, extras, reuse, link)
                    }
                } finally {
                    pool.shutdownNow()
                }
            } catch (e: IOException) {
                if (e.message == Portal.REFUSED && out.isEmpty()) throw e
                // films and series are optional
            }
        }
        progress?.invoke(LoadProgress(out.size))
        return out to extras
    }

    private fun addTitle(
        o: JsonObject, group: String, module: String, root: String,
        out: MutableList<Channel>, ids: MutableSet<String>, extras: MutableMap<String, PortalExtra>,
        reuse: Reuse, link: (String, Int, String, String) -> String,
    ) = with(Portal) {
        val sid = o.str("id").ifEmpty { o.str("video_id") }
        if (sid.isEmpty()) return@with
        val cmd = o.str("cmd")
        val extra = PortalExtra(
            plot = o.str("description").take(600),
            year = o.str("year").take(4).takeIf { it.all(Char::isDigit) }.orEmpty(),
            director = o.str("director"),
            cast = o.str("actors"),
            genre = o.str("genres_str"),
            poster = picture(o.str("screenshot_uri"), root),
            rating = o.str("rating_imdb").ifBlank { o.str("rating_kinopoisk") }.takeIf { it != "0" && it != "N/A" }.orEmpty(),
            duration = o.str("time").toIntOrNull()?.takeIf { it > 0 }?.let { "$it min" }.orEmpty(),
            cmd = cmd,
            episodes = episodes(o),
            module = module,
        )
        val name = o.str("name").ifBlank { o.str("o_name") }
        if (module == "series" || isSeries(o)) {
            val key = (if (module == "series") "x" else "v") + sid
            val id = "s$key"
            if (!ids.add(id)) return@with
            extras[id] = extra
            out += reuse.of(Channel(
                id = id, name = name.ifBlank { "Series $sid" }, group = group, url = "", logo = extra.poster,
                kind = Kind.SERIES, seriesId = key, added = addedOf(o.str("added")), rating = extra.rating,
            ))
        } else {
            val id = "m$sid"
            if (!ids.add(id)) return@with
            extras[id] = extra
            out += reuse.of(Channel(
                id = id, name = name.ifBlank { "Film $sid" }, group = group, url = link("vod", 0, cmd, extOf(cmd)),
                logo = extra.poster, kind = Kind.MOVIE, added = addedOf(o.str("added")), rating = extra.rating,
            ))
        }
    }

    /** A series' seasons and episodes ([seriesId] as listed: "v…" in the films list, "x…" in the series part). */
    fun seriesInfo(seriesId: String, extra: PortalExtra?, link: (String, Int, String, String) -> String): SeriesInfo {
        val id = seriesId.drop(1)
        val modules = if (seriesId.startsWith("x")) listOf("series", "vod") else listOf("vod", "series")
        val seasons = java.util.TreeMap<Int, List<Channel>>()

        fun episode(season: Int, n: Int, name: String, cmd: String, logo: String = ""): Channel = Channel(
            id = "e${seriesId}_${season}_$n",
            name = name.ifBlank { "Episode $n" },
            group = "Season $season",
            url = link("vod", n, cmd, Portal.extOf(cmd)),
            logo = logo,
            kind = Kind.SERIES,
            info = "S$season · E$n",
        )

        with(Portal) {
            for (module in modules) {
                val found = try {
                    val got = ArrayList<JsonObject>()
                    allPages(listOf("type" to module, "action" to "get_ordered_list", "movie_id" to id, "season_id" to "0", "episode_id" to "0"), 2_000) { got += it }
                    got
                } catch (e: IOException) {
                    if (e.message == Portal.REFUSED) throw e
                    emptyList()
                }
                val seasonItems = found.filter { it.str("is_season").let { s -> s == "1" || s == "true" } || episodes(it).isNotEmpty() }
                    .ifEmpty { found.filter { it.str("id") != id } }
                for ((i, s) in seasonItems.withIndex()) {
                    val number = seasonNumber(s, i + 1)
                    val eps = episodes(s)
                    val list = if (eps.isNotEmpty()) {
                        eps.map { n -> episode(number, n, "Episode $n", s.str("cmd").ifEmpty { extra?.cmd.orEmpty() }) }
                    } else {
                        // Episodes asked for one season at a time.
                        val got = ArrayList<JsonObject>()
                        val movie = s.str("video_id").ifEmpty { s.str("movie_id") }.ifEmpty { id }
                        try {
                            allPages(listOf("type" to module, "action" to "get_ordered_list", "movie_id" to movie, "season_id" to s.str("id"), "episode_id" to "0"), 2_000) { got += it }
                        } catch (e: IOException) {
                            if (e.message == Portal.REFUSED) throw e
                        }
                        got.mapIndexedNotNull { j, e ->
                            val n = e.str("series_number").toIntOrNull() ?: (j + 1)
                            val cmd = e.str("cmd")
                            if (cmd.isEmpty()) null else episode(number, n, e.str("name").ifBlank { "Episode $n" }, cmd, picture(e.str("screenshot_uri"), root))
                        }
                    }
                    if (list.isNotEmpty()) seasons[number] = list.distinctBy { it.id }.sortedBy { it.info.substringAfter("E").toIntOrNull() ?: 0 }
                }
                if (seasons.isNotEmpty()) break
            }
        }
        // A series kept in the films list with its episode numbers only: one season.
        if (seasons.isEmpty() && extra != null && extra.episodes.isNotEmpty() && extra.cmd.isNotEmpty()) {
            seasons[1] = extra.episodes.map { n -> episode(1, n, "Episode $n", extra.cmd) }
        }
        if (seasons.isEmpty()) throw IOException("The portal sent no episodes for this series")
        return SeriesInfo(
            plot = extra?.plot.orEmpty(),
            cover = extra?.poster.orEmpty(),
            seasons = seasons.map { (n, eps) -> Season(n, eps) },
            genre = extra?.genre.orEmpty(),
            cast = extra?.cast.orEmpty(),
            year = extra?.year.orEmpty(),
            rating = extra?.rating.orEmpty(),
        )
    }

    /** The link to play now (they last a short while): asked of the portal for each play. */
    fun playLink(module: String, cmd: String, series: Int): String {
        if (module == "tv_archive") return archiveLink(cmd.substringBefore('@'), cmd.substringAfter('@').toLongOrNull() ?: 0L)
        val js = call(
            "type" to module, "action" to "create_link", "cmd" to cmd, "series" to (if (series > 0) series.toString() else ""),
            "forced_storage" to "undefined", "disable_ad" to "0", "download" to "0", "force_ch_link_check" to "0",
        ) as? JsonObject
        with(Portal) {
            val url = cmdUrl(js?.str("url").orEmpty()).ifEmpty { cmdUrl(js?.str("cmd").orEmpty()) }
            if (url.isNotEmpty()) return url
            cmdUrl(cmd).takeIf { direct(it) }?.let { return it }
            throw IOException(
                when (js?.str("error").orEmpty()) {
                    "limit" -> "Too many devices are watching on this account right now"
                    "nothing_to_play" -> "The portal has nothing to play for this"
                    "link_fault" -> "The portal couldn't make a link for this"
                    else -> "The portal gave no link to play"
                },
            )
        }
    }

    /**
     * A recorded programme (8.35, catch-up): the day's archive list of channel [chId] gives the
     * programme's id (the portal's date may be a day off ours: the days around are tried too), then
     * `create_link` of type tv_archive with "auto /media/<id>.mpg", as set-top boxes ask.
     */
    fun archiveLink(chId: String, startSec: Long): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val days = listOf(0L, -1L, 1L).map { fmt.format(java.util.Date(startSec * 1000 + it * 86_400_000L)) }.distinct()
        var found: Pair<String, Boolean>? = null
        loop@ for (day in days) {
            for (p in 1..8) {
                val page = Portal.page(call("type" to "epg", "action" to "get_simple_data_table", "ch_id" to chId, "date" to day, "p" to p.toString()))
                Portal.archiveEntry(page.items, startSec)?.let { found = it; break@loop }
                if (page.items.isEmpty() || p >= page.pages) break
            }
        }
        val (id, recorded) = found ?: throw IOException("The portal has no recording of this programme")
        if (!recorded) throw IOException("The portal didn't record this programme")
        val js = call(
            "type" to "tv_archive", "action" to "create_link", "cmd" to "auto /media/$id.mpg", "series" to "",
            "forced_storage" to "", "disable_ad" to "0", "download" to "0",
        ) as? JsonObject
        with(Portal) {
            val url = cmdUrl(js?.str("url").orEmpty()).ifEmpty { cmdUrl(js?.str("cmd").orEmpty()) }
            if (url.isNotEmpty()) return url
        }
        throw IOException("The portal gave no link for this recording")
    }

    /** "Still here": some portals stop a stream after a few minutes without it. */
    fun stillHere() {
        call("type" to "watchdog", "action" to "get_events", "event_active_id" to "0", "init" to "0", "cur_play_type" to "1")
    }

    /** The account (its end date, when the portal tells it). */
    fun account(now: Long = System.currentTimeMillis()): Account {
        val js = call("type" to "account_info", "action" to "get_main_info") as? JsonObject
            ?: return Account(status = "Active", checkedAt = now)
        with(Portal) {
            val end = listOf("end_date", "expire_billing_date", "subscribed_till", "phone").firstNotNullOfOrNull { endDate(js.str(it)) }
            return Account(status = "Active", expires = end, checkedAt = now)
        }
    }

    /**
     * The portal's guide for the next [hours] hours (portals keep few days): its channel id →
     * programmes. Empty when the portal doesn't give one this way.
     */
    fun guide(hours: Int): Map<String, List<Program>> {
        val js = call("type" to "itv", "action" to "get_epg_info", "period" to hours.toString()) as? JsonObject ?: return emptyMap()
        val data = js["data"] as? JsonObject ?: return emptyMap()
        val out = HashMap<String, List<Program>>()
        with(Portal) {
            for ((chId, list) in data) {
                val progs = (list as? JsonArray)?.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val start = o.str("start_timestamp").toLongOrNull() ?: return@mapNotNull null
                    val stop = o.str("stop_timestamp").toLongOrNull() ?: return@mapNotNull null
                    if (stop <= start) null else Program(start * 1000, stop * 1000, o.str("name"), o.str("descr"))
                }?.sortedBy { it.start }.orEmpty()
                if (progs.isNotEmpty()) out["l$chId"] = progs
            }
        }
        return out
    }
}
