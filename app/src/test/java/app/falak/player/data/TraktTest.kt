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
    fun peopleAndThePersonMeant() {
        val text = """[
          {"type":"person","score":51.2,"person":{"name":"Keanu Reeves","ids":{"trakt":9486,"slug":"keanu-reeves","imdb":"nm0000206","tmdb":6384},
            "images":{"headshot":["media.trakt.tv/images/people/000/009/486/headshots/medium/k.jpg.webp"]}}},
          {"type":"person","score":12.0,"person":{"name":"Keanu Reeves Jr","ids":{"trakt":1,"slug":"keanu-reeves-jr","imdb":null,"tmdb":2}}},
          {"type":"person","score":1.0,"person":{"name":"","ids":{"slug":"nobody"}}}
        ]"""
        val found = Trakt.parsePeople(text)
        assertEquals(listOf("keanu-reeves", "keanu-reeves-jr"), found.map { it.slug })
        assertTrue(found[0].photo.startsWith("https://media.trakt.tv/"))
        assertEquals("keanu-reeves", Trakt.pickPerson(found, "KEANU reeves")?.slug)
        // A name not quite the same: Trakt's first answer.
        assertEquals("keanu-reeves", Trakt.pickPerson(found, "Keanu C. Reeves")?.slug)
        assertNull(Trakt.pickPerson(emptyList(), "x"))
        assertTrue(Trakt.parsePeople("not json").isEmpty())
    }

    @Test
    fun theirFilmsNewestFirst() {
        val text = """{"cast":[
          {"characters":["Neo"],"movie":{"title":"The Matrix","year":1999,"ids":{"trakt":481,"slug":"the-matrix-1999","imdb":"tt0133093","tmdb":603},
            "images":{"poster":["media.trakt.tv/images/movies/000/000/481/posters/medium/m.jpg.webp"]}}},
          {"character":"John Wick","movie":{"title":"John Wick","year":2014,"ids":{"imdb":"tt2911666"}}},
          {"characters":["Neo"],"movie":{"title":"The Matrix","year":1999,"ids":{"imdb":"tt0133093"}}},
          {"characters":["?"],"movie":{"title":"Untitled","year":null,"ids":{"imdb":null}}}
        ],"crew":{"directing":[{"jobs":["Director"],"movie":{"title":"Man of Tai Chi","year":2013,"ids":{"imdb":"tt2016940"}}}]}}"""
        val films = Trakt.parseCredits(text, "movie")
        assertEquals(listOf("John Wick", "The Matrix"), films.map { it.name })
        assertEquals("Neo", films[1].character)
        assertTrue(films[1].poster.startsWith("https://"))
        // Someone who only directs: the films they made.
        val director = Trakt.parseCredits("""{"cast":[],"crew":{"directing":[{"job":"Director","movie":{"title":"Man of Tai Chi","year":2013,"ids":{"imdb":"tt2016940"}}}]}}""", "movie")
        assertEquals(listOf("tt2016940"), director.map { it.imdb })
        val shows = Trakt.parseCredits("""{"cast":[{"characters":["Narrator"],"show":{"title":"Planet Earth","year":2006,"ids":{"imdb":"tt0795176"}}}]}""", "series")
        assertEquals("series", shows.single().type)
    }

    @Test
    fun aboutScoreAndWatchlist() {
        val a = Trakt.parseAbout("""{"name":"Keanu Reeves","ids":{"slug":"keanu-reeves"},"biography":"Keanu Charles Reeves is a Canadian actor. ","birthday":"1964-09-02","death":null,"birthplace":"Beirut, Lebanon","known_for_department":"acting"}""")!!
        assertEquals("Keanu Charles Reeves is a Canadian actor.", a.bio)
        assertEquals("Beirut, Lebanon", a.place)
        assertEquals("", a.died)
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
        assertTrue(Trakt.searchPeopleUrl("Keanu Reeves").contains("query=Keanu+Reeves"))
    }

    @Test
    fun theirTitlesInYourPlaylist() {
        val library = listOf(
            Channel("m1", "EN - The Matrix (1999) 4K", "Action", "http://x/1.mkv", kind = Kind.MOVIE),
            Channel("m2", "John Wick", "Action", "http://x/2.mkv", kind = Kind.MOVIE),
            Channel("m3", "Dune (1984)", "Sci-Fi", "http://x/3.mkv", kind = Kind.MOVIE),
            Channel("s1", "|AR| The Matrix", "Series", "", kind = Kind.SERIES),
        )
        val theirs = listOf(
            Trakt.Title("movie", "The Matrix", 1999, "tt0133093", ""),
            Trakt.Title("movie", "John Wick", 2014, "tt2911666", ""),
            Trakt.Title("movie", "Dune", 2021, "tt1160419", ""),
        )
        val found = Trakt.inLibrary(theirs, library).associate { it.first.imdb to it.second.id }
        // The film, not the series of the same name; a name with no year counts; another year's remake doesn't.
        assertEquals(mapOf("tt0133093" to "m1", "tt2911666" to "m2"), found)
    }

    @Test
    fun aProvidersCastLine() {
        assertEquals(listOf("Keanu Reeves", "Laurence Fishburne", "Carrie-Anne Moss"), Trakt.castNames("Keanu Reeves, Laurence Fishburne ,Carrie-Anne Moss,"))
        assertEquals(listOf("عادل إمام", "يسرا"), Trakt.castNames("عادل إمام، يسرا"))
        assertEquals(2, Trakt.castNames("A b, C d, E f", max = 2).size)
    }
}
