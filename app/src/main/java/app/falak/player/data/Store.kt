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
    /** 2: the guide also records which channels it was matched for ([GuideCache.scope]). */
    private const val GUIDE_VERSION = 2

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

    /**
     * Only the groups of a saved playlist and how many items each has, per kind, in playlist
     * order. Streams through the file without building the entries, so it is cheap even for
     * very big playlists (used by the phone page that shows and hides groups).
     */
    fun readGroups(file: File): Map<Kind, LinkedHashMap<String, Int>> =
        DataInputStream(BufferedInputStream(file.inputStream(), 256 * 1024)).use { inp ->
            if (inp.readInt() != PLAYLIST_MAGIC || inp.readInt() != VERSION) throw IOException("Old cache format")
            inp.readLong()
            skipStr(inp) // guide link
            skipStr(inp) // server time zone
            val table = Array(inp.readInt()) { str(inp) }
            val kinds = Kind.entries
            val out = kinds.associateWith { LinkedHashMap<String, Int>() }
            repeat(inp.readInt()) {
                skipStr(inp) // id
                skipStr(inp) // name
                val group = table[inp.readInt()]
                skipStr(inp) // url
                skipStr(inp) // logo
                skipStr(inp) // tvg-id
                val kind = kinds[inp.readByte().toInt()]
                skip(inp, 8) // user agent, referrer
                skipStr(inp) // series id
                skipStr(inp) // info
                skip(inp, 8) // added
                skipStr(inp) // rating
                skip(inp, 2 + 4) // catch-up days, type
                skipStr(inp) // catch-up source
                val m = out.getValue(kind)
                m[group] = (m[group] ?: 0) + 1
            }
            out
        }

    // ---- guide ----

    fun writeGuide(file: File, cache: GuideCache) {
        val tmp = File(file.path + ".tmp")
        DataOutputStream(BufferedOutputStream(tmp.outputStream(), 256 * 1024)).use { out ->
            out.writeInt(GUIDE_MAGIC)
            out.writeInt(GUIDE_VERSION)
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
            out.writeInt(cache.scope.size)
            for (k in cache.scope) str(out, k)
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Can't save the guide cache")
        }
    }

    /**
     * Reads the saved guide. With [only], programmes of other channels (hidden groups) are
     * skipped without being loaded, so they cost no memory.
     */
    fun readGuide(file: File, only: Set<String>? = null): GuideCache =
        DataInputStream(BufferedInputStream(file.inputStream(), 256 * 1024)).use { inp ->
            if (inp.readInt() != GUIDE_MAGIC || inp.readInt() != GUIDE_VERSION) throw IOException("Old guide format")
            val savedAt = inp.readLong()
            inp.readInt() // matched: worked out again below, for the channels actually read
            val total = inp.readInt()
            val keys = inp.readInt()
            val programs = HashMap<String, List<Program>>(keys * 2)
            repeat(keys) {
                val key = str(inp)
                val n = inp.readInt()
                if (only != null && key !in only) {
                    repeat(n) {
                        skip(inp, 12)
                        skipStr(inp)
                        skipStr(inp)
                    }
                    return@repeat
                }
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
            val scopeSize = inp.readInt()
            val scope = HashSet<String>(scopeSize * 2)
            repeat(scopeSize) { scope += str(inp) }
            GuideCache(savedAt, programs, programs.count { it.value.isNotEmpty() }, if (only != null) only.size else total, channels, scope)
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

    private fun skip(inp: DataInputStream, n: Int) {
        var left = n
        while (left > 0) {
            val k = inp.skipBytes(left)
            if (k <= 0) {
                inp.readByte() // at least one byte, or EOFException on a cut-off file
                left--
            } else {
                left -= k
            }
        }
    }

    private fun skipStr(inp: DataInputStream) {
        val n = inp.readInt()
        if (n < 0 || n > 8_000_000) throw IOException("Corrupt cache")
        skip(inp, n)
    }

    /** One reusable read buffer per thread: a big playlist has over a million strings to read. */
    private val buffers = ThreadLocal<ByteArray>()

    private fun str(inp: DataInputStream): String {
        val n = inp.readInt()
        if (n == 0) return ""
        if (n < 0 || n > 8_000_000) throw IOException("Corrupt cache")
        var b = buffers.get()
        if (b == null || b.size < n) {
            b = ByteArray(maxOf(n, 1024))
            if (n <= 64 * 1024) buffers.set(b) // don't keep a rare huge one
        }
        inp.readFully(b, 0, n)
        return String(b, 0, n, Charsets.UTF_8)
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
