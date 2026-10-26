package app.falak.player.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Where a download stands. */
@Serializable
enum class OfflineState { QUEUED, RUNNING, PAUSED, FAILED, DONE }

/**
 * A film or episode saved on this device to watch without internet: what it is (enough to show
 * and play it with no playlist loaded), where it comes from, and how far the download has got.
 */
@Serializable
data class OfflineItem(
    /** The channel id (a film's or an episode's). */
    val id: String,
    val playlistId: String,
    val name: String,
    val kind: Kind,
    val logo: String = "",
    /** Episodes: "S1 · E2 · Title". */
    val info: String = "",
    val seriesId: String = "",
    val group: String = "",
    val url: String,
    val userAgent: String = "",
    val referrer: String = "",
    /** The finished file (absolute path), once done. */
    val file: String = "",
    /** Whole size in bytes (0 while unknown). */
    val size: Long = 0,
    /** Bytes saved so far. */
    val done: Long = 0,
    val state: OfflineState = OfflineState.QUEUED,
    /** Why it stopped (failed), shown under it. */
    val note: String = "",
    val addedAt: Long = 0,
) {
    val fraction: Float get() = if (size > 0) (done.toFloat() / size).coerceIn(0f, 1f) else 0f

    /** The download in progress, before it is renamed to its finished name. */
    val partName: String get() = safeName(id) + ".part"

    /** The item as a playable channel (the player uses the saved file, see OfflineCenter.fileFor). */
    fun asChannel(): Channel = Channel(
        id = id, name = name, group = group, url = url, logo = logo, kind = kind,
        seriesId = seriesId, info = info, userAgent = userAgent, referrer = referrer,
    )

    companion object {
        /** A file name from an id ("m123" → "m123"; anything odd becomes "_"). */
        fun safeName(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
    }
}

/** The list of downloads, kept in a small file next to the app's other data. */
object OfflineStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val serializer = ListSerializer(OfflineItem.serializer())

    fun read(file: File): List<OfflineItem> = try {
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    fun write(file: File, items: List<OfflineItem>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, items))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** The finished file's extension: from the address, else the type the server gave, else mp4. */
    fun extensionFor(url: String, contentType: String): String {
        val fromUrl = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
        if (fromUrl in VIDEO_EXTENSIONS) return fromUrl
        val type = contentType.lowercase()
        return when {
            "matroska" in type -> "mkv"
            "webm" in type -> "webm"
            "quicktime" in type -> "mov"
            "x-msvideo" in type || "avi" in type -> "avi"
            "mp2t" in type -> "ts"
            else -> "mp4"
        }
    }

    /** "bytes 100-199/1234" → 1234. */
    fun totalFromContentRange(header: String?): Long? =
        header?.substringAfterLast('/')?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    /** True for answers that are a list of parts (HLS) or a page, not a video file to save. */
    fun notAFile(contentType: String): Boolean {
        val t = contentType.lowercase()
        return "mpegurl" in t || "dash+xml" in t || t.startsWith("text/") || "json" in t
    }

    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "ts", "wmv", "flv", "mpg", "mpeg", "3gp")
}
