package app.falak.player.record

import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/** A channel from a pretend provider, through the buffer and the local server, comes back byte for byte. */
class CaptureTest {
    /** A pretend provider: each path answers with what its function writes. */
    private class Provider(private val routes: Map<String, (OutputStream) -> Unit>) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${server.localPort}"

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val s = try {
                        server.accept()
                    } catch (e: IOException) {
                        break
                    }
                    thread(isDaemon = true) { handle(s) }
                }
            }
        }

        private fun handle(s: Socket) {
            s.use { sock ->
                val input = sock.getInputStream().bufferedReader()
                val line = input.readLine() ?: return
                while (true) {
                    val h = input.readLine() ?: break
                    if (h.isEmpty()) break
                }
                val path = line.split(' ')[1]
                val out = sock.getOutputStream()
                val f = routes[path]
                try {
                    if (f == null) out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) else f(out)
                    out.flush()
                } catch (e: IOException) {
                    // the capture let go
                }
            }
        }

        override fun close() = server.close()
    }

    private fun ok(out: OutputStream, type: String, body: ByteArray) {
        out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
    }

    private fun ts(count: Int, seed: Int): ByteArray {
        val out = ByteArray(count * 188)
        for (p in 0 until count) {
            val i = p * 188
            out[i] = 0x47
            out[i + 1] = 0x01
            out[i + 3] = if (p % 10 == 0) 0x30 else 0x10
            out[i + 4] = 7
            out[i + 5] = 0x40
            for (k in i + 6 until i + 188) out[k] = ((k + seed) % 13).toByte()
        }
        return out
    }

    private fun waitFor(ms: Long = 10_000, what: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!what()) {
            if (System.currentTimeMillis() > until) throw AssertionError("Timed out")
            Thread.sleep(20)
        }
    }

    private fun readLocal(url: String, bytes: Int): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.readTimeout = 10_000
        val out = ByteArrayOutputStream()
        c.inputStream.use { input ->
            val buf = ByteArray(4096)
            while (out.size() < bytes) {
                val n = input.read(buf, 0, minOf(buf.size, bytes - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        c.disconnect()
        return out.toByteArray()
    }

    @Test
    fun liveStreamThroughTheBufferAndIntoARecording() {
        val stream = ts(300, 1)
        Provider(
            mapOf(
                "/live/u/p/1.ts" to { out ->
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".toByteArray())
                    // As a live channel sends: in bursts, then it stays open.
                    for (k in 0 until 3) {
                        out.write(stream, k * 100 * 188, 100 * 188)
                        out.flush()
                        Thread.sleep(150)
                    }
                    Thread.sleep(20_000)
                },
            ),
        ).use { provider ->
            val dir = Files.createTempDirectory("cap").toFile()
            val c = Capture("t1", provider.base + "/live/u/p/1.ts", "test", "", OkHttpClient(), File(dir, "ring"), failFast = true)
            c.start()
            waitFor { (c.ring?.end ?: 0L) >= stream.size.toLong() }
            assertEquals(Capture.State.RUNNING, c.state)
            // A good starting point was marked on a key-frame packet.
            val start = c.ring!!.offsetAt(Long.MAX_VALUE)
            assertEquals(0L, start % 188)
            // The player's address: the stream from the start of the buffer.
            val back = readLocal(LocalStream.urlFor(c, 0), stream.size)
            assertArrayEquals(stream, back)
            // From a later point, carrying on as the player would after a hiccup (Range).
            val conn = URL(LocalStream.urlFor(c, 188L * 50)).openConnection() as HttpURLConnection
            conn.setRequestProperty("Range", "bytes=${188 * 10}-")
            val part = ByteArray(188 * 20)
            conn.inputStream.use { input ->
                var got = 0
                while (got < part.size) {
                    val n = input.read(part, got, part.size - got)
                    if (n < 0) break
                    got += n
                }
            }
            conn.disconnect()
            assertArrayEquals(stream.copyOfRange(188 * 60, 188 * 80), part)
            // A recording that starts from a point in the past takes what the buffer has.
            val file = File(dir, "rec.ts")
            val sink = Sink(file)
            c.addSink(sink, from = 188L * 100)
            sink.flush()
            assertArrayEquals(stream.copyOfRange(188 * 100, stream.size), file.readBytes())
            c.removeSink(sink)
            c.stop()
            waitFor { c.done }
            c.ring?.delete()
            dir.deleteRecursively()
        }
    }

    @Test
    fun replayFromHlsPartsEncryptedOrNot() {
        val a = ts(20, 2)
        val b = ts(30, 3)
        val key = ByteArray(16) { (it * 7).toByte() }
        val iv = ByteArray(16) { it.toByte() }
        val encrypted = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(b)
        }
        val master = "#EXTM3U\n" +
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"x\",URI=\"/sep/a.m3u8\"\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=9000000,AUDIO=\"x\"\n/sep/v.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=2000000\nhi/media.m3u8\n"
        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:7\n" +
            "#EXTINF:2,\ns7.ts\n" +
            "#EXT-X-KEY:METHOD=AES-128,URI=\"/key.bin\",IV=0x000102030405060708090a0b0c0d0e0f\n" +
            "#EXTINF:2,\ns8.ts\n#EXT-X-ENDLIST\n"
        Provider(
            mapOf(
                "/replay/index.m3u8" to { out -> ok(out, "application/vnd.apple.mpegurl", master.toByteArray()) },
                "/replay/hi/media.m3u8" to { out -> ok(out, "application/vnd.apple.mpegurl", media.toByteArray()) },
                "/replay/hi/s7.ts" to { out -> ok(out, "video/mp2t", a) },
                "/replay/hi/s8.ts" to { out -> ok(out, "video/mp2t", encrypted) },
                "/key.bin" to { out -> ok(out, "application/octet-stream", key) },
            ),
        ).use { provider ->
            val dir = Files.createTempDirectory("cap").toFile()
            val c = Capture("t2", provider.base + "/replay/index.m3u8", "test", "", OkHttpClient(), null, finite = true)
            val file = File(dir, "replay.ts")
            val sink = Sink(file)
            c.addSink(sink)
            c.start()
            waitFor { c.done }
            assertEquals(Capture.State.ENDED, c.state)
            c.removeSink(sink)
            assertArrayEquals(a + b, file.readBytes())
            dir.deleteRecursively()
        }
    }

    @Test
    fun aRefusedChannelFailsAtOnceForThePlayer() {
        Provider(
            mapOf("/live/u/p/2.ts" to { out -> out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) }),
        ).use { provider ->
            val dir = Files.createTempDirectory("cap").toFile()
            val c = Capture("t3", provider.base + "/live/u/p/2.ts", "test", "", OkHttpClient(), File(dir, "ring"), failFast = true)
            c.start()
            waitFor { c.done }
            assertEquals(Capture.State.FAILED, c.state)
            assertTrue(c.problem.orEmpty().contains("403"))
            dir.deleteRecursively()
        }
    }

    @Test
    fun aRecordingStillBeingMadeIsReadAsItGrows() {
        val dir = Files.createTempDirectory("grow").toFile()
        val file = File(dir, "r.ts")
        val first = ts(30, 4)
        val second = ts(30, 5)
        file.writeBytes(first)
        val more = java.util.concurrent.atomic.AtomicBoolean(true)
        val url = LocalStream.urlForFile("g1", LocalStream.Growing(file) { more.get() })
        thread {
            Thread.sleep(400)
            file.appendBytes(second)
            Thread.sleep(200)
            more.set(false)
        }
        val back = readLocal(url, first.size + second.size + 10)
        assertArrayEquals(first + second, back)
        LocalStream.forgetFile("g1")
        dir.deleteRecursively()
    }
}
