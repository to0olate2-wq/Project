package app.falak.player.record

import java.net.URI

/**
 * Just enough of HLS (.m3u8) to save a stream: a master playlist's versions and a media
 * playlist's parts, with their AES-128 keys and fMP4 headers.
 */
internal object Hls {
    class Variant(val uri: String, val bandwidth: Long, val audioGroup: String?)

    /** A master playlist: its versions, and the audio groups whose sound comes as separate parts. */
    class Master(val variants: List<Variant>, val separateAudio: Set<String>)

    class Key(val method: String, val uri: String?, val iv: ByteArray?)

    class Segment(
        val uri: String,
        val durationMs: Long,
        val sequence: Long,
        val key: Key?,
        /** The fMP4 header these parts need (EXT-X-MAP), if any. */
        val map: String?,
    )

    class Media(val targetMs: Long, val segments: List<Segment>, val ended: Boolean)

    fun isPlaylist(text: String): Boolean = text.trimStart('﻿', ' ', '\r', '\n', '\t').startsWith("#EXTM3U")

    fun isMaster(text: String): Boolean = text.lineSequence().any { it.trim().startsWith("#EXT-X-STREAM-INF") }

    fun parseMaster(text: String, base: String): Master {
        val variants = ArrayList<Variant>()
        val separate = HashSet<String>()
        var pending: Map<String, String>? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> pending = attributes(line.substringAfter(':'))
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val a = attributes(line.substringAfter(':'))
                    if (a["TYPE"].equals("AUDIO", ignoreCase = true) && !a["URI"].isNullOrBlank()) a["GROUP-ID"]?.let { separate += it }
                }
                line.isEmpty() || line.startsWith("#") -> Unit
                pending != null -> {
                    val a = pending
                    variants += Variant(resolve(base, line), a["BANDWIDTH"]?.toLongOrNull() ?: 0L, a["AUDIO"])
                    pending = null
                }
            }
        }
        return Master(variants, separate)
    }

    /**
     * The version to save: the best one whose sound is in the same parts as its picture (a
     * version whose sound comes separately can't be saved as one file). Null when there's none.
     */
    fun pick(m: Master): Variant? =
        m.variants.filter { it.audioGroup == null || it.audioGroup !in m.separateAudio }.maxByOrNull { it.bandwidth }

    fun parseMedia(text: String, base: String): Media {
        val segments = ArrayList<Segment>()
        var target = 6_000L
        var sequence = 0L
        var duration = 0L
        var key: Key? = null
        var map: String? = null
        var ended = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-TARGETDURATION:") -> target = ((line.substringAfter(':').trim().toDoubleOrNull() ?: 6.0) * 1000).toLong()
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXTINF:") -> duration = ((line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0) * 1000).toLong()
                line.startsWith("#EXT-X-KEY:") -> {
                    val a = attributes(line.substringAfter(':'))
                    val method = a["METHOD"].orEmpty().uppercase()
                    key = if (method == "NONE" || method.isEmpty()) null else Key(method, a["URI"]?.let { resolve(base, it) }, a["IV"]?.let(::hex))
                }
                line.startsWith("#EXT-X-MAP:") -> map = attributes(line.substringAfter(':'))["URI"]?.let { resolve(base, it) }
                line.startsWith("#EXT-X-ENDLIST") -> ended = true
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    segments += Segment(resolve(base, line), duration, sequence, key, map)
                    sequence++
                    duration = 0L
                }
            }
        }
        return Media(target, segments, ended)
    }

    /** `A=1,B="x,y",C=z` → map (quotes taken off). */
    fun attributes(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        while (i < s.length) {
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            val name = s.substring(i, eq).trim().trimStart(',').trim()
            var j = eq + 1
            val value: String
            if (j < s.length && s[j] == '"') {
                val end = s.indexOf('"', j + 1).let { if (it < 0) s.length else it }
                value = s.substring(j + 1, end)
                j = end + 1
            } else {
                val end = s.indexOf(',', j).let { if (it < 0) s.length else it }
                value = s.substring(j, end).trim()
                j = end
            }
            if (name.isNotEmpty()) out[name.uppercase()] = value
            i = j + 1
        }
        return out
    }

    fun resolve(base: String, ref: String): String = try {
        URI(base).resolve(ref.replace(" ", "%20")).toString()
    } catch (e: Exception) {
        if (ref.startsWith("http://") || ref.startsWith("https://")) ref else base.substringBeforeLast('/') + "/" + ref
    }

    /** An AES-128 IV: the one given (0x…), else the part's number as 16 bytes. */
    fun iv(key: Key, sequence: Long): ByteArray = key.iv ?: ByteArray(16).also { b ->
        var v = sequence
        for (k in 15 downTo 8) {
            b[k] = (v and 0xFF).toByte()
            v = v ushr 8
        }
    }

    private fun hex(s: String): ByteArray? {
        val h = s.removePrefix("0x").removePrefix("0X")
        if (h.isEmpty() || h.length > 32) return null
        val padded = h.padStart(32, '0')
        return try {
            ByteArray(16) { i -> padded.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
