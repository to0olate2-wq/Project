package app.falak.player.player

import app.falak.player.player.FrameRateMatch.Companion.choose
import app.falak.player.player.FrameRateMatch.Companion.snap
import app.falak.player.player.FrameRateMatch.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateMatchTest {
    private fun m(id: Int, hz: Float, w: Int = 1920, h: Int = 1080) = Mode(id, w, h, hz)

    private val tv = listOf(
        m(1, 60f), m(2, 59.94f), m(3, 50f), m(4, 24f), m(5, 23.976f), m(6, 30f), m(7, 25f),
        m(8, 60f, 3840, 2160),
    )
    private val current = m(1, 60f)

    @Test
    fun filmsGetTheirOwnRate() {
        assertEquals(5, choose(tv, current, 23.976f)?.id)
        assertEquals(4, choose(tv, current, 24f)?.id)
    }

    @Test
    fun broadcastRatesPreferDoubleRate() {
        assertEquals(3, choose(tv, current, 25f)?.id)
        assertEquals(2, choose(tv, current, 29.97f)?.id)
        assertEquals(1, choose(tv, current, 30f)?.id)
        assertEquals(3, choose(tv, current, 50f)?.id)
        assertEquals(2, choose(tv, current, 59.94f)?.id)
    }

    @Test
    fun resolutionStaysTheSame() {
        val uhd = m(8, 60f, 3840, 2160)
        assertEquals(8, choose(tv, uhd, 60f)?.id)
        assertNull(choose(tv, uhd, 24f))
    }

    @Test
    fun nearestRateWhenTheExactOneIsMissing() {
        val modes = listOf(m(1, 60f), m(4, 24f))
        assertEquals(4, choose(modes, m(1, 60f), 23.976f)?.id)
        assertEquals(1, choose(modes, m(1, 60f), 59.94f)?.id)
    }

    @Test
    fun nothingSuits() {
        val modes = listOf(m(1, 60f), m(2, 59.94f))
        assertNull(choose(modes, m(1, 60f), 25f))
        assertNull(choose(tv, current, 15f))
    }

    @Test
    fun snapsToStandardRates() {
        assertEquals(23.976f, snap(23.98f), 0.0001f)
        assertEquals(50f, snap(49.9f), 0.0001f)
        assertEquals(37f, snap(37f), 0.0001f)
    }
}
