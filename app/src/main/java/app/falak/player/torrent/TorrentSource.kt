package app.falak.player.torrent

/**
 * A torrent link as an add-on gives it (8.16): the torrent's hash, which of its files to play (a
 * season pack has many), trackers to find people sharing it, and the file's name when known.
 */
data class TorrentSource(
    /** 40 hex characters, lower case. */
    val infoHash: String,
    val fileIdx: Int? = null,
    val trackers: List<String> = emptyList(),
    val fileName: String = "",
) {
    fun magnet(): String = buildString {
        append("magnet:?xt=urn:btih:").append(infoHash)
        for (t in trackers.distinct().take(MAX_TRACKERS)) {
            append("&tr=").append(java.net.URLEncoder.encode(t, "UTF-8"))
        }
    }

    companion object {
        private const val MAX_TRACKERS = 40
        private val HEX = Regex("[0-9a-fA-F]{40}")
        private val BASE32 = Regex("[A-Za-z2-7]{32}")

        /** A hash as add-ons and magnet links write it (hex, or the older 32 letters) → 40 hex characters, or null. */
        fun cleanHash(raw: String): String? {
            val s = raw.trim()
            return when {
                HEX.matches(s) -> s.lowercase()
                BASE32.matches(s) -> base32ToHex(s.uppercase())
                else -> null
            }
        }

        private fun base32ToHex(s: String): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            val out = StringBuilder(40)
            var buffer = 0
            var bits = 0
            for (c in s) {
                buffer = (buffer shl 5) or alphabet.indexOf(c)
                bits += 5
                while (bits >= 4) {
                    bits -= 4
                    out.append("0123456789abcdef"[(buffer shr bits) and 0xF])
                }
                buffer = buffer and ((1 shl bits) - 1)
            }
            return out.toString()
        }

        /**
         * An add-on's "sources" ("tracker:udp://…", "dht:<hash>") or a magnet's "tr" values → the
         * trackers among them (http, https or udp links).
         */
        fun trackersFrom(sources: List<String>): List<String> = sources.mapNotNull { s ->
            val t = s.trim().removePrefix("tracker:")
            t.takeIf { it.startsWith("udp://") || it.startsWith("http://") || it.startsWith("https://") }
        }.distinct()

        /** "magnet:?xt=urn:btih:<hash>&tr=…" → the link, or null when it isn't a torrent magnet. */
        fun fromMagnet(uri: String, fileIdx: Int? = null, fileName: String = ""): TorrentSource? {
            if (!uri.startsWith("magnet:", ignoreCase = true)) return null
            val params = uri.substringAfter('?', "").split('&').mapNotNull { p ->
                val k = p.substringBefore('=', "")
                if (k.isEmpty()) null else k.lowercase() to runCatching { java.net.URLDecoder.decode(p.substringAfter('='), "UTF-8") }.getOrDefault("")
            }
            val hash = params.firstNotNullOfOrNull { (k, v) ->
                if (k == "xt" && v.startsWith("urn:btih:", ignoreCase = true)) cleanHash(v.substring(9)) else null
            } ?: return null
            return TorrentSource(hash, fileIdx, trackersFrom(params.filter { it.first == "tr" }.map { it.second }), fileName)
        }
    }
}

/** Why a torrent link can't play, in words for the owner of the app ("Nobody is sharing this link right now."). */
class TorrentException(message: String) : Exception(message)
