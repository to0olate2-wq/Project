package app.falak.player.player

/**
 * The Player → Stream info window (8.28): what is playing, in words (resolution, frame rate,
 * codecs, the stream's own data rate, how fast it is coming in, how much is buffered, frames
 * dropped). The player fills in what its engine knows; what isn't known is left out.
 */
object StreamInfo {
    fun videoCodec(mime: String?): String? = when (mime?.lowercase()) {
        null, "" -> null
        "video/avc" -> "H.264"
        "video/hevc" -> "H.265 (HEVC)"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/mpeg2" -> "MPEG-2"
        "video/mp4v-es" -> "MPEG-4"
        "video/dolby-vision" -> "Dolby Vision"
        else -> mime.substringAfter('/').uppercase()
    }

    fun audioCodec(mime: String?): String? = when (mime?.lowercase()) {
        null, "" -> null
        "audio/mp4a-latm" -> "AAC"
        "audio/ac3" -> "Dolby Digital"
        "audio/eac3" -> "Dolby Digital Plus"
        "audio/eac3-joc" -> "Dolby Atmos (DD+)"
        "audio/ac4" -> "Dolby AC-4"
        "audio/true-hd" -> "Dolby TrueHD"
        "audio/vnd.dts" -> "DTS"
        "audio/vnd.dts.hd" -> "DTS-HD"
        "audio/mpeg" -> "MP3"
        "audio/mpeg-l2" -> "MP2"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        "audio/raw" -> "PCM"
        else -> mime.substringAfter('/').uppercase()
    }

    /** "1080p" from the picture's height (the width for very wide pictures). */
    fun quality(width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        val h = maxOf(height, width * 9 / 16)
        return when {
            h >= 2000 -> "4K"
            h >= 1400 -> "1440p"
            h >= 1000 -> "1080p"
            h >= 700 -> "720p"
            h >= 540 -> "576p"
            h >= 460 -> "480p"
            else -> "${height}p"
        }
    }

    fun rate(bps: Long): String? = when {
        bps <= 0 -> null
        bps >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f Mb/s", bps / 1_000_000.0)
        else -> "${bps / 1_000} kb/s"
    }

    fun channels(n: Int): String? = when (n) {
        in Int.MIN_VALUE..0 -> null
        1 -> "mono"
        2 -> "stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "$n channels"
    }

    /** What kind of link: "HLS", "MPEG-TS", "MP4 file"… */
    fun linkKind(url: String): String {
        val u = url.substringBefore('?').lowercase()
        return when {
            u.startsWith("http://127.0.0.1") && u.contains("/video.") -> "Torrent, streamed"
            u.startsWith("http://127.0.0.1") -> "Through the live buffer"
            u.startsWith("file:") || u.startsWith("content:") -> "Downloaded copy"
            u.endsWith(".m3u8") || u.contains(".m3u8") -> "HLS"
            u.endsWith(".mpd") -> "DASH"
            u.endsWith(".ts") -> "MPEG-TS"
            u.endsWith(".mkv") -> "MKV file"
            u.endsWith(".mp4") || u.endsWith(".m4v") -> "MP4 file"
            u.endsWith(".avi") -> "AVI file"
            u.startsWith("rtmp") -> "RTMP"
            else -> "Stream"
        }
    }

    /** The window's lines, label → value; only what is known. */
    fun lines(
        engine: String,
        width: Int,
        height: Int,
        fps: Float,
        videoMime: String?,
        declaredBps: Long,
        audioMime: String?,
        channels: Int,
        sampleRate: Int,
        audioLang: String?,
        incomingBps: Long,
        bufferedMs: Long,
        dropped: Int,
        link: String,
    ): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val picture = listOfNotNull(
            if (width > 0 && height > 0) "$width × $height" else null,
            quality(width, height),
            if (fps > 0f) (if (fps % 1f == 0f) "${fps.toInt()} fps" else String.format(java.util.Locale.US, "%.2f fps", fps)) else null,
            videoCodec(videoMime),
        )
        if (picture.isNotEmpty()) out += "Picture" to picture.joinToString(" · ")
        val sound = listOfNotNull(
            audioCodec(audioMime),
            channels(channels),
            if (sampleRate > 0) "${sampleRate / 1000.0} kHz".replace(".0 kHz", " kHz") else null,
            audioLang?.takeIf { it.isNotBlank() && it != "und" }?.let { java.util.Locale(it).getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { it } },
        )
        if (sound.isNotEmpty()) out += "Sound" to sound.joinToString(" · ")
        rate(declaredBps)?.let { out += "Stream's rate" to it }
        rate(incomingBps)?.let { out += "Coming in at" to it }
        if (bufferedMs > 0) out += "Ready ahead" to String.format(java.util.Locale.US, "%.1f s", bufferedMs / 1000.0)
        if (dropped > 0) out += "Frames dropped" to dropped.toString()
        out += "Link" to link
        out += "Playing with" to engine
        return out
    }
}
