package app.falak.player.data

import app.falak.player.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

class SubtitleResult(val fileId: Long, val language: String, val release: String, val downloads: Int)

/**
 * OpenSubtitles.com (REST API v1) for movies and episodes whose stream has no subtitles.
 * Needs the user's free API key; logging in raises the daily download limit.
 */
class OpenSubtitles(private val http: OkHttpClient, private val apiKey: String, private val user: String, private val pass: String) {

    private val base = "https://api.opensubtitles.com/api/v1"
    private val agent = "ThePlayer v${BuildConfig.VERSION_NAME}"
    private val jsonType = "application/json".toMediaType()

    private fun request(url: String) = Request.Builder().url(url)
        .header("Api-Key", apiKey)
        .header("User-Agent", agent)
        .header("Accept", "application/json")

    private fun token(): String? {
        if (user.isBlank() || pass.isBlank()) return null
        cachedToken?.let { return it }
        val body = buildJsonObject {
            put("username", user)
            put("password", pass)
        }.toString().toRequestBody(jsonType)
        http.newCall(request("$base/login").post(body).build()).execute().use { res ->
            if (res.code == 401) throw IOException("OpenSubtitles login failed: check username and password")
            if (!res.isSuccessful) throw IOException("OpenSubtitles login failed (HTTP ${res.code})")
            val root = Json.parseToJsonElement(res.body?.string().orEmpty()) as? JsonObject
            return root?.text("token")?.takeIf { it.isNotEmpty() }?.also { cachedToken = it }
        }
    }

    /** Searches by title (+ year) or, for episodes, by show title + season/episode. */
    fun search(title: String, year: String?, season: Int?, episode: Int?, languages: List<String>): List<SubtitleResult> {
        // The API wants lower-case, alphabetically ordered parameters.
        val params = sortedMapOf<String, String>()
        if (episode != null) params["episode_number"] = "$episode"
        params["languages"] = languages.joinToString(",") { it.lowercase() }
        params["order_by"] = "download_count"
        params["query"] = title.lowercase()
        if (season != null) params["season_number"] = "$season"
        if (!year.isNullOrBlank() && season == null) params["year"] = year
        val url = "$base/subtitles".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        http.newCall(request(url.toString()).build()).execute().use { res ->
            if (res.code == 401 || res.code == 403) throw IOException("OpenSubtitles rejected the API key. Check it in Settings.")
            if (!res.isSuccessful) throw IOException("Subtitle search failed (HTTP ${res.code})")
            val root = Json.parseToJsonElement(res.body?.string().orEmpty()) as? JsonObject ?: return emptyList()
            val data = root["data"] as? JsonArray ?: return emptyList()
            return data.mapNotNull { item ->
                val attrs = (item as? JsonObject)?.get("attributes") as? JsonObject ?: return@mapNotNull null
                val file = (attrs["files"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@mapNotNull null
                val id = file.text("file_id").toLongOrNull() ?: return@mapNotNull null
                SubtitleResult(
                    fileId = id,
                    language = attrs.text("language"),
                    release = attrs.text("release").ifBlank { file.text("file_name") },
                    downloads = attrs.text("download_count").toIntOrNull() ?: 0,
                )
            }
        }
    }

    /** Downloads the subtitle file (SRT) into [dir] and returns it. */
    fun download(result: SubtitleResult, dir: File): File {
        val body = buildJsonObject { put("file_id", result.fileId) }.toString().toRequestBody(jsonType)
        val req = request("$base/download").post(body)
        token()?.let { req.header("Authorization", "Bearer $it") }
        val link = http.newCall(req.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            if (res.code == 406 || res.code == 429) {
                val reset = root?.text("reset_time").orEmpty()
                throw IOException("Daily subtitle download limit reached" + if (reset.isNotEmpty()) " (resets in $reset)" else "")
            }
            if (res.code == 401) throw IOException("Log in to OpenSubtitles in Settings to download subtitles")
            if (!res.isSuccessful) throw IOException("Subtitle download failed (HTTP ${res.code})")
            root?.text("link").orEmpty()
        }
        if (link.isEmpty()) throw IOException("OpenSubtitles returned no download link")
        dir.mkdirs()
        val out = File(dir, "sub-${result.fileId}.srt")
        http.newCall(Request.Builder().url(link).header("User-Agent", agent).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("Subtitle file download failed (HTTP ${res.code})")
            res.body?.byteStream()?.use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        return out
    }

    private fun JsonObject.text(key: String): String {
        val p = this[key] as? JsonPrimitive ?: return ""
        return if (p is JsonNull) "" else p.content
    }

    companion object {
        @Volatile private var cachedToken: String? = null

        private val NOISE = Regex("(?i)\\b(4k|uhd|fhd|hd|sd|1080p|720p|2160p|multi|vostfr|subbed|dubbed|hevc|x265|x264)\\b")
        private val PREFIX = Regex("^\\s*[\\[(]?[A-Za-z]{2,3}[\\])]?\\s*(?:[|\\-–]\\s+|:\\s*)")
        private val YEAR = Regex("\\b(19|20)\\d{2}\\b")

        /** "EN - The Matrix (1999) [4K]" → ("the matrix", "1999") */
        fun cleanTitle(name: String): Pair<String, String?> {
            val year = YEAR.find(name)?.value
            var t = PREFIX.replace(name, "")
            t = t.replace(Regex("[\\[(][^\\])]*[\\])]"), " ")
            t = NOISE.replace(t, " ")
            if (year != null) t = t.replace(year, " ")
            t = t.replace(Regex("[._]+"), " ").replace(Regex("\\s+"), " ").trim(' ', '-', '|', ':')
            return t to year
        }
    }
}
