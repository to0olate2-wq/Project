package app.falak.player.torrent

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The local server that feeds the player a film that is still arriving, and how torrent links are read. */
class TorrentServerTest {
    private val pieceSize = 100_000
    private val data = ByteArray(3_000_000) { (it * 31 + it / 7).toByte() }

    /** A film arriving piece by piece, the wanted piece first, one every 20 ms. */
    private inner class Arriving : GrowingFile {
        override val name = "Film.Name.2024.mkv"
        override val size = data.size.toLong()
        override val path: File = File.createTempFile("film", ".mkv").apply { deleteOnExit() }
        private val have = BooleanArray(data.size / pieceSize)

        @Volatile
        private var wanted = 0

        init {
            val raf = RandomAccessFile(path, "rw")
            Thread {
                while (have.any { !it }) {
                    val p = (wanted until have.size).firstOrNull { !have[it] } ?: have.indexOfFirst { !it }
                    raf.seek(p * pieceSize.toLong())
                    raf.write(data, p * pieceSize, pieceSize)
                    have[p] = true
                    Thread.sleep(20)
                }
                raf.close()
            }.apply { isDaemon = true }.start()
        }

        override fun readyFrom(offset: Long): Long {
            var p = (offset / pieceSize).toInt()
            if (p >= have.size || !have[p]) return 0
            var end = (p + 1) * pieceSize.toLong()
            while (p + 1 < have.size && have[p + 1]) {
                p++
                end = (p + 1) * pieceSize.toLong()
            }
            return end - offset
        }

        override fun want(offset: Long) {
            wanted = (offset / pieceSize).toInt()
        }
    }

    private fun get(url: String, range: String?): Pair<Int, ByteArray> {
        val c = URL(url).openConnection() as HttpURLConnection
        if (range != null) c.setRequestProperty("Range", range)
        val code = c.responseCode
        return code to if (code < 400) c.inputStream.use { it.readBytes() } else ByteArray(0)
    }

    @Test
    fun servesAnyPartWaitingForWhatHasNotArrived() {
        TorrentServer(Arriving(), waitMs = 10_000).use { server ->
            assertEquals(true, server.url.endsWith("/video.mkv"))
            // The end first, as players do for the index.
            val (c1, tail) = get(server.url, "bytes=2900000-")
            assertEquals(206, c1)
            assertArrayEquals(data.copyOfRange(2_900_000, data.size), tail)
            val (c2, mid) = get(server.url, "bytes=1234567-1334566")
            assertEquals(206, c2)
            assertArrayEquals(data.copyOfRange(1_234_567, 1_334_567), mid)
            val (c3, all) = get(server.url, null)
            assertEquals(200, c3)
            assertArrayEquals(data, all)
            val (c4, last) = get(server.url, "bytes=-500")
            assertEquals(206, c4)
            assertArrayEquals(data.copyOfRange(data.size - 500, data.size), last)
            assertEquals(416, get(server.url, "bytes=3000000-").first)
            // Only someone who was given the link can read it.
            assertEquals(404, get(server.url.replace(Regex("/[0-9a-f]{32}/"), "/guess/"), null).first)
        }
    }

    @Test
    fun readsHashesAndMagnets() {
        assertEquals("abcdef0123456789abcdef0123456789abcdef01", TorrentSource.cleanHash("ABCDEF0123456789ABCDEF0123456789ABCDEF01"))
        // The older way of writing a hash (32 letters).
        assertEquals("abdbe60123456789abcdefc123456789abcdef01", TorrentSource.cleanHash("VPN6MAJDIVTYTK6N57ASGRLHRGV433YB"))
        assertNull(TorrentSource.cleanHash("abc"))
        val m = TorrentSource.fromMagnet(
            "magnet:?xt=urn:btih:ABCDEF0123456789ABCDEF0123456789ABCDEF01&dn=x&tr=udp%3A%2F%2Ft.example%3A80%2Fannounce&tr=wss%3A%2F%2Fno",
        )!!
        assertEquals(listOf("udp://t.example:80/announce"), m.trackers)
        assertEquals("magnet:?xt=urn:btih:abcdef0123456789abcdef0123456789abcdef01&tr=udp%3A%2F%2Ft.example%3A80%2Fannounce", m.magnet())
        assertEquals(
            listOf("udp://a:1/announce", "http://b/announce"),
            TorrentSource.trackersFrom(listOf("tracker:udp://a:1/announce", "dht:abc", "tracker:http://b/announce")),
        )
        assertNull(TorrentSource.fromMagnet("https://example.com/film.mp4"))
    }
}
