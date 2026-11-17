package app.falak.player.data

import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger

/** MAC portals (8.33): the pieces, then a whole portal on this machine played through the link server. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class PortalTest {
    @Test
    fun readsMacAddresses() {
        assertEquals("00:1A:79:12:34:56", Portal.normalizeMac("00:1a:79:12:34:56"))
        assertEquals("00:1A:79:12:34:56", Portal.normalizeMac("001A79123456"))
        assertEquals("00:1A:79:12:34:56", Portal.normalizeMac(" 00-1A-79-12-34-56 "))
        assertNull(Portal.normalizeMac("00:1A:79:12:34"))
        assertNull(Portal.normalizeMac("00:1A:79:12:34:5G"))
    }

    @Test
    fun findsWhereTheApiIs() {
        val a = Portal.endpoints("http://host.tv:8080/c/")
        assertEquals("http://host.tv:8080/portal.php", a.first())
        assertTrue("http://host.tv:8080/stalker_portal/server/load.php" in a)
        val b = Portal.endpoints("host.tv/stalker_portal/c/")
        assertEquals("http://host.tv/stalker_portal/server/load.php", b.first())
        assertEquals("http://h:1/portal.php", Portal.endpoints("http://h:1/portal.php").first())
        assertEquals("http://host.tv/stalker_portal/c/", Portal.refererFor("http://host.tv/stalker_portal/server/load.php"))
        assertEquals("http://host.tv:8080/c/", Portal.refererFor("http://host.tv:8080/portal.php"))
        assertTrue(Portal.endpoints("").isEmpty())
    }

    @Test
    fun readsPlayCommandsAndDates() {
        assertEquals("http://localhost/ch/123_", Portal.cmdUrl("ffmpeg http://localhost/ch/123_"))
        assertEquals("http://a.b/x.m3u8?t=1", Portal.cmdUrl("auto  http://a.b/x.m3u8?t=1"))
        assertEquals("", Portal.cmdUrl("ffmpeg "))
        assertEquals("m3u8", Portal.extOf("ffmpeg http://a.b/live/x.m3u8"))
        assertEquals("mkv", Portal.extOf("ffmpeg http://a.b/media/film.mkv"))
        assertEquals("ts", Portal.extOf("ffmpeg http://localhost/ch/123_"))
        assertTrue(!Portal.direct("http://localhost/ch/1"))
        assertTrue(Portal.direct("http://real.host/ch/1"))
        assertEquals("http://p:8/logo.png", Portal.picture("/logo.png", "http://p:8"))
        assertEquals("", Portal.picture("logo.png", "http://p:8"))
        assertEquals(1_700_000_000L, Portal.addedOf("2023-11-14 22:13:20"))
        assertNull(Portal.endDate("Unlimited"))
        assertTrue((Portal.endDate("2026-12-31 23:59:59") ?: 0) > 0)
        assertTrue((Portal.endDate("December 31, 2026, 11:59 pm") ?: 0) > 0)
    }

    // ---- a portal on this machine ----

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val handshakes = AtomicInteger()
    private val links = AtomicInteger()
    @Volatile private var forgetOnce = false
    @Volatile private var sawMac = ""

    @After
    fun stop() = server.stop(0)

    private fun start(): String {
        val port = server.address.port
        server.createContext("/stalker_portal/server/load.php") { ex -> api(ex, port) }
        server.createContext("/stream/") { ex ->
            val b = "PLAYING ${ex.requestURI.path}".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.start()
        return "http://127.0.0.1:$port/stalker_portal/c/"
    }

    private fun api(ex: HttpExchange, port: Int) {
        val q = ex.requestURI.rawQuery.orEmpty().split('&').associate {
            val kv = it.split('=', limit = 2)
            kv[0] to URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
        }
        sawMac = ex.requestHeaders.getFirst("Cookie").orEmpty()
        val auth = ex.requestHeaders.getFirst("Authorization").orEmpty()
        val action = q["action"]
        val body = when {
            action == "handshake" -> {
                handshakes.incrementAndGet()
                """{"js":{"token":"TOKEN${handshakes.get()}"}}"""
            }
            auth != "Bearer TOKEN${handshakes.get()}" -> "Authorization failed."
            action == "get_profile" -> """{"js":{"id":"1","status":0}}"""
            q["type"] == "itv" && action == "get_genres" -> """{"js":[{"id":"*","title":"All"},{"id":"7","title":"News"}]}"""
            q["type"] == "itv" && action == "get_all_channels" -> {
                if (forgetOnce) {
                    forgetOnce = false
                    handshakes.incrementAndGet() // the portal forgot the session
                    "Authorization failed."
                } else {
                    """{"js":{"total_items":2,"max_page_items":2,"data":[
                    {"id":"11","name":"Atlas News","cmd":"ffmpeg http://localhost/ch/11_","tv_genre_id":"7","logo":"/logos/11.png","xmltv_id":"atlas.uk"},
                    {"id":"12","name":"Meridian","cmd":"ffmpeg http://localhost/ch/12_","tv_genre_id":"9","logo":""}]}}"""
                }
            }
            q["type"] == "vod" && action == "get_categories" -> """{"js":[{"id":"*","title":"All"},{"id":"3","title":"Drama"}]}"""
            q["type"] == "vod" && action == "get_ordered_list" && q["movie_id"] != null -> """{"js":{"total_items":0,"max_page_items":14,"data":[]}}"""
            q["type"] == "vod" && action == "get_ordered_list" -> when (q["p"]) {
                "1" -> """{"js":{"total_items":3,"max_page_items":2,"data":[
                    {"id":"501","name":"The Long Harbour","cmd":"ZmlsbQ==","description":"A harbour town.","year":"2023","screenshot_uri":"http://img/p.jpg","rating_imdb":"7.8","added":"2023-11-14 22:13:20"},
                    {"id":"502","name":"Paper Kites","cmd":"ZmlsbTI=","time":"95"}]}}"""
                else -> """{"js":{"total_items":3,"max_page_items":2,"data":[
                    {"id":"601","name":"Harbour Lights","cmd":"c2VyaWVz","is_series":"1","series":[1,2]}]}}"""
            }
            q["type"] == "series" -> """{"js":[]}"""
            action == "create_link" -> {
                links.incrementAndGet()
                val what = if (q["type"] == "itv") q["cmd"].orEmpty().filter { it.isDigit() } else q["cmd"] + "-" + q["series"]
                """{"js":{"id":"1","cmd":"ffmpeg http://127.0.0.1:$port/stream/$what.ts?play_token=x"}}"""
            }
            action == "get_main_info" -> """{"js":{"mac":"00:1A:79:00:00:01","phone":"December 31, 2026, 11:59 pm"}}"""
            else -> """{"js":null}"""
        }
        val b = body.toByteArray()
        ex.sendResponseHeaders(200, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }

    @Test
    fun playsAPortalThroughTheLinkServer() {
        val portalUrl = start()
        val http = OkHttpClient()
        val api = PortalApi(http, portalUrl, "001a79000001")
        api.check()
        assertTrue(sawMac.contains("mac=00%3A1A%3A79%3A00%3A00%3A01"))

        PortalLinks.ensure(ApplicationProvider.getApplicationContext())
        PortalLinks.resolve = { _, module, series, cmd -> api.playLink(module, cmd, series) }
        forgetOnce = true // the session is started again by itself
        val (list, extras) = api.loadAll { module, series, cmd, ext -> PortalLinks.link("pl1", module, series, cmd, ext) }

        val live = list.filter { it.kind == Kind.LIVE }
        assertEquals(listOf("Atlas News", "Meridian"), live.map { it.name })
        assertEquals("News", live[0].group)
        assertEquals("Uncategorized", live[1].group)
        assertEquals("atlas.uk", live[0].tvgId)
        assertTrue(live[0].logo.endsWith("/logos/11.png"))
        val films = list.filter { it.kind == Kind.MOVIE }
        assertEquals(listOf("The Long Harbour", "Paper Kites"), films.map { it.name })
        assertEquals("Drama", films[0].group)
        assertEquals(1_700_000_000L, films[0].added)
        assertEquals("A harbour town.", extras["m501"]?.plot)
        assertEquals("95 min", extras["m502"]?.duration)
        val series = list.single { it.kind == Kind.SERIES }
        assertEquals("v601", series.seriesId)
        assertTrue(series.isSeriesFolder)

        // A channel plays: the link server asks the portal and sends the player on.
        val played = http.newCall(Request.Builder().url(live[0].url).build()).execute().use { it.body!!.string() }
        assertEquals("PLAYING /stream/11.ts", played)
        val film = http.newCall(Request.Builder().url(films[1].url).build()).execute().use { it.body!!.string() }
        assertEquals("PLAYING /stream/ZmlsbTI=-.ts", film)

        // The series kept in the films list: one season of its episode numbers.
        val info = api.seriesInfo("v601", extras["sv601"]) { module, n, cmd, ext -> PortalLinks.link("pl1", module, n, cmd, ext) }
        assertEquals(1, info.seasons.size)
        assertEquals(listOf("Episode 1", "Episode 2"), info.seasons[0].episodes.map { it.name })
        val ep = http.newCall(Request.Builder().url(info.seasons[0].episodes[1].url).build()).execute().use { it.body!!.string() }
        assertEquals("PLAYING /stream/c2VyaWVz-2.ts", ep)

        // Asked twice in a row (players do): the portal is asked once.
        val before = links.get()
        http.newCall(Request.Builder().url(live[0].url).build()).execute().close()
        assertEquals(before, links.get())

        // A link saved with an old port is mended; someone else's link is left alone.
        val old = "http://127.0.0.1:1/" + PortalLinks.base.substringAfterLast('/') + "/pl1/itv/0/abc.ts"
        assertTrue(PortalLinks.fix(old).startsWith(PortalLinks.base))
        assertEquals("http://other/x.ts", PortalLinks.fix("http://other/x.ts"))
        // A wrong token gets nothing.
        val wrong = PortalLinks.base.substringBeforeLast('/') + "/nope/pl1/itv/0/abc.ts"
        assertEquals(404, http.newCall(Request.Builder().url(wrong).build()).execute().use { it.code })

        assertTrue((api.account().expires ?: 0) > 0)
    }

    @Test
    fun saysPlainlyWhenTheMacIsRefused() {
        server.createContext("/portal.php") { ex ->
            val b = (if (ex.requestURI.rawQuery.orEmpty().contains("handshake")) """{"js":{"token":"T"}}""" else "Authorization failed.").toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.start()
        val api = PortalApi(OkHttpClient(), "http://127.0.0.1:${server.address.port}/c/", "00:1A:79:00:00:02")
        val e = runCatching { api.check() }.exceptionOrNull()
        assertEquals(Portal.REFUSED, e?.message)
    }
}
