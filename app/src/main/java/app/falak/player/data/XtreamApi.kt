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

    fun loadAll(): List<Channel> {
        val out = ArrayList<Channel>()
        val ids = HashSet<String>()

        val liveCats = categories("get_live_categories")
        val livePrefix = prefix("live")
        forEachObject("get_live_streams") { o ->
            val sid = o["stream_id"].orEmpty()
            if (sid.isEmpty() || !ids.add("l$sid")) return@forEachObject
            out += Channel(
                id = "l$sid",
                name = o["name"].orEmpty().ifBlank { "Channel $sid" },
                group = o["category_id"]?.let { liveCats[it] } ?: "Uncategorized",
                url = "$livePrefix/$sid.ts",
                logo = o["stream_icon"].orEmpty(),
                tvgId = o["epg_channel_id"].orEmpty(),
                kind = Kind.LIVE,
                added = o["added"]?.toLongOrNull() ?: 0L,
            )
        }

        // Movies are optional: some servers time out here; keep live TV working regardless.
        try {
            val vodCats = categories("get_vod_categories")
            val vodPrefix = prefix("movie")
            forEachObject("get_vod_streams") { o ->
                val sid = o["stream_id"].orEmpty()
                if (sid.isEmpty() || !ids.add("m$sid")) return@forEachObject
                val ext = o["container_extension"].orEmpty().ifBlank { "mp4" }
                out += Channel(
                    id = "m$sid",
                    name = o["name"].orEmpty().ifBlank { "Movie $sid" },
                    group = o["category_id"]?.let { vodCats[it] } ?: "Uncategorized",
                    url = "$vodPrefix/$sid.$ext",
                    logo = o["stream_icon"].orEmpty(),
                    kind = Kind.MOVIE,
                    added = o["added"]?.toLongOrNull() ?: 0L,
                    rating = o["rating"].orEmpty().takeIf { it != "0" }.orEmpty(),
                )
            }
        } catch (e: IOException) {
            // ignore
        }

        // Series list; episodes are fetched when a series is opened.
        try {
            val seriesCats = categories("get_series_categories")
            forEachObject("get_series") { o ->
                val sid = o["series_id"].orEmpty()
                if (sid.isEmpty() || !ids.add("s$sid")) return@forEachObject
                out += Channel(
                    id = "s$sid",
                    name = o["name"].orEmpty().ifBlank { "Series $sid" },
                    group = o["category_id"]?.let { seriesCats[it] } ?: "Uncategorized",
                    url = "",
                    logo = o["cover"].orEmpty(),
                    kind = Kind.SERIES,
                    seriesId = sid,
                    added = o["last_modified"]?.toLongOrNull() ?: 0L,
                    rating = o["rating"].orEmpty().takeIf { it != "0" }.orEmpty(),
                )
            }
        } catch (e: IOException) {
            // ignore
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

        fun add(seasonKey: String?, o: JsonObject) {
            val id = o.str("id")
            if (id.isEmpty()) return
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
        )
    }

    private fun prefix(kind: String): String =
        base.newBuilder().addPathSegment(kind).addPathSegment(user).addPathSegment(pass)
            .build().toString().trimEnd('/')

    private fun categories(action: String): Map<String, String> {
        val map = HashMap<String, String>()
        try {
            forEachObject(action) { o ->
                val id = o["category_id"] ?: return@forEachObject
                map[id] = o["category_name"].orEmpty().ifBlank { "Uncategorized" }
            }
        } catch (e: IOException) {
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
            if (!res.isSuccessful) throw IOException("Server returned HTTP ${res.code}")
            read(res.body?.string().orEmpty())
        }

    /** Streams a JSON array of flat objects; nested values are skipped. */
    private fun forEachObject(action: String, onItem: (Map<String, String>) -> Unit) {
        http.newCall(request(action)).execute().use { res ->
            if (!res.isSuccessful) throw IOException("Server returned HTTP ${res.code}")
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
