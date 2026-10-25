package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kids screen time: minutes left, time up, bedtime across midnight, and a grown-up's extra time. */
class KidsTimeTest {
    private fun state(
        usedMin: Int,
        daily: Int = 60,
        extra: Int = 0,
        unlimited: Boolean = false,
        minute: Int = 15 * 60,
        bedtime: Int = -1,
        wake: Int = 7 * 60,
        lifted: Boolean = false,
    ) = KidsTime.state(usedMin * 60_000L, daily, extra, unlimited, minute, bedtime, wake, lifted)

    @Test
    fun minutesLeftAndTimeUp() {
        assertEquals(KidsTime.State(null, 50), state(usedMin = 10))
        assertEquals(KidsTime.State(KidsTime.Block.TIME_UP, 0), state(usedMin = 60))
        // Half a minute left still shows as one.
        assertEquals(1, KidsTime.state(59 * 60_000L + 30_000L, 60, 0, false, 900, -1, 420, false).minutesLeft)
        // A grown-up added 30 minutes, or lifted the limit for today.
        assertEquals(KidsTime.State(null, 20), state(usedMin = 70, extra = 30))
        assertEquals(KidsTime.State(null, null), state(usedMin = 500, unlimited = true))
        // No limit set.
        assertEquals(KidsTime.State(null, null), state(usedMin = 500, daily = 0))
    }

    @Test
    fun bedtimeRunsPastMidnight() {
        assertTrue(KidsTime.inBedtime(20 * 60 + 30, 20 * 60, 7 * 60))
        assertTrue(KidsTime.inBedtime(2 * 60, 20 * 60, 7 * 60))
        assertFalse(KidsTime.inBedtime(7 * 60, 20 * 60, 7 * 60))
        assertFalse(KidsTime.inBedtime(19 * 60 + 59, 20 * 60, 7 * 60))
        assertFalse(KidsTime.inBedtime(2 * 60, -1, 7 * 60))
        assertEquals(KidsTime.Block.BEDTIME, state(usedMin = 0, minute = 21 * 60, bedtime = 20 * 60).block)
        // Lifted for tonight by a grown-up.
        assertNull(state(usedMin = 0, minute = 21 * 60, bedtime = 20 * 60, lifted = true).block)
    }

    @Test
    fun durations() {
        assertEquals("45 min", KidsTime.duration(45))
        assertEquals("2 h", KidsTime.duration(120))
        assertEquals("1 h 30 min", KidsTime.duration(90))
    }
}
