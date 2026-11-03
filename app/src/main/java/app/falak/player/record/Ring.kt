package app.falak.player.record

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * A live stream kept on disk as it arrives, in files of a few MB, so it can be read again from
 * any point still kept: the player reads it a little behind (pause, rewind) and the oldest part is
 * dropped as new arrives. Positions are counted from the stream's first byte and never reused.
 * Each [mark] remembers where the picture can start well ("this position was live at that time").
 */
internal class Ring(private val dir: File, private val chunkBytes: Long = 4L shl 20) {
    class Trimmed : IOException("That part of the buffer is gone")

    private class Chunk(val start: Long, val file: File) {
        var length = 0L
    }

    private val lock = Object()
    private val chunks = ArrayDeque<Chunk>()
    private var writer: RandomAccessFile? = null
    private val times = ArrayList<Long>()
    private val offsets = ArrayList<Long>()
    private var serial = 0

    /** The oldest position still kept, and where the next byte will go. */
    @Volatile var start = 0L
        private set
    @Volatile var end = 0L
        private set

    /** No more is coming (the stream stopped for good). */
    @Volatile var closed = false
        private set

    init {
        dir.mkdirs()
    }

    fun append(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        synchronized(lock) {
            if (closed) return
            var o = off
            var left = len
            while (left > 0) {
                val cur = chunks.lastOrNull()?.takeIf { it.length < chunkBytes && writer != null } ?: newChunk()
                val n = minOf(left.toLong(), chunkBytes - cur.length).toInt()
                writer!!.write(b, o, n)
                cur.length += n
                o += n
                left -= n
                end += n
            }
            lock.notifyAll()
        }
    }

    private fun newChunk(): Chunk {
        writer?.let { runCatching { it.close() } }
        val c = Chunk(end, File(dir, "c" + (serial++) + ".bin"))
        writer = RandomAccessFile(c.file, "rw").apply { setLength(0) }
        chunks.addLast(c)
        return c
    }

    /** [offset] was live at [time] and the picture can start there. */
    fun mark(time: Long, offset: Long) {
        synchronized(lock) {
            if (offset < start || offset > end) return
            if (offsets.isNotEmpty() && offset <= offsets.last()) return
            times += maxOf(time, times.lastOrNull() ?: time)
            offsets += offset
        }
    }

    /**
     * Reads from [pos] into [buf]: the number of bytes read, 0 when nothing came within [waitMs],
     * or -1 at the end of a stream that has stopped. Throws [Trimmed] for a position already dropped.
     */
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int, waitMs: Long): Int {
        val chunk: Chunk
        val avail: Long
        synchronized(lock) {
            if (pos < start) throw Trimmed()
            if (pos >= end) {
                if (closed) return -1
                if (waitMs > 0) lock.wait(waitMs)
                if (pos < start) throw Trimmed()
                if (pos >= end) return if (closed) -1 else 0
            }
            chunk = chunks.lastOrNull { it.start <= pos } ?: throw Trimmed()
            avail = minOf(chunk.start + chunk.length, end) - pos
        }
        val n = minOf(len.toLong(), avail).toInt()
        if (n <= 0) return 0
        RandomAccessFile(chunk.file, "r").use { f ->
            f.seek(pos - chunk.start)
            f.readFully(buf, off, n)
        }
        return n
    }

    /** The newest good starting point at or before [time] (the oldest one if [time] is older). */
    fun offsetAt(time: Long): Long = synchronized(lock) {
        if (offsets.isEmpty()) return start
        val i = times.binarySearch(time).let { if (it >= 0) it else -it - 2 }.coerceIn(0, offsets.lastIndex)
        offsets[i]
    }

    /** When [offset] was live (the good starting point at or before it), or null before any. */
    fun timeAt(offset: Long): Long? = synchronized(lock) {
        val i = offsets.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
        if (i < 0) null else times[i]
    }

    /** The oldest time still kept, or null before any. */
    fun oldestTime(): Long? = synchronized(lock) { times.firstOrNull() }

    /** The newest time marked, or null before any. */
    fun newestTime(): Long? = synchronized(lock) { times.lastOrNull() }

    /** A good starting point about [backMs] behind live (the newest one when there's no older). */
    fun nearLive(backMs: Long): Long = synchronized(lock) {
        val last = times.lastOrNull() ?: return end
        offsetAt(last - backMs)
    }

    /** Copies what's kept from [from] up to now into [out] (a recording that starts in the past). */
    fun copyTo(from: Long, out: java.io.OutputStream): Long {
        var pos = maxOf(from, start)
        val buf = ByteArray(256 * 1024)
        val stopAt = end
        while (pos < stopAt) {
            val n = try {
                read(pos, buf, 0, minOf(buf.size.toLong(), stopAt - pos).toInt(), 0)
            } catch (e: Trimmed) {
                pos = start
                continue
            }
            if (n <= 0) break
            out.write(buf, 0, n)
            pos += n
        }
        return pos
    }

    /** Drops what's older than [keepMs] and anything over [maxBytes] (whole files, oldest first). */
    fun trim(keepMs: Long, maxBytes: Long, now: Long) {
        synchronized(lock) {
            while (chunks.size > 1) {
                val first = chunks.first()
                val firstEnd = first.start + first.length
                // A file goes once everything in it is older than the limit, or the buffer is too big.
                val newestInIt = timeAt(firstEnd - 1)
                val tooOld = newestInIt != null && now - newestInIt > keepMs
                val tooBig = end - first.start > maxBytes
                if (!tooOld && !tooBig) break
                chunks.removeFirst()
                first.file.delete()
                start = chunks.first().start
            }
            // Starting points in what was dropped are forgotten (one before the new start stays useless).
            var drop = 0
            while (drop < offsets.size && offsets[drop] < start) drop++
            if (drop > 0) {
                times.subList(0, drop).clear()
                offsets.subList(0, drop).clear()
            }
            // Always somewhere to start: the oldest byte kept.
            if (offsets.isEmpty() && end > start) {
                offsets += start
                times += now
            }
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            writer?.let { runCatching { it.close() } }
            writer = null
            lock.notifyAll()
        }
    }

    /** Closes it and deletes its files. */
    fun delete() {
        close()
        synchronized(lock) {
            chunks.forEach { it.file.delete() }
            chunks.clear()
        }
        dir.deleteRecursively()
    }
}

/**
 * Finds MPEG-TS packets in a stream fed in pieces: where they start, and the ones a decoder can
 * start from (a key frame, flagged "random access" in the packet's header).
 */
internal class TsScan {
    private var pending = ByteArray(0)
    private var synced = false

    /** The last packet start seen, and the last key-frame packet seen (stream positions; −1 = none yet). */
    var lastPacket = -1L
        private set
    var lastKey = -1L
        private set

    /** The stream starts again (a new connection): find the packets afresh. */
    fun reset() {
        pending = ByteArray(0)
        synced = false
    }

    /** [b] (from [off], [len] bytes) is the stream from position [at]. */
    fun feed(b: ByteArray, off: Int, len: Int, at: Long) {
        val buf: ByteArray
        val n: Int
        if (pending.isEmpty()) {
            buf = b
            n = off + len
        } else {
            buf = ByteArray(pending.size + len)
            System.arraycopy(pending, 0, buf, 0, pending.size)
            System.arraycopy(b, off, buf, pending.size, len)
            n = buf.size
        }
        val first = if (pending.isEmpty()) off else 0
        // The stream position of buf[0] (reading b directly, buf[off] is at [at]).
        val origin = if (pending.isEmpty()) at - off else at - pending.size
        var i = first
        while (true) {
            if (!synced) {
                // Three packet starts in a row: in step with the packets.
                var found = -1
                var j = i
                while (j + 2 * PACKET < n) {
                    if (buf[j] == SYNC && buf[j + PACKET] == SYNC && buf[j + 2 * PACKET] == SYNC) {
                        found = j
                        break
                    }
                    j++
                }
                if (found < 0) {
                    i = maxOf(i, n - 2 * PACKET)
                    break
                }
                i = found
                synced = true
            }
            if (i + PACKET > n) break
            if (buf[i] != SYNC) {
                synced = false
                i++
                continue
            }
            val pos = origin + i
            lastPacket = pos
            if (isKey(buf, i)) lastKey = pos
            i += PACKET
        }
        val keep = (n - i).coerceAtLeast(0)
        pending = if (keep == 0) ByteArray(0) else buf.copyOfRange(n - keep, n)
    }

    companion object {
        const val PACKET = 188
        private const val SYNC = 0x47.toByte()

        /** An adaptation field with its random-access flag: a key frame starts in this packet. */
        fun isKey(b: ByteArray, i: Int): Boolean {
            val control = (b[i + 3].toInt() shr 4) and 3
            if (control != 2 && control != 3) return false
            val length = b[i + 4].toInt() and 0xFF
            return length > 0 && (b[i + 5].toInt() and 0x40) != 0
        }
    }
}
