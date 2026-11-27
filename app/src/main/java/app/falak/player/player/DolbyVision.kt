package app.falak.player.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/**
 * Dolby Vision films on a screen or box that can't show Dolby Vision (8.53, the owner: "some versions of
 * 4K" played sound over a black picture; VLC showed them, in the wrong colours and stuttering).
 *
 * ExoPlayer plays Dolby Vision profiles 4 and 8 through the ordinary HEVC decoder (their base picture)
 * when the screen can't show Dolby Vision, but not profile 5 (most 4K web copies) or 7 (4K disc copies):
 * it found no decoder and played the sound alone. Here the HEVC decoder takes those too, in hardware:
 * profile 7's base picture is ordinary HDR10, so it looks right; profile 5 has no ordinary picture inside
 * (its colours are Dolby's own), so without Dolby Vision on the screen its colours look off, as they did
 * in VLC, but it plays smoothly.
 */
object DolbyVision {
    /** The screen says it can show Dolby Vision (Android 8+; ExoPlayer asks the same). */
    fun screenShowsIt(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        return runCatching {
            val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
            display.isHdr && display.hdrCapabilities.supportedHdrTypes.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)
        }.getOrDefault(false)
    }

    /**
     * Decoders for [mime]: for Dolby Vision, when the screen can't show it or the device has no Dolby
     * Vision decoder, the HEVC decoders. [software] puts software decoders first (the player's setting).
     */
    fun selector(screenShowsDolbyVision: Boolean, software: Boolean): MediaCodecSelector =
        MediaCodecSelector { mime, secure, tunneling ->
            var list: List<MediaCodecInfo> = MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
            if (mime == MimeTypes.VIDEO_DOLBY_VISION && (list.isEmpty() || !screenShowsDolbyVision)) {
                val hevc = MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H265, secure, tunneling)
                if (hevc.isNotEmpty()) list = hevc
            }
            if (software) list.sortedBy { if (it.softwareOnly) 0 else 1 } else list
        }

    /** Profile 5: Dolby Vision only, no ordinary picture inside ("dvhe.05…" / "dvh1.05…"). */
    fun onlyDolby(format: Format?): Boolean {
        if (format?.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return false
        val c = format.codecs ?: return false
        return c.startsWith("dvhe.05") || c.startsWith("dvh1.05")
    }
}
