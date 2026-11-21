package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FinishTest {
    private val min = 60_000L

    private fun film(pos: Long, dur: Long) = Watch(pos * min, dur * min, kind = Kind.MOVIE)

    @Test
    fun filmsLeftFitTheTimeLongestFirst() {
        val p = mapOf(
            "pl|a" to film(30, 120), // 90 left
            "pl|b" to film(100, 130), // 30 left
            "pl|c" to film(10, 50), // 40 left
            "other|d" to film(0, 20), // another playlist
            "pl|e" to film(120, 120), // watched
        )
        assertEquals(listOf("c", "b"), Finish.fits(p, "pl", 45).map { it.id })
        assertEquals(listOf("a", "c", "b"), Finish.fits(p, "pl", 90).map { it.id })
    }

    @Test
    fun theNextEpisodeIsAsLongAsTheOnesBefore() {
        val p = mapOf(
            "pl|e1" to Watch(42 * min, 42 * min, kind = Kind.SERIES, seriesId = "s"),
            "pl|e2" to Watch(46 * min, 46 * min, kind = Kind.SERIES, seriesId = "s"),
            "pl|e3" to Watch(1, 0, kind = Kind.SERIES, seriesId = "s"), // waiting in Continue watching
            "pl|x1" to Watch(1, 0, kind = Kind.SERIES, seriesId = "unknown"), // nothing to go by
        )
        val picks = Finish.fits(p, "pl", 60)
        assertEquals(listOf("e3"), picks.map { it.id })
        assertEquals(46 * min, picks[0].leftMs)
        assertEquals(true, picks[0].guessed)
        assertEquals(emptyList<Finish.Pick>(), Finish.fits(p, "pl", 30))
    }

    @Test
    fun liveAndTheCreditsAreLeftOut() {
        val p = mapOf(
            "pl|live" to Watch(0, 0, kind = Kind.LIVE),
            "pl|end" to film(119, 120) + 30_000, // 30 s left
        )
        assertEquals(emptyList<Finish.Pick>(), Finish.fits(p, "pl", 180))
    }

    private operator fun Watch.plus(ms: Long) = copy(pos = pos + ms)

    @Test
    fun lengthsReadPlainly() {
        assertEquals("47 min", Finish.length(47 * min))
        assertEquals("1 h 05 min", Finish.length(65 * min))
    }
}
