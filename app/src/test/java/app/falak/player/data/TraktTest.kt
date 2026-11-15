package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trakt's answers as its API documents them (api.trakt.tv, version 2), read by Trakt. */
class TraktTest {
    @Test
    fun imagesBecomeLinks() {
        val j = kotlinx.serialization.json.Json
        assertEquals("https://media.trakt.tv/images/people/1/headshots/medium/a.jpg.webp", Trakt.image(j.parseToJsonElement("""["media.trakt.tv/images/people/1/headshots/medium/a.jpg.webp"]""")))
        assertEquals("https://x.org/p.jpg", Trakt.image(j.parseToJsonElement("""{"full":"http://x.org/p.jpg"}""")))
        assertEquals("", Trakt.image(j.parseToJsonElement("""[]""")))
        assertEquals("", Trakt.image(null))
    }

    @Test
    fun scoreAndWatchlist() {
        val s = Trakt.parseScore("""{"rating":8.68,"votes":63472,"distribution":{"10":1}}""")!!
        assertEquals(87, s.percent)
        assertNull(Trakt.parseScore("""{"rating":0,"votes":0}"""))
        val w = Trakt.parseWatchlist("""[
          {"rank":1,"id":1,"listed_at":"2026-01-01T00:00:00.000Z","type":"movie","movie":{"title":"Dune","year":2021,"ids":{"imdb":"tt1160419"}}},
          {"rank":2,"id":2,"type":"show","show":{"title":"Shogun","year":2024,"ids":{"imdb":"tt2788316"}}},
          {"rank":3,"id":3,"type":"episode","episode":{"season":1,"number":1}}
        ]""")
        assertEquals(listOf("movie" to "tt1160419", "series" to "tt2788316"), w.map { it.type to it.imdb })
    }

    @Test
    fun connectingWithACode() {
        val d = Trakt.parseDeviceCode("""{"device_code":"d9c1","user_code":"5055CC52","verification_url":"https://trakt.tv/activate","expires_in":600,"interval":5}""")!!
        assertEquals("5055CC52", d.userCode)
        assertEquals(5, d.interval)
        val t = Trakt.parseTokens("""{"access_token":"a1","token_type":"bearer","expires_in":7200,"refresh_token":"r1","scope":"public","created_at":1}""", 1_000_000L)!!
        assertEquals("r1", t.refresh)
        // Renewed a day early (a short one is renewed at once).
        assertTrue(t.until < 1_000_000L + 7_200_000L)
        assertNull(Trakt.parseTokens("""{"error":"invalid_grant"}""", 0))
        assertEquals("Ahmed", Trakt.parseUser("""{"user":{"username":"ahmed_k","name":"Ahmed"}}"""))
        assertEquals("ahmed_k", Trakt.parseUser("""{"user":{"username":"ahmed_k","name":""}}"""))
    }

    @Test
    fun whatIsSaidAsItPlays() {
        assertEquals("""{"movie":{"ids":{"imdb":"tt0133093"}},"progress":12.50}""", Trakt.scrobbleBody("tt0133093", 12.5))
        assertEquals("""{"show":{"ids":{"imdb":"tt0903747"}},"episode":{"season":1,"number":2},"progress":100.00}""", Trakt.scrobbleBody("tt0903747:1:2", 140.0))
        assertNull(Trakt.scrobbleBody("kitsu:1:2", 10.0))
        assertNull(Trakt.scrobbleBody("tt0903747:1", 10.0))
        assertEquals("""{"shows":[{"ids":{"imdb":"tt2788316"}}]}""", Trakt.watchlistBody("tt2788316", "series"))
        // Apps made since October 2026 have no secret: none is sent.
        assertEquals("""{"code":"d1","client_id":"c1"}""", Trakt.deviceTokenBody("d1", "c1"))
        assertEquals("""{"code":"d1","client_id":"c1","client_secret":"s1"}""", Trakt.deviceTokenBody("d1", "c1", "s1"))
        assertTrue(Trakt.refreshBody("r1", "c1").contains(""""redirect_uri":"http://localhost""""))
    }
}
