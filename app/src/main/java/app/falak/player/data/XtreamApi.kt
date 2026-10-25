package app.falak.player.data

import android.util.JsonReader
import android.util.JsonToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Xtream Codes "player_api.php" client. Large lists are streamed to keep memory low on TV boxes. */
class XtreamApi(
    private val http: OkHttpClient,
    server: String,
    private val user: String,
    private val pass: String,
    private val userAgent: String,
) {
    private val base: HttpUrl = normalize(server).toHttpUrl()

    val epgUrl: String
        get() = base.newBuilder().addPathSegment("xmltv.php")
            .addQueryParameter("username", user)
            .addQueryParameter("password", pass)
            .build().toString()

    fun login() {
        val text = call(null) { it }
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw IOException("Not an Xtream server, or the server URL is wrong")
        val info = root["user_info"] as? JsonObject
            ?: throw IOException("Not an Xtream server, or the server URL is wrong")
        if (info.str("auth") != "1") throw IOException("Login failed: check username and password")
        val status = info.str("status")
        if (status.isNotEmpty() && !status.equals("Active", ignoreCase = true)) {
            throw IOException("Account status: $status")
        }
    }

    /** The account as the provider reports it ("user_info"): status, end date, connections. */
    fun account(now: Long = System.currentTimeMillis()): Account {
        val root = Json.parseToJsonElement(call(null) { it }) as? JsonObject
            ?: throw IOException("Not an Xtream server, or the server URL is wrong")
        val info = root["user_info"] as? JsonObject
            ?: throw IOException("Not an Xtream server, or the server URL is wrong")
        return Accounts.parse({ info.str(it) }, now)
    }

    /** IANA time zone of the server (replay times are given in it), or "" if unknown. */
    fun serverTimezone(): String = try {
        val root = Json.parseToJsonElement(call(null) { it }) as? JsonObject
        (root?.get("server_info") as? JsonObject)?.str("timezone").orEmpty()
    } catch (e: Exception) {
        ""
    }

    /**
     * Every live channel, film and series. [reuse] keeps identical entries from a previous load;
     * [progress] hears how far it got. A list that fails part-way is read again (see [Downloads]).
     */
    fun loadAll(reuse: Reuse = Reuse.NONE, progress: ((LoadProgress) -> Unit)? = null): List<Channel> {
        val out = ArrayList<Channel>()
        val ids = HashSet<String>()

        fun add(ch: Channel) {
            out += ch
            if (progress != null && out.size % 1000 == 0) progress(LoadProgress(out.size))
        }

        /** One list (live channels, films or series), read again from the start if it fails part-way. */
        fun section(action: String, onItem: (Map<String, String>) -> Unit) {
            val mark = out.size
            Downloads.withRetries(onRetry = { attempt ->
                // Drop what the failed try added: the next one brings it all again.
                while (out.size > mark) ids.remove(out.removeAt(out.size - 1).id)
                progress?.invoke(LoadProgress(out.size, attempt))
            }) { forEachObject(action, onItem) }
        }

        val liveCats = categories("get_live_categories")
        val livePrefix = prefix("live")
        section("get_live_streams") { o ->
            val sid = o["stream_id"].orEmpty()
            val id = "l$sid"
            if (sid.isEmpty() || !ids.add(id)) return@section
            add(reuse.of(Channel(
                id = id,
                name = o["name"].orEmpty().ifBlank { "Channel $sid" },
                group = o["category_id"]?.let { liveCats[it] } ?: "Uncategorized",
                url = "$livePrefix/$sid.ts",
                logo = o["stream_icon"].orEmpty(),
                tvgId = o["epg_channel_id"].orEmpty(),
                kind = Kind.LIVE,
                added = o["added"]?.toLongOrNull() ?: 0L,
                catchupDays = if (o["tv_archive"] == "1") (o["tv_archive_duration"]?.toIntOrNull() ?: 1).coerceAtLeast(1) else 0,
                catchupType = if (o["tv_archive"] == "1") "xc" else "",
            )))
        }

        // Movies are optional: some servers time out here; keep live TV working regardless.
        try {
            val vodCats = categories("get_vod_categories")
            val vodPrefix = prefix("movie")
            section("get_vod_streams") { o ->
                val sid = o["stream_id"].orEmpty()
                val id = "m$sid"
                if (sid.isEmpty() || !ids.add(id)) return@section
                val ext = o["container_extension"].orEmpty().ifBlank { "mp4" }
                add(reuse.of(Channel(
                    id = id,
                    name = o["name"].orEmpty().ifBlank { "Movie $sid" },
                    group = o["category_id"]?.let { vodCats[it] } ?: "Uncategorized",
                    url = "$vodPrefix/$sid.$ext",
                    logo = o["stream_icon"].orEmpty(),
                    kind = Kind.MOVIE,
                    added = o["added"]?.toLongOrNull() ?: 0L,
                    rating = o["rating"].orEmpty().takeIf { it != "0" }.orEmpty(),
                )))
            }
        } catch (e: Exception) {
            // ignore: films and series are optional (some servers time out or send broken lists)
        }

        // Series list; episodes are fetched when a series is opened.
        try {
            val seriesCats = categories("get_series_categories")
            section("get_series") { o ->
                val sid = o["series_id"].orEmpty()
                val id = "s$sid"
                if (sid.isEmpty() || !ids.add(id)) return@section
                add(reuse.of(Channel(
                    id = id,
                    name = o["name"].orEmpty().ifBlank { "Series $sid" },
                    group = o["category_id"]?.let { seriesCats[it] } ?: "Uncategorized",
                    url = "",
                    logo = o["cover"].orEmpty(),
                    kind = Kind.SERIES,
                    seriesId = sid,
                    added = o["last_modified"]?.toLongOrNull() ?: 0L,
                    rating = o["rating"].orEmpty().takeIf { it != "0" }.orEmpty(),
                )))
            }
        } catch (e: Exception) {
            // ignore: films and series are optional (some servers time out or send broken lists)
        }
        return out
    }

    fun seriesInfo(seriesId: String): SeriesInfo {
        val text = call("get_series_info", mapOf("series_id" to seriesId)) { it }
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw IOException("The server returned no episodes")
        val info = root["info"] as? JsonObject
        val seriesPrefix = prefix("series")
        val seasons = java.util.TreeMap<Int, MutableList<Pair<Int, Channel>>>()
        val seen = HashSet<String>()

        fun add(seasonKey: String?, o: JsonObject) {
            val id = o.str("id")
            if (id.isEmpty() || !seen.add(id)) return // some panels list an episode twice
            val season = o.str("season").toIntOrNull() ?: seasonKey?.toIntOrNull() ?: 1
            val num = o.str("episode_num").toIntOrNull() ?: 0
            val ext = o.str("container_extension").ifBlank { "mp4" }
            val epInfo = o["info"] as? JsonObject
            val duration = epInfo?.str("duration").orEmpty()
            val label = buildString {
                append("S").append(season).append(" · E").append(num)
                if (duration.isNotBlank()) append(" · ").append(duration)
            }
            val ch = Channel(
                id = "e$id",
                name = o.str("title").ifBlank { "Episode $num" },
                group = "Season $season",
                url = "$seriesPrefix/$id.$ext",
                logo = epInfo?.str("movie_image").orEmpty(),
                kind = Kind.SERIES,
                info = label,
            )
            seasons.getOrPut(season) { ArrayList() }.add(num to ch)
        }

        when (val eps = root["episodes"]) {
            is JsonObject -> eps.forEach { (key, value) ->
                (value as? JsonArray)?.forEach { (it as? JsonObject)?.let { o -> add(key, o) } }
            }
            is JsonArray -> eps.forEach { el ->
                when (el) {
                    is JsonArray -> el.forEach { (it as? JsonObject)?.let { o -> add(null, o) } }
                    is JsonObject -> add(null, el)
                    else -> Unit
                }
            }
            else -> Unit
        }
        return SeriesInfo(
            plot = info?.str("plot").orEmpty(),
            cover = info?.str("cover").orEmpty(),
            seasons = seasons.map { (n, list) -> Season(n, list.sortedBy { it.first }.map { it.second }) },
            backdrop = info?.firstImage("backdrop_path").orEmpty(),
            genre = info?.str("genre").orEmpty(),
            cast = info?.str("cast").orEmpty(),
            year = info?.str("releaseDate").orEmpty().ifBlank { info?.str("release_date").orEmpty() }.take(4),
            rating = info?.str("rating").orEmpty().takeIf { it != "0" }.orEmpty(),
        )
    }

    fun vodInfo(streamId: String): VodInfo {
        val text = call("get_vod_info", mapOf("vod_id" to streamId)) { it }
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw IOException("No details for this movie")
        val info = root["info"] as? JsonObject ?: JsonObject(emptyMap())
        return VodInfo(
            plot = info.str("plot").ifBlank { info.str("description") },
            poster = info.str("movie_image").ifBlank { info.str("cover_big") },
            backdrop = info.firstImage("backdrop_path"),
            genre = info.str("genre"),
            cast = info.str("cast").ifBlank { info.str("actors") },
            director = info.str("director"),
            year = info.str("releasedate").ifBlank { info.str("release_date") }.take(4),
            duration = info.str("duration"),
            rating = info.str("rating").takeIf { it != "0" }.orEmpty(),
            trailer = info.str("youtube_trailer"),
        )
    }

    /** backdrop_path is an array on most panels and a string on some. */
    private fun JsonObject.firstImage(key: String): String = when (val v = this[key]) {
        is JsonArray -> (v.firstOrNull() as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
        is JsonPrimitive -> if (v is JsonNull) "" else v.content
        else -> ""
    }

    private fun prefix(kind: String): String =
        base.newBuilder().addPathSegment(kind).addPathSegment(user).addPathSegment(pass)
            .build().toString().trimEnd('/')

    private fun categories(action: String): Map<String, String> {
        val map = HashMap<String, String>()
        try {
            Downloads.withRetries(onRetry = { map.clear() }) {
                forEachObject(action) { o ->
                    val id = o["category_id"] ?: return@forEachObject
                    map[id] = o["category_name"].orEmpty().ifBlank { "Uncategorized" }
                }
            }
        } catch (e: Exception) {
            // categories are cosmetic
        }
        return map
    }

    private fun request(action: String?, extra: Map<String, String> = emptyMap()): Request {
        val url = base.newBuilder().addPathSegment("player_api.php")
            .addQueryParameter("username", user)
            .addQueryParameter("password", pass)
            .apply {
                if (action != null) addQueryParameter("action", action)
                extra.forEach { (k, v) -> addQueryParameter(k, v) }
            }
            .build()
        return Request.Builder().url(url)
            .header("User-Agent", userAgent.ifBlank { Repository.DEFAULT_UA })
            .build()
    }

    private fun <T> call(action: String?, extra: Map<String, String> = emptyMap(), read: (String) -> T): T =
        http.newCall(request(action, extra)).execute().use { res ->
            if (!res.isSuccessful) throw HttpStatusException(res.code)
            read(res.body?.string().orEmpty())
        }

    /** Streams a JSON array of flat objects; nested values are skipped. */
    private fun forEachObject(action: String, onItem: (Map<String, String>) -> Unit) {
        http.newCall(request(action)).execute().use { res ->
            if (!res.isSuccessful) throw HttpStatusException(res.code)
            val body = res.body ?: throw IOException("Empty response")
            JsonReader(body.charStream()).use { r ->
                r.isLenient = true
                if (r.peek() == JsonToken.BEGIN_ARRAY) {
                    r.beginArray()
                    while (r.hasNext()) {
                        if (r.peek() == JsonToken.BEGIN_OBJECT) onItem(readFlat(r)) else r.skipValue()
                    }
                    r.endArray()
                } else {
                    r.skipValue() // e.g. {} when the list is empty
                }
            }
        }
    }

    private fun readFlat(r: JsonReader): Map<String, String> {
        val m = HashMap<String, String>()
        r.beginObject()
        while (r.hasNext()) {
            val key = r.nextName()
            when (r.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> m[key] = r.nextString()
                JsonToken.BOOLEAN -> m[key] = r.nextBoolean().toString()
                JsonToken.NULL -> r.nextNull()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return m
    }

    companion object {
        /** Splits http://host:port/get.php?username=U&password=P… into (server, user, pass). */
        fun fromM3uLink(link: String): Triple<String, String, String>? {
            val url = link.trim().toHttpUrlOrNull() ?: return null
            if (!url.encodedPath.endsWith("/get.php")) return null
            val user = url.queryParameter("username")?.takeIf { it.isNotBlank() } ?: return null
            val pass = url.queryParameter("password")?.takeIf { it.isNotBlank() } ?: return null
            val server = url.newBuilder()
                .encodedPath(url.encodedPath.removeSuffix("get.php"))
                .query(null)
                .build().toString().trimEnd('/')
            return Triple(server, user, pass)
        }

        fun normalize(server: String): String {
            var s = server.trim()
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            s = s.substringBefore("/player_api.php").substringBefore("/get.php")
            return s.trimEnd('/')
        }
    }
}

private fun JsonObject.str(key: String): String {
    val p = this[key] as? JsonPrimitive ?: return ""
    return if (p is JsonNull) "" else p.content
}
