package app.falak.player.player

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/** The video players the app can use. */
enum class Engine(val key: String, val label: String, val short: String) {
    EXO("exo", "ExoPlayer · hardware decoding", "ExoPlayer"),
    EXO_SOFT("exo_soft", "ExoPlayer · software decoding", "ExoPlayer (software)"),
    VLC("vlc", "VLC", "VLC"),
    EXTERNAL("external", "Another app (VLC, MX Player…)", "Other app");

    companion object {
        fun of(key: String): Engine? = entries.firstOrNull { it.key == key }
    }
}

/** What the app picked for this device in Automatic mode, and why. */
class EngineAdvice(val engine: Engine, val reason: String, val device: String)

/**
 * Looks at the TV's video decoders and memory once and picks the player that should work best.
 * In Automatic mode a channel that the chosen player can't open is retried with VLC.
 */
object EngineAdvisor {
    @Volatile private var cached: EngineAdvice? = null

    fun advise(context: Context): EngineAdvice {
        cached?.let { return it }
        val hw = hardwareDecoders()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ramGb = mem.totalMem / (1024.0 * 1024 * 1024)
        fun has(mime: String) = mime in hw
        val codecs = listOf("H.264" to "video/avc", "HEVC" to "video/hevc", "MPEG-2" to "video/mpeg2")
            .joinToString("  ") { (name, mime) -> "$name ${if (has(mime)) "✓" else "✗"}" }
        val device = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · " +
            String.format(java.util.Locale.US, "%.1f GB RAM", ramGb) + " · hardware: $codecs"
        val advice = when {
            // Couldn't read the decoder list at all: ExoPlayer is the safe default.
            hw.isEmpty() -> EngineAdvice(Engine.EXO, "ExoPlayer is the default; channels it can't open are retried with VLC.", device)
            !has("video/avc") -> EngineAdvice(
                Engine.VLC,
                "No hardware H.264 decoder was found, so VLC (which decodes well in software) plays best here.",
                device,
            )
            else -> EngineAdvice(
                Engine.EXO,
                "This TV decodes H.264" + (if (has("video/hevc")) " and HEVC" else "") +
                    " in hardware: ExoPlayer is the smoothest and lightest. Channels it can't open are retried with VLC.",
                device,
            )
        }
        cached = advice
        return advice
    }

    /** MIME types with a hardware (not software-only) video decoder. */
    private fun hardwareDecoders(): Set<String> = try {
        val out = HashSet<String>()
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder || !isHardware(info)) continue
            for (type in info.supportedTypes) out += type.lowercase()
        }
        out
    } catch (e: Exception) {
        emptySet()
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isHardwareAccelerated
        val n = info.name.lowercase()
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains("ffmpeg") || n.contains(".sw."))
    }

    /** The engine to start with: the user's choice, or the advice in Automatic mode. */
    fun resolve(context: Context, setting: String): Engine =
        Engine.of(setting) ?: advise(context).engine
}
