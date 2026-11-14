package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadChannelsTest {
    private val min = 60_000L

    @Test
    fun aFailureWaitsForAnotherChannelToPlay() {
        val t = DeadChannels.Tracker()
        // Nothing has played yet: it could be the internet.
        assertTrue(t.failed("a", 0).isEmpty())
        // Another channel plays within ten minutes: it was the channel.
        assertEquals(listOf("a"), t.played("b", 4 * min))
        // And a channel that fails right after one played is hidden at once.
        assertEquals(listOf("c"), t.failed("c", 5 * min))
    }

    @Test
    fun notWhenNothingElsePlaysInTime() {
        val t = DeadChannels.Tracker()
        t.failed("a", 0)
        assertTrue(t.played("b", 11 * min).isEmpty())
        // (And it is forgotten: a later channel doesn't bring it back.)
        assertTrue(t.played("c", 12 * min).isEmpty())
    }

    @Test
    fun aChannelThatJustWorkedOnlyHiccupped() {
        val t = DeadChannels.Tracker()
        t.played("a", 0)
        assertTrue(t.failed("a", 5 * min).isEmpty())
        assertTrue(t.played("b", 6 * min).isEmpty())
        // Much later it counts again (another channel played a moment before).
        t.played("c", 39 * min)
        assertEquals(listOf("a"), t.failed("a", 40 * min))
    }

    @Test
    fun theSameChannelPlayingAgainProvesNothing() {
        val t = DeadChannels.Tracker()
        t.failed("a", 0)
        // It plays after all: not dead, and nothing else was waiting.
        assertTrue(t.played("a", 1 * min).isEmpty())
    }

    @Test
    fun manyFailingAtOnceIsTheProvider() {
        val t = DeadChannels.Tracker()
        for (i in 1..6) t.failed("x$i", i * 1_000L)
        assertTrue(t.played("ok", 2 * min).isEmpty())
        // Five or fewer are taken as the channels' own fault.
        val u = DeadChannels.Tracker()
        for (i in 1..5) u.failed("x$i", i * 1_000L)
        assertEquals(5, u.played("ok", 2 * min).size)
    }

    @Test
    fun hiddenForAWeekThenTriedAgain() {
        val day = 24 * 3_600_000L
        var marks = DeadChannels.mark(emptyMap(), listOf("a"), now = 0)
        marks = DeadChannels.mark(marks, listOf("b"), now = 3 * day)
        assertEquals(setOf("a", "b"), DeadChannels.hidden(marks, 6 * day))
        assertEquals(setOf("b"), DeadChannels.hidden(marks, 8 * day))
        // (Old marks are dropped when new ones are written.)
        assertEquals(setOf("b", "c"), DeadChannels.mark(marks, listOf("c"), now = 8 * day).keys)
        assertTrue(DeadChannels.hidden(null, 0).isEmpty())
    }
}
