package app.falak.player.player

import org.junit.Assert.assertEquals
import org.junit.Test

class FreezeRuleTest {
    private fun FreezeRule.at(t: Long, buffering: Boolean = false, pos: Long = t, playing: Boolean = true, started: Boolean = true, live: Boolean = true, id: String = "c1", moves: Boolean = true, watching: Boolean = true) =
        step(t, id, watching, buffering, !buffering, playing && !buffering, pos, moves, started, live)

    @Test
    fun aChannelLoadingTooLongStartsAgain() {
        val r = FreezeRule()
        var t = 0L
        // Playing well: nothing.
        while (t < 20_000) { assertEquals(FreezeRule.Action.NONE, r.at(t)); t += 2_000 }
        // Loading from 20 s: 12 s later it starts again.
        val actions = (0..8).map { r.at(t + it * 2_000L, buffering = true, pos = 20_000) }
        assertEquals(FreezeRule.Action.RESTART, actions[6])
        assertEquals(1, actions.count { it == FreezeRule.Action.RESTART })
    }

    @Test
    fun aStillPictureCountsOnlyWhereThePositionIsSure() {
        val r = FreezeRule()
        // "Playing" but the position stands at 5000.
        val frozen = (0..7).map { r.at(it * 2_000L, pos = 5_000) }
        assertEquals(FreezeRule.Action.RESTART, frozen.last())
        // VLC: a still position isn't counted.
        val v = FreezeRule()
        assertEquals(setOf(FreezeRule.Action.NONE), (0..20).map { v.at(it * 2_000L, pos = 5_000, moves = false) }.toSet())
    }

    @Test
    fun pausedOrASlowStartIsNotAFreeze() {
        val r = FreezeRule()
        // Paused by you: not watching.
        assertEquals(setOf(FreezeRule.Action.NONE), (0..20).map { r.at(it * 2_000L, pos = 1_000, watching = false) }.toSet())
        // Before the first picture: 20 s for a channel, not 12.
        val s = FreezeRule()
        val slow = (0..12).map { s.at(it * 2_000L, buffering = true, pos = 0, started = false) }
        assertEquals(FreezeRule.Action.NONE, slow[8])
        assertEquals(FreezeRule.Action.RESTART, slow[10])
    }

    @Test
    fun threeTimesThenItStopsTrying() {
        val r = FreezeRule()
        val out = ArrayList<FreezeRule.Action>()
        var t = 0L
        repeat(60) { out += r.at(t, buffering = true, pos = 0); t += 2_000 }
        assertEquals(3, out.count { it == FreezeRule.Action.RESTART })
        assertEquals(1, out.count { it == FreezeRule.Action.GIVE_UP })
        // Another channel: counted afresh.
        val again = (0..8).map { r.at(t + it * 2_000L, buffering = true, pos = 0, id = "c2") }
        assertEquals(FreezeRule.Action.RESTART, again[6])
    }
}
