package com.novatv.player.data

import android.util.JsonReader
import android.util.JsonToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
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
                )
            }
        } catch (e: IOException) {
            // ignore
        }
        return out
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

    private fun request(action: String?): Request {
        val url = base.newBuilder().addPathSegment("player_api.php")
            .addQueryParameter("username", user)
            .addQueryParameter("password", pass)
            .apply { if (action != null) addQueryParameter("action", action) }
            .build()
        return Request.Builder().url(url)
            .header("User-Agent", userAgent.ifBlank { Repository.DEFAULT_UA })
            .build()
    }

    private fun <T> call(action: String?, read: (String) -> T): T =
        http.newCall(request(action)).execute().use { res ->
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
