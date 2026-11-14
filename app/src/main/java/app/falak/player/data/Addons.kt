package app.falak.player.data

import app.falak.player.torrent.TorrentSource
import app.falak.player.torrent.Torrents
import app.falak.player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

/*
 * Stremio-style add-ons (8.12): the user adds an add-on's link (its manifest.json); the app asks it
 * for lists of films and series ("catalogs"), their details ("meta") and links to play ("streams").
 * The format is open: https://github.com/Stremio/stremio-addon-sdk/tree/master/docs/api
 *
 * Owner's choices: GitHub version only (not on Google Play, see [Addons.enabled]); only links the
 * player can open (web links). Torrent links are counted and left out (no torrent engine). No
 * add-on comes with the app: users add their own links.
 */

data class AddonCatalog(
    val type: String,
    val id: String,
    val name: String,
    /** It can be searched ("search" extra). */
    val searchable: Boolean,
    /** Only with a search (or another required extra that isn't a pick from [genres]): not shown as a row. */
    val needsExtra: Boolean,
    /**
     * What the list can be narrowed to (its "genre" extra's options): genres, or whatever the
     * add-on calls so: Cinemeta's "New" lists are picked by year here.
     */
    val genres: List<String> = emptyList(),
    /** One of [genres] has to be picked (Cinemeta's "New" needs its year): the first is shown as the row. */
    val genreRequired: Boolean = false,
    /** It has more pages (the "skip" extra). */
    val paged: Boolean = false,
)

data class AddonResource(val name: String, val types: List<String>?, val idPrefixes: List<String>?)

data class Addon(
    /** The add-on's base link (no trailing "/manifest.json"). */
    val url: String,
    val id: String,
    val name: String,
    val description: String,
    val logo: String,
    val types: List<String>,
    val catalogs: List<AddonCatalog>,
    val resources: List<AddonResource>,
    val idPrefixes: List<String>?,
    /** The add-on has to be set up on its own web page first (its link then carries the settings). */
    val configurationRequired: Boolean,
) {
    /** It answers [resource] for this type and id (by its declared types and id prefixes). */
    fun supports(resource: String, type: String, id: String): Boolean {
        val r = resources.firstOrNull { it.name == resource } ?: return false
        val types = r.types ?: this.types
        if (types.isNotEmpty() && type !in types) return false
        val prefixes = r.idPrefixes ?: idPrefixes
        return prefixes.isNullOrEmpty() || prefixes.any { id.startsWith(it) }
    }
}

/** A film or series in an add-on's list (kept as it is in My list, see Settings.addonSaved). */
@kotlinx.serialization.Serializable
data class AddonItem(
    val addonUrl: String,
    val id: String,
    val type: String,
    val name: String,
    val poster: String = "",
    val year: String = "",
    val rating: String = "",
)

/**
 * A film or series of an add-on that was started (8.23): the portal's "Continue watching". One per
 * title, the film or episode watched last. Its link isn't kept (add-ons' links change): carrying
 * on means picking a link again, and the player then starts where it was left.
 */
@kotlinx.serialization.Serializable
data class AddonWatch(
    val type: String,
    /** The title (a series, not its episode). */
    val id: String,
    val name: String,
    val poster: String = "",
    /** What was played: the film's id, or the episode's ("tt…:1:2"). */
    val videoId: String,
    /** The episode ("S1 E2 · Its name"); empty for a film. */
    val label: String = "",
    val pos: Long = 0,
    val dur: Long = 0,
    val at: Long = 0,
    /** The episode was watched to its end (the series stays in the row, to pick the next one). */
    val done: Boolean = false,
) {
    /** The season of the episode ("tt…:1:2" → 1), to open the series on it. */
    val season: Int? get() = if (type == "series") videoId.split(':').let { it.getOrNull(it.size - 2)?.toIntOrNull() } else null
}

data class AddonVideo(
    /** What to ask streams for (usually "tt…:season:episode"). */
    val id: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val released: String,
    val overview: String,
)

data class AddonMeta(
    val id: String,
    val type: String,
    val name: String,
    val poster: String,
    val background: String,
    val description: String,
    val year: String,
    val runtime: String,
    val genres: List<String>,
    val rating: String,
    val videos: List<AddonVideo>,
)

/** A link the player can open, from one add-on. */
data class AddonStream(
    val addonName: String,
    val url: String,
    /** The add-on's short name for it (often the quality: "1080p"). */
    val name: String,
    /** More about it: file name, size, source (several lines). */
    val description: String,
    val userAgent: String,
    val referrer: String,
    /** A torrent link (8.16): [url] is empty until it has been got ready to play (see Torrents). */
    val torrent: TorrentSource? = null,
)

data class AddonStreams(
    val playable: List<AddonStream>,
    /** How many torrent links there are (shown only where torrents play, see [torrentLinks]). */
    val torrents: Int,
    /** Other links left out (YouTube, "open in a browser"). */
    val others: Int,
    /** Add-ons that didn't answer. */
    val failed: List<String>,
    /** The torrent links themselves: played by the GitHub version (8.16), counted and left out elsewhere. */
    val torrentLinks: List<AddonStream> = emptyList(),
)

object Addons {
    /** GitHub version only: never on Google Play (its rules on apps that can reach pirated films). */
    val enabled: Boolean get() = BuildConfig.ADDONS || enabledForTest

    /** Screenshot tests (which run the Play version). */
    @Volatile
    internal var enabledForTest = false

    /**
     * Cinemeta, Stremio's own lists of films and series (posters and descriptions, no links): offered
     * in one tap when the add-ons added only find links. Never added by itself.
     */
    const val STANDARD_LISTS = "https://v3-cinemeta.strem.io"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * A link as people paste it → the add-on's base link, or null when it isn't one.
     * "stremio://host/path/manifest.json" → "https://host/path"; a trailing "/manifest.json" or "/"
     * goes; plain "host/path" gets https.
     */
    fun normalize(link: String): String? {
        var s = link.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        s = when {
            s.startsWith("stremio://", ignoreCase = true) -> "https://" + s.substring("stremio://".length)
            s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true) -> s
            s.contains("://") -> return null
            else -> "https://$s"
        }
        s = s.substringBefore('#')
        if (s.endsWith("/manifest.json", ignoreCase = true)) s = s.dropLast("/manifest.json".length)
        s = s.trimEnd('/')
        val host = s.substringAfter("://").substringBefore('/')
        if (host.isEmpty() || !host.contains('.') && !host.startsWith("localhost") && !host.contains(':')) return null
        return s
    }

    fun manifestUrl(base: String) = "$base/manifest.json"

    // ---------- Continue watching (Settings.addonWatch, newest first) ----------

    private const val MAX_WATCH = 40

    /** Something starts playing: its title goes first (where it was left is kept if it is the same film or episode). */
    fun noteStart(list: List<AddonWatch>, e: AddonWatch, now: Long): List<AddonWatch> {
        val before = list.firstOrNull { it.type == e.type && it.id == e.id }
        val same = before?.takeIf { it.videoId == e.videoId && !it.done }
        val entry = e.copy(pos = same?.pos ?: 0, dur = same?.dur ?: 0, at = now, done = false)
        return (listOf(entry) + list.filterNot { it.type == e.type && it.id == e.id }).take(MAX_WATCH)
    }

    /**
     * The player says how far it is. Watched to the end: a film leaves the row, a series stays
     * (its next episode is picked on its page). Closed in the first half minute without having
     * been watched before: it was only looked at, and leaves too.
     */
    fun noteProgress(list: List<AddonWatch>, videoId: String, pos: Long, dur: Long, finished: Boolean, now: Long): List<AddonWatch> {
        val e = list.firstOrNull { it.videoId == videoId } ?: return list
        val next = when {
            finished -> if (e.type == "series") e.copy(pos = 0, dur = dur, at = now, done = true) else null
            pos < 30_000 -> if (e.pos == 0L && !e.done) null else return list
            else -> e.copy(pos = pos, dur = dur, at = now, done = false)
        }
        val rest = list.filterNot { it.videoId == videoId }
        return if (next == null) rest else listOf(next) + rest
    }

    /** Which add-ons find links (Torrentio and the like), and which only bring lists and details (Cinemeta). */
    fun hasLinks(a: Addon): Boolean = a.resources.any { it.name == "stream" }

    /**
     * "catalog/movie/top.json", or with a pick, a search or a later page:
     * "catalog/movie/top/genre=Action&skip=100.json", "catalog/movie/top/search=the%20matrix.json".
     */
    fun catalogPath(c: AddonCatalog, search: String? = null, skip: Int = 0, genre: String? = null): String {
        val extras = buildList {
            if (!genre.isNullOrBlank()) add("genre=" + enc(genre))
            if (!search.isNullOrBlank()) add("search=" + enc(search.trim()))
            if (skip > 0) add("skip=$skip")
        }
        val tail = if (extras.isEmpty()) "" else "/" + extras.joinToString("&")
        return "catalog/${enc(c.type)}/${enc(c.id)}$tail.json"
    }

    fun metaPath(type: String, id: String) = "meta/${enc(type)}/${enc(id)}.json"

    fun streamPath(type: String, id: String) = "stream/${enc(type)}/${enc(id)}.json"

    // (":" stays: legal in a path, and how ids such as "tt…:1:2" are asked for.)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%3A", ":")

    // ---------- reading what add-ons answer (leniently: add-ons differ) ----------

    fun parseManifest(base: String, text: String): Addon {
        val o = json.parseToJsonElement(text) as? JsonObject ?: throw IOException("Not an add-on (no manifest)")
        val id = o.str("id")
        val name = o.str("name")
        if (id.isEmpty() && name.isEmpty()) throw IOException("Not an add-on (no name)")
        val resources = (o["resources"] as? JsonArray).orEmpty().mapNotNull { r ->
            when (r) {
                is JsonPrimitive -> r.contentOrNull?.let { AddonResource(it, null, null) }
                is JsonObject -> r.str("name").takeIf { it.isNotEmpty() }?.let {
                    AddonResource(it, r.strings("types"), r.strings("idPrefixes"))
                }
                else -> null
            }
        }
        val catalogs = (o["catalogs"] as? JsonArray).orEmpty().mapNotNull { c ->
            (c as? JsonObject)?.let { cat ->
                val extras = (cat["extra"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                val required = extras.filter { it.bool("isRequired") }.map { it.str("name") } +
                    cat.strings("extraRequired").orEmpty()
                val supported = extras.map { it.str("name") } + cat.strings("extraSupported").orEmpty()
                // The picks: the "genre" extra's options, or the older "genres" list beside it.
                val genres = (extras.firstOrNull { it.str("name") == "genre" }?.strings("options")?.takeIf { it.isNotEmpty() }
                    ?: cat.strings("genres").orEmpty()).filter { it.isNotBlank() }.distinct()
                val genreRequired = "genre" in required
                AddonCatalog(
                    type = cat.str("type"),
                    id = cat.str("id"),
                    name = cat.str("name").ifEmpty { cat.str("id") },
                    searchable = "search" in supported || "search" in required,
                    // A list that needs a pick is shown with its first one; one that needs a search
                    // (or anything else) only answers when asked.
                    needsExtra = required.any { it != "genre" } || (genreRequired && genres.isEmpty()),
                    genres = genres,
                    genreRequired = genreRequired,
                    paged = "skip" in supported,
                ).takeIf { it.type.isNotEmpty() && it.id.isNotEmpty() }
            }
        }
        val hints = o["behaviorHints"] as? JsonObject
        return Addon(
            url = base,
            id = id.ifEmpty { base },
            name = name.ifEmpty { id },
            description = o.str("description"),
            logo = o.str("logo"),
            types = o.strings("types").orEmpty(),
            catalogs = catalogs,
            resources = resources,
            idPrefixes = o.strings("idPrefixes"),
            configurationRequired = hints?.bool("configurationRequired") == true,
        )
    }

    fun parseCatalog(addonUrl: String, text: String): List<AddonItem> {
        val o = json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
        return (o["metas"] as? JsonArray).orEmpty().mapNotNull { m ->
            (m as? JsonObject)?.let {
                AddonItem(
                    addonUrl = addonUrl,
                    id = it.str("id"),
                    type = it.str("type"),
                    name = it.str("name"),
                    poster = it.str("poster"),
                    year = it.str("releaseInfo").ifEmpty { it.str("year") },
                    rating = it.str("imdbRating"),
                ).takeIf { item -> item.id.isNotEmpty() && item.name.isNotEmpty() }
            }
        }.distinctBy { it.id }
    }

    fun parseMeta(text: String): AddonMeta? {
        val o = json.parseToJsonElement(text) as? JsonObject ?: return null
        val m = o["meta"] as? JsonObject ?: return null
        val videos = (m["videos"] as? JsonArray).orEmpty().mapNotNull { v ->
            (v as? JsonObject)?.let {
                AddonVideo(
                    id = it.str("id"),
                    title = it.str("title").ifEmpty { it.str("name") },
                    season = it.int("season"),
                    episode = it.int("episode").takeIf { e -> e > 0 } ?: it.int("number"),
                    released = it.str("released").take(10),
                    overview = it.str("overview").ifEmpty { it.str("description") },
                ).takeIf { video -> video.id.isNotEmpty() }
            }
        }.sortedWith(compareBy({ if (it.season == 0) Int.MAX_VALUE else it.season }, { it.episode }))
        return AddonMeta(
            id = m.str("id"),
            type = m.str("type"),
            name = m.str("name"),
            poster = m.str("poster"),
            background = m.str("background"),
            description = m.str("description"),
            year = m.str("releaseInfo").ifEmpty { m.str("year") },
            runtime = m.str("runtime"),
            genres = m.strings("genres") ?: m.strings("genre").orEmpty(),
            rating = m.str("imdbRating"),
            videos = videos,
        )
    }

    fun parseStreams(addonName: String, text: String): AddonStreams {
        val o = json.parseToJsonElement(text) as? JsonObject ?: return AddonStreams(emptyList(), 0, 0, emptyList())
        var others = 0
        val playable = ArrayList<AddonStream>()
        val torrentLinks = ArrayList<AddonStream>()
        for (e in (o["streams"] as? JsonArray).orEmpty()) {
            val s = e as? JsonObject ?: continue
            val url = s.str("url")
            when {
                s.str("infoHash").isNotEmpty() || url.startsWith("magnet:", ignoreCase = true) -> {
                    val fileIdx = (s["fileIdx"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                    val fileName = (s["behaviorHints"] as? JsonObject)?.str("filename").orEmpty()
                    val source = TorrentSource.cleanHash(s.str("infoHash"))?.let { hash ->
                        TorrentSource(
                            hash,
                            fileIdx,
                            TorrentSource.trackersFrom(s.strings("sources").orEmpty()),
                            fileName,
                        )
                    } ?: TorrentSource.fromMagnet(url, fileIdx, fileName)
                    if (source == null) {
                        others++
                    } else {
                        torrentLinks += AddonStream(
                            addonName = addonName,
                            url = "",
                            name = s.str("name"),
                            description = s.str("description").ifEmpty { s.str("title") },
                            userAgent = "",
                            referrer = "",
                            torrent = source,
                        )
                    }
                }
                url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> {
                    val headers = ((s["behaviorHints"] as? JsonObject)?.get("proxyHeaders") as? JsonObject)
                        ?.get("request") as? JsonObject
                    playable += AddonStream(
                        addonName = addonName,
                        url = url,
                        name = s.str("name"),
                        description = s.str("description").ifEmpty { s.str("title") },
                        userAgent = headers?.headerValue("User-Agent").orEmpty(),
                        referrer = (headers?.headerValue("Referer") ?: headers?.headerValue("Referrer")).orEmpty(),
                    )
                }
                else -> others++
            }
        }
        return AddonStreams(playable, torrentLinks.size, others, emptyList(), torrentLinks)
    }

    private fun JsonObject.str(key: String): String = when (val v = this[key]) {
        is JsonPrimitive -> v.contentOrNull.orEmpty()
        else -> ""
    }

    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 0

    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.contentOrNull == "true"

    private fun JsonObject.strings(key: String): List<String>? = when (val v = this[key]) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> v.contentOrNull?.let { listOf(it) }
        else -> null
    }

    private fun JsonObject.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.let { (it as? JsonPrimitive)?.contentOrNull }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}

/** Asks add-ons over the network (each answer is small JSON). */
class AddonClient(private val http: OkHttpClient) {
    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("Empty answer")
            // An add-on's answer is small; a huge one isn't an add-on.
            if (body.contentLength() > MAX_BYTES) throw IOException("Answer too big")
            body.string().also { if (it.length > MAX_BYTES) throw IOException("Answer too big") }
        }
    }

    suspend fun manifest(base: String): Addon = Addons.parseManifest(base, get(Addons.manifestUrl(base)))

    suspend fun catalog(a: Addon, c: AddonCatalog, search: String? = null, skip: Int = 0, genre: String? = null): List<AddonItem> =
        Addons.parseCatalog(a.url, get("${a.url}/${Addons.catalogPath(c, search, skip, genre)}"))

    /** The details, from the add-on the item came from if it can, else the first one that can. */
    suspend fun meta(addons: List<Addon>, item: AddonItem): AddonMeta? {
        val order = addons.sortedByDescending { it.url == item.addonUrl }.filter { it.supports("meta", item.type, item.id) }
        for (a in order) {
            val meta = runCatching { Addons.parseMeta(get("${a.url}/${Addons.metaPath(item.type, item.id)}")) }.getOrNull()
            if (meta != null && meta.name.isNotEmpty()) return meta
        }
        return null
    }

    /** Links from every add-on that has them for this film or episode, asked at the same time. */
    suspend fun streams(addons: List<Addon>, type: String, id: String): AddonStreams = coroutineScope {
        val answers = addons.filter { it.supports("stream", type, id) }.map { a ->
            async {
                try {
                    Addons.parseStreams(a.name, get("${a.url}/${Addons.streamPath(type, id)}"))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AddonStreams(emptyList(), 0, 0, listOf(a.name))
                }
            }
        }.awaitAll()
        AddonStreams(
            playable = answers.flatMap { it.playable }.distinctBy { it.url },
            torrents = answers.sumOf { it.torrents },
            others = answers.sumOf { it.others },
            failed = answers.flatMap { it.failed },
            torrentLinks = answers.flatMap { it.torrentLinks }
                .distinctBy { Torrents.keyOf(it.torrent!!) },
        )
    }

    companion object {
        private const val MAX_BYTES = 8_000_000L
    }
}
