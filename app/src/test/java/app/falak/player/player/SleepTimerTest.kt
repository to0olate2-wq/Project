package app.falak.player.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sleep timer: the time left, the fade over the last minute, and stopping after a film. */
class SleepTimerTest {
    @After
    fun off() = SleepTimer.cancel()

    @Test
    fun countsDownAndFades() {
        SleepTimer.after(30, now = 1_000L)
        assertEquals("Sleep 30 min", SleepTimer.buttonLabel(1_000L))
        assertEquals("Sleep 29 min", SleepTimer.buttonLabel(1_000L + 61_000L))
        assertEquals(1f, SleepTimer.volume(1_000L + 28 * 60_000L), 0f)
        // Halfway through the last minute: half the sound.
        assertEquals(0.5f, SleepTimer.volume(1_000L + 30 * 60_000L - 30_000L), 0.001f)
        assertEquals("Sleep <1 min", SleepTimer.buttonLabel(1_000L + 30 * 60_000L - 30_000L))
        assertFalse(SleepTimer.due(1_000L + 30 * 60_000L - 1))
        assertTrue(SleepTimer.due(1_000L + 30 * 60_000L))
        assertEquals(0f, SleepTimer.volume(1_000L + 31 * 60_000L), 0f)
    }

    @Test
    fun endOfFilmWaitsForThatFilm() {
        SleepTimer.endOf("m42", "End of this film")
        assertTrue(SleepTimer.stopsAfter("m42"))
        assertFalse(SleepTimer.stopsAfter("m43"))
        assertFalse(SleepTimer.due(Long.MAX_VALUE))
        assertEquals(1f, SleepTimer.volume(5_000L), 0f)
        assertEquals("Sleep · end", SleepTimer.buttonLabel(0L))
    }

    @Test
    fun offIsOff() {
        SleepTimer.at(10_000L, "End of the programme")
        SleepTimer.cancel()
        assertFalse(SleepTimer.isOn)
        assertFalse(SleepTimer.due(20_000L))
        assertEquals("Sleep", SleepTimer.buttonLabel(0L))
    }
}
