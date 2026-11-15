package app.falak.player.cast

/**
 * What a Chromecast can be sent (8.28). It plays the link itself, from the provider: so not the
 * app's own local links (a torrent being streamed, the live buffer, a downloaded copy), and
 * without the app's headers (a provider that insists on a User-Agent may refuse it). Xtream's live
 * channels go as HLS (".m3u8"), which Google's standard receiver plays; plain ".ts" it doesn't.
 */
object CastLinks {
    /** The link to send for [url], or null when a Chromecast can't reach it. */
    fun castable(url: String): String? {
        val u = url.trim()
        if (!u.startsWith("http://", ignoreCase = true) && !u.startsWith("https://", ignoreCase = true)) return null
        val host = u.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        if (host == "127.0.0.1" || host == "localhost") return null
        return if (u.contains("/live/") && u.substringBefore('?').endsWith(".ts")) u.substringBefore('?').removeSuffix(".ts") + ".m3u8" + u.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" } else u
    }

    /** The kind of content, as the receiver wants it. */
    fun contentType(url: String): String {
        val u = url.substringBefore('?').lowercase()
        return when {
            u.endsWith(".m3u8") || u.endsWith(".m3u") -> "application/x-mpegurl"
            u.endsWith(".mpd") -> "application/dash+xml"
            u.endsWith(".mkv") -> "video/x-matroska"
            u.endsWith(".webm") -> "video/webm"
            u.endsWith(".ts") -> "video/mp2t"
            u.endsWith(".mp3") -> "audio/mpeg"
            else -> "video/mp4"
        }
    }
}
