package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipSegmentsTest {
    @Test
    fun linksForEpisodesAndFilms() {
        assertEquals("https://api.introdb.app/segments?imdb_id=tt0903747&season=1&episode=2", SkipSegments.url("tt0903747:1:2"))
        assertEquals("https://api.introdb.app/segments?imdb_id=tt0371746&is_movie=true", SkipSegments.url("tt0371746"))
        // No IMDb number: nothing to ask with.
        assertNull(SkipSegments.url("kitsu:123:4"))
        assertNull(SkipSegments.url("tt0903747:x:2"))
        assertNull(SkipSegments.url(""))
    }

    @Test
    fun readsTheAnswer() {
        // As introdb.app answers.
        val parts = SkipSegments.parse(
            """{"imdb_id":"tt0903747","media_type":"tv","is_movie":false,"season":1,"episode":1,
               "intro":{"start_sec":2.5,"end_sec":58,"start_ms":2500,"end_ms":58000,"confidence":0.92,"submission_count":14},
               "recap":null,"outro":{"start_sec":2600,"end_sec":2865},"post_credits":null,"no_intro":false}""",
        )
        assertEquals(
            listOf(SkipSegments.Part(SkipSegments.Kind.INTRO, 2_500, 58_000), SkipSegments.Part(SkipSegments.Kind.OUTRO, 2_600_000, 2_865_000)),
            parts,
        )
        // Nothing known, or not an answer at all.
        assertTrue(SkipSegments.parse("""{"intro":null,"recap":null,"outro":null}""").isEmpty())
        assertTrue(SkipSegments.parse("<html>Too many requests</html>").isEmpty())
        // Clock times, credits that run to the end, and a part too short to be worth a button.
        val odd = SkipSegments.parse("""{"recap":{"start_sec":"00:30","end_sec":"2:15"},"intro":{"start_ms":1000,"end_ms":3000},"outro":{"start_sec":"1:59:00"}}""")
        assertEquals(listOf(SkipSegments.Kind.RECAP, SkipSegments.Kind.OUTRO), odd.map { it.kind })
        assertEquals(30_000L to 135_000L, odd[0].start to odd[0].end)
        assertEquals(7_140_000L, odd[1].start)
    }

    @Test
    fun theButtonShowsWhileThePartPlays() {
        val intro = SkipSegments.Part(SkipSegments.Kind.INTRO, 60_000, 150_000)
        val credits = SkipSegments.Part(SkipSegments.Kind.OUTRO, 2_600_000, Long.MAX_VALUE)
        val parts = listOf(intro, credits)
        val dur = 2_700_000L
        assertNull(SkipSegments.offer(parts, 30_000, dur))
        assertEquals(intro, SkipSegments.offer(parts, 59_500, dur))
        assertEquals(intro, SkipSegments.offer(parts, 100_000, dur))
        // Its last seconds, and after it: no button.
        assertNull(SkipSegments.offer(parts, 147_000, dur))
        assertNull(SkipSegments.offer(parts, 1_000_000, dur))
        assertEquals(credits, SkipSegments.offer(parts, 2_650_000, dur))
        assertNull(SkipSegments.offer(parts, 2_698_000, dur))
        // A real answer for a film (Iron Man, November 2026): an "intro" two hours in, inside the
        // credits, and a scene after them. In the credits the button is "Skip credits", and it
        // jumps to that scene.
        val film = SkipSegments.parse(
            """{"imdb_id":"tt0371746","media_type":"movie","is_movie":true,"season":0,"episode":0,
               "intro":{"start_sec":7512,"end_sec":7545,"start_ms":7512000,"end_ms":7545000,"confidence":1,"submission_count":1},
               "recap":null,"outro":{"start_sec":7038,"end_sec":7540,"start_ms":7038000,"end_ms":7540000,"confidence":1,"submission_count":1},
               "post_credits":{"start_sec":7540,"end_sec":7580,"start_ms":7540000,"end_ms":7580000}}""",
        )
        val length = 7_580_000L
        assertEquals(SkipSegments.Kind.OUTRO, SkipSegments.offer(film, 7_515_000, length)?.kind)
        assertEquals(7_540_000, SkipSegments.target(SkipSegments.offer(film, 7_100_000, length)!!, length))
        assertNull(SkipSegments.offer(film, 60_000, length))
        // (Credits written into the first half are a slip too.)
        assertNull(SkipSegments.offer(listOf(SkipSegments.Part(SkipSegments.Kind.OUTRO, 100_000, 200_000)), 150_000, dur))
        // Where it jumps: the end of the intro; for credits, the end of the episode.
        assertEquals(150_000, SkipSegments.target(intro, dur))
        assertEquals(dur - 500, SkipSegments.target(credits, dur))
    }
}
