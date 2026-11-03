package app.falak.player.record

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class RingTest {
    private fun bytes(n: Int, from: Int = 0) = ByteArray(n) { ((it + from) % 251).toByte() }

    @Test
    fun keepsWhatArrivesAcrossFilesAndReadsItBack() {
        val dir = Files.createTempDirectory("ring").toFile()
        val ring = Ring(dir, chunkBytes = 1_000)
        val all = bytes(3_500)
        ring.append(all, 0, 1_700)
        ring.append(all, 1_700, 1_800)
        assertEquals(3_500L, ring.end)
        assertEquals(4, dir.listFiles()!!.size)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(700)
        var pos = 0L
        while (pos < ring.end) {
            val n = ring.read(pos, buf, 0, buf.size, 100)
            assertTrue(n > 0)
            out.write(buf, 0, n)
            pos += n
        }
        assertArrayEquals(all, out.toByteArray())
        // Nothing more yet: waits, then says so.
        assertEquals(0, ring.read(pos, buf, 0, buf.size, 50))
        ring.close()
        assertEquals(-1, ring.read(pos, buf, 0, buf.size, 50))
        ring.delete()
        assertTrue(!dir.exists())
    }

    @Test
    fun startingPointsByTimeAndDroppingTheOldest() {
        val dir = Files.createTempDirectory("ring").toFile()
        val ring = Ring(dir, chunkBytes = 1_000)
        val t0 = 1_000_000L
        for (i in 0 until 10) {
            ring.mark(t0 + i * 1_000L, ring.end)
            ring.append(bytes(500, i * 500), 0, 500)
        }
        assertEquals(t0, ring.oldestTime())
        assertEquals(t0 + 9_000, ring.newestTime())
        assertEquals(1_500L, ring.offsetAt(t0 + 3_500))
        assertEquals(0L, ring.offsetAt(0))
        assertEquals(t0 + 3_000, ring.timeAt(1_700))
        assertEquals(4_000L, ring.nearLive(1_000))
        // Older than 4 s goes, in whole files (1,000 bytes = 2 seconds each here).
        ring.trim(keepMs = 4_000, maxBytes = Long.MAX_VALUE, now = t0 + 9_500)
        assertTrue(ring.start > 0)
        assertEquals(0L, ring.start % 1_000)
        assertTrue(ring.oldestTime()!! >= t0 + 4_000)
        try {
            ring.read(0, ByteArray(10), 0, 10, 0)
            fail("A dropped part can't be read")
        } catch (e: Ring.Trimmed) {
            // as it should
        }
        // Too big: down to the limit.
        ring.trim(keepMs = Long.MAX_VALUE, maxBytes = 2_000, now = t0 + 9_500)
        assertTrue(ring.end - ring.start <= 2_000)
        ring.delete()
    }

    @Test
    fun copiesFromAPointUpToNow() {
        val dir = Files.createTempDirectory("ring").toFile()
        val ring = Ring(dir, chunkBytes = 700)
        val all = bytes(2_000)
        ring.append(all, 0, all.size)
        val out = ByteArrayOutputStream()
        assertEquals(2_000L, ring.copyTo(500, out))
        assertArrayEquals(all.copyOfRange(500, 2_000), out.toByteArray())
        ring.delete()
    }

    /** MPEG-TS packets; the ones in [keys] flagged as key frames. */
    private fun ts(count: Int, keys: Set<Int>): ByteArray {
        val out = ByteArray(count * 188)
        for (p in 0 until count) {
            val i = p * 188
            out[i] = 0x47
            out[i + 1] = 0x01
            out[i + 2] = 0x00
            if (p in keys) {
                out[i + 3] = 0x30 // adaptation field and payload
                out[i + 4] = 7
                out[i + 5] = 0x40 // random access
            } else {
                out[i + 3] = 0x10
                out[i + 4] = 0x55
                out[i + 5] = 0x40 // looks like a flag, but there's no adaptation field
            }
            for (k in i + 6 until i + 188) out[k] = (k % 7).toByte()
        }
        return out
    }

    @Test
    fun findsKeyFramesInPiecesOfAnyLength() {
        val data = ts(40, setOf(3, 17, 31))
        val scan = TsScan()
        val seen = HashSet<Long>()
        var pos = 0
        val pieces = intArrayOf(5, 100, 377, 1, 900, 2_000, 13)
        var k = 0
        while (pos < data.size) {
            val n = minOf(pieces[k++ % pieces.size], data.size - pos)
            scan.feed(data, pos, n, pos.toLong())
            if (scan.lastKey >= 0) seen += scan.lastKey
            pos += n
        }
        assertTrue(seen.containsAll(listOf(17L * 188, 31L * 188)))
        assertEquals(31L * 188, scan.lastKey)
        assertEquals(39L * 188, scan.lastPacket)
    }

    @Test
    fun findsThePacketsAfterJunkAtTheStart() {
        val junk = ByteArray(77) { 0x47 }
        val data = junk + ts(10, setOf(5))
        val scan = TsScan()
        scan.feed(data, 0, data.size, 1_000)
        assertEquals(1_000L + 77 + 5 * 188, scan.lastKey)
        assertEquals(1_000L + 77 + 9 * 188, scan.lastPacket)
    }
}
