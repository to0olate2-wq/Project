package app.falak.player.data

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * Compact binary caches for the playlist and the guide. Reading them is several times faster
 * than JSON and needs far less memory, so a big playlist opens almost instantly.
 * Repeated strings (groups, user agents, catch-up types) are stored once in a string table.
 */
object Store {
    private const val PLAYLIST_MAGIC = 0x54504C31 // "TPL1"
    private const val GUIDE_MAGIC = 0x54504731 // "TPG1"
    private const val VERSION = 1

    // ---- playlist ----

    fun writePlaylist(file: File, cache: PlaylistCache) {
        val tmp = File(file.path + ".tmp")
        DataOutputStream(BufferedOutputStream(tmp.outputStream(), 256 * 1024)).use { out ->
            out.writeInt(PLAYLIST_MAGIC)
            out.writeInt(VERSION)
            out.writeLong(cache.savedAt)
            str(out, cache.epgUrl)
            str(out, cache.serverTz)
            val table = Pool()
            for (ch in cache.channels) {
                table.add(ch.group); table.add(ch.userAgent); table.add(ch.referrer); table.add(ch.catchupType)
            }
            out.writeInt(table.list.size)
            for (s in table.list) str(out, s)
            out.writeInt(cache.channels.size)
            for (ch in cache.channels) {
                str(out, ch.id)
                str(out, ch.name)
                out.writeInt(table.idOf(ch.group))
                str(out, ch.url)
                str(out, ch.logo)
                str(out, ch.tvgId)
                out.writeByte(ch.kind.ordinal)
                out.writeInt(table.idOf(ch.userAgent))
                out.writeInt(table.idOf(ch.referrer))
                str(out, ch.seriesId)
                str(out, ch.info)
                out.writeLong(ch.added)
                str(out, ch.rating)
                out.writeShort(ch.catchupDays)
                out.writeInt(table.idOf(ch.catchupType))
                str(out, ch.catchupSource)
            }
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Can't save the playlist cache")
        }
    }

    fun readPlaylist(file: File): PlaylistCache =
        DataInputStream(BufferedInputStream(file.inputStream(), 256 * 1024)).use { inp ->
            if (inp.readInt() != PLAYLIST_MAGIC || inp.readInt() != VERSION) throw IOException("Old cache format")
            val savedAt = inp.readLong()
            val epgUrl = str(inp)
            val serverTz = str(inp)
            val table = Array(inp.readInt()) { str(inp) }
            val kinds = Kind.entries
            val n = inp.readInt()
            val list = ArrayList<Channel>(n)
            repeat(n) {
                list += Channel(
                    id = str(inp),
                    name = str(inp),
                    group = table[inp.readInt()],
                    url = str(inp),
                    logo = str(inp),
                    tvgId = str(inp),
                    kind = kinds[inp.readByte().toInt()],
                    userAgent = table[inp.readInt()],
                    referrer = table[inp.readInt()],
                    seriesId = str(inp),
                    info = str(inp),
                    added = inp.readLong(),
                    rating = str(inp),
                    catchupDays = inp.readShort().toInt(),
                    catchupType = table[inp.readInt()],
                    catchupSource = str(inp),
                )
            }
            PlaylistCache(list, epgUrl, serverTz, savedAt)
        }

    // ---- guide ----

    fun writeGuide(file: File, cache: GuideCache) {
        val tmp = File(file.path + ".tmp")
        DataOutputStream(BufferedOutputStream(tmp.outputStream(), 256 * 1024)).use { out ->
            out.writeInt(GUIDE_MAGIC)
            out.writeInt(VERSION)
            out.writeLong(cache.savedAt)
            out.writeInt(cache.matched)
            out.writeInt(cache.total)
            out.writeInt(cache.programs.size)
            for ((key, list) in cache.programs) {
                str(out, key)
                out.writeInt(list.size)
                for (p in list) {
                    out.writeLong(p.start)
                    out.writeInt(((p.stop - p.start) / 1000).toInt())
                    str(out, p.title)
                    str(out, p.desc)
                }
            }
            out.writeInt(cache.channels.size)
            for (c in cache.channels) {
                str(out, c.id)
                str(out, c.name)
                str(out, c.source)
            }
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Can't save the guide cache")
        }
    }

    fun readGuide(file: File): GuideCache =
        DataInputStream(BufferedInputStream(file.inputStream(), 256 * 1024)).use { inp ->
            if (inp.readInt() != GUIDE_MAGIC || inp.readInt() != VERSION) throw IOException("Old guide format")
            val savedAt = inp.readLong()
            val matched = inp.readInt()
            val total = inp.readInt()
            val keys = inp.readInt()
            val programs = HashMap<String, List<Program>>(keys * 2)
            repeat(keys) {
                val key = str(inp)
                val n = inp.readInt()
                val list = ArrayList<Program>(n)
                repeat(n) {
                    val start = inp.readLong()
                    val stop = start + inp.readInt() * 1000L
                    list += Program(start, stop, str(inp), str(inp))
                }
                programs[key] = list
            }
            val chans = inp.readInt()
            val channels = ArrayList<GuideChannel>(chans)
            repeat(chans) { channels += GuideChannel(str(inp), str(inp), str(inp)) }
            GuideCache(savedAt, programs, matched, total, channels)
        }

    // ---- helpers ----

    /** Length-prefixed UTF-8 (writeUTF is limited to 64 KB and uses modified UTF-8). */
    private fun str(out: DataOutputStream, s: String) {
        if (s.isEmpty()) {
            out.writeInt(0)
            return
        }
        val b = s.toByteArray(Charsets.UTF_8)
        out.writeInt(b.size)
        out.write(b)
    }

    private fun str(inp: DataInputStream): String {
        val n = inp.readInt()
        if (n == 0) return ""
        if (n < 0 || n > 8_000_000) throw IOException("Corrupt cache")
        val b = ByteArray(n)
        inp.readFully(b)
        return String(b, Charsets.UTF_8)
    }

    private class Pool {
        val list = ArrayList<String>()
        private val ids = HashMap<String, Int>()
        fun add(s: String) {
            if (s !in ids) {
                ids[s] = list.size
                list += s
            }
        }
        fun idOf(s: String): Int = ids[s] ?: 0
    }
}
