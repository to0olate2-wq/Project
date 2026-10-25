package app.falak.player.data

import androidx.test.core.app.ApplicationProvider
import app.falak.player.FalakApp
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Big playlists on busy providers: a list that fails part-way is read again (and only once in the
 * result), a refusal isn't, and the loading screen hears how far it got.
 */
@RunWith(RobolectricTestRunner::class)
class BigPlaylistTest {

    @Before
    fun quick() {
        Downloads.pauseMs = 5
    }

    @Test
    fun retriesPassingProblemsOnly() {
        val tries = mutableListOf<Int>()
        var calls = 0
        val got = Downloads.withRetries(onRetry = { tries += it }) {
            calls++
            if (calls < 3) throw SocketTimeoutException("timeout")
            "ok"
        }
        assertEquals("ok", got)
        assertEquals(listOf(2, 3), tries)

        // Never more than three tries.
        calls = 0
        try {
            Downloads.withRetries { calls++; throw ProtocolException("unexpected end of stream") }
            fail("should give up")
        } catch (e: IOException) {
            assertEquals(Downloads.TRIES, calls)
        }

        // A refusal isn't tried again; a server error is.
        calls = 0
        try {
            Downloads.withRetries { calls++; throw HttpStatusException(404) }
        } catch (e: HttpStatusException) {
            assertEquals(1, calls)
        }
        calls = 0
        try {
            Downloads.withRetries { calls++; throw HttpStatusException(503) }
        } catch (e: HttpStatusException) {
            assertEquals(3, calls)
        }
    }

    @Test
    fun plainWordsForNetworkFailures() {
        assertTrue(Downloads.explain(SocketTimeoutException("timeout")).message!!.contains("took too long"))
        assertTrue(Downloads.explain(ProtocolException("unexpected end of stream")).message!!.contains("dropped part-way"))
        assertTrue(Downloads.explain(ConnectException("failed")).message!!.contains("didn't answer"))
        // The app's own messages stay as they are.
        assertEquals("Login failed: check username and password", Downloads.explain(IOException("Login failed: check username and password")).message)
    }

    /** One request per connection; [answer] gets the action (or path) and how many times it was asked. */
    private class FakeServer(val answer: (String, Int) -> String) : AutoCloseable {
        val asked = ConcurrentHashMap<String, AtomicInteger>()
        private val socket = ServerSocket(0)
        val base = "http://127.0.0.1:${socket.localPort}"

        init {
            Thread {
                while (!socket.isClosed) {
                    val c = try { socket.accept() } catch (e: IOException) { break }
                    c.use {
                        val reader = it.getInputStream().bufferedReader()
                        val line = reader.readLine() ?: return@use
                        // Read the whole request (no body): hanging up on unread bytes would reset the connection.
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        val target = line.split(' ')[1]
                        val key = Regex("action=([a-z_]+)").find(target)?.groupValues?.get(1) ?: target.substringBefore('?')
                        val n = asked.getOrPut(key) { AtomicInteger() }.incrementAndGet()
                        it.getOutputStream().write(answer(key, n).toByteArray())
                        it.getOutputStream().flush()
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        override fun close() = socket.close()

        companion object {
            fun ok(body: String) = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"

            /** Says more is coming than it sends, then hangs up: the connection dropped part-way. */
            fun cut(body: String) = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size + 5000}\r\nConnection: close\r\n\r\n$body"

            fun status(code: Int) = "HTTP/1.1 $code Nope\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        }
    }

    @Test
    fun xtreamListFailingPartWayIsReadAgainFromTheStart() {
        val live = (1..3000).joinToString(",", "[", "]") { """{"stream_id":"$it","name":"Channel $it","category_id":"1"}""" }
        FakeServer { action, n ->
            when (action) {
                "get_live_categories" -> FakeServer.ok("""[{"category_id":"1","category_name":"News"}]""")
                // The first try drops after 1,500 channels; the second brings them all (one renamed).
                "get_live_streams" -> if (n == 1) FakeServer.cut(live.substring(0, live.indexOf("""{"stream_id":"1501"""))) else FakeServer.ok(live.replace("Channel 1\"", "Channel 1 (new)\""))
                // Films refused: not tried again, and live TV still loads.
                "get_vod_categories", "get_vod_streams" -> FakeServer.status(404)
                else -> FakeServer.ok("[]")
            }
        }.use { server ->
            val steps = mutableListOf<LoadProgress>()
            val list = XtreamApi(OkHttpClient(), server.base, "u", "p", "").loadAll(progress = { steps += it })

            assertEquals(3000, list.size)
            assertEquals(3000, list.map { it.id }.toSet().size)
            // The second try's copy, not the first's: what a failed try added was dropped.
            assertEquals("Channel 1 (new)", list.first { it.id == "l1" }.name)
            assertEquals("News", list.first().group)
            assertEquals(2, server.asked["get_live_streams"]!!.get())
            assertEquals(1, server.asked["get_vod_streams"]!!.get())
            assertTrue("told the retry", steps.any { it.retry == 2 })
            assertTrue("counted", steps.any { it.count == 3000 } || steps.any { it.count == 2000 })
        }
    }

    @Test
    fun m3uLinkFailingPartWayIsDownloadedAgain() {
        val m3u = buildString {
            append("#EXTM3U\n")
            repeat(5000) { append("#EXTINF:-1 group-title=\"G${it % 9}\",Channel $it\nhttp://example.com/live/$it.ts\n") }
        }
        FakeServer { _, n -> if (n == 1) FakeServer.cut(m3u.substring(0, m3u.length / 2)) else FakeServer.ok(m3u) }.use { server ->
            val repo = ApplicationProvider.getApplicationContext<FalakApp>().repo
            val p = Playlist("big", "Big", SourceType.M3U_URL, "${server.base}/list.m3u", userAgent = "Test")
            val steps = mutableListOf<LoadProgress>()
            val loaded = runBlocking { repo.loadChannels(p, refresh = true) { steps += it } }

            assertEquals(5000, loaded.channels.size)
            assertEquals(2, server.asked["/list.m3u"]!!.get())
            assertTrue(steps.any { it.retry == 2 })
            assertTrue(steps.any { it.count == 5000 })
            // Saved for next time.
            assertEquals(5000, runBlocking { repo.cachedChannels(p) }!!.channels.size)
        }
    }

    @Test
    fun aDeadServerSaysSoInPlainWords() {
        val port = ServerSocket(0).use { it.localPort } // nothing listens there now
        val repo = ApplicationProvider.getApplicationContext<FalakApp>().repo
        val p = Playlist("dead", "Dead", SourceType.M3U_URL, "http://127.0.0.1:$port/list.m3u", userAgent = "Test")
        try {
            runBlocking { repo.loadChannels(p, refresh = true) }
            fail("should fail")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("didn't answer"))
        }
    }
}
