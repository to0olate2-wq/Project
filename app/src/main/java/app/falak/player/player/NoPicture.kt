package app.falak.player.player

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/**
 * Films and series that played sound with a black picture on this device (8.51 / 8.52, the owner).
 *
 * The device's hardware decoder took the picture and drew nothing, so ExoPlayer (and VLC, which uses the
 * same decoder by default) showed black. Such a title plays with VLC decoding the picture itself, from
 * its start next time; and the kind of picture that failed (its codec and profile, 4K or not) is noted,
 * so another title of the same kind moves to VLC as soon as its picture is known, not after a wait.
 * H.264 is never noted: every device plays it, and a failure there is the stream's, not the decoder's.
 * Kept on this device only (SharedPreferences "no_picture").
 */
object NoPicture {
    private const val PREFS = "no_picture"
    private const val MAX_TITLES = 300
    private const val ROUND = 2

    private var prefs: SharedPreferences? = null
    private val titles = LinkedHashSet<String>()
    private val kinds = HashSet<String>()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        // 8.53: Dolby Vision films now play with ExoPlayer: what 8.51 / 8.52 noted is forgotten once.
        if (p.getInt("round", 1) < ROUND) p.edit().clear().putInt("round", ROUND).apply()
        p.getString("titles", "")?.split('\n')?.filter { it.isNotBlank() }?.let { titles += it }
        p.getString("kinds", "")?.split('\n')?.filter { it.isNotBlank() }?.let { kinds += it }
    }

    @Synchronized
    fun has(id: String): Boolean = id in titles

    /** A picture of a kind that already came up black on this device. */
    @Synchronized
    fun knownKind(format: Format?): Boolean = kindOf(format)?.let { it in kinds } == true

    @Synchronized
    fun note(id: String, format: Format?) {
        titles.remove(id)
        titles += id
        while (titles.size > MAX_TITLES) titles.remove(titles.first())
        kindOf(format)?.let { kinds += it }
        prefs?.edit()
            ?.putString("titles", titles.joinToString("\n"))
            ?.putString("kinds", kinds.joinToString("\n"))
            ?.apply()
    }

    /**
     * "video/hevc|hvc1.2|4k": the codec, its profile (the first part of the codec string after the
     * name) and whether it's 4K. Null for H.264 and for what isn't a known picture.
     */
    fun kindOf(format: Format?): String? {
        val mime = format?.sampleMimeType ?: return null
        if (!MimeTypes.isVideo(mime) || mime == MimeTypes.VIDEO_H264) return null
        val parts = format.codecs?.split('.').orEmpty()
        val profile = if (parts.size > 1) parts[0] + "." + parts[1].take(4) else parts.firstOrNull().orEmpty()
        val big = if (maxOf(format.width, format.height) >= 2500) "|4k" else ""
        return "$mime|$profile$big"
    }

    /** Tests: forget everything (no SharedPreferences under a plain JVM). */
    @Synchronized
    internal fun clearForTest() {
        titles.clear()
        kinds.clear()
    }
}
