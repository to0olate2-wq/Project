package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 8.45: what the player shows. */
class PlayerInfoTest {
    @Test
    fun theDefaultIsWhatShowedBefore() {
        // Everything but the clock, so nothing changes for anyone who doesn't pick.
        assertFalse(PlayerInfo.CLOCK in PlayerInfo.DEFAULT)
        assertEquals(PlayerInfo.CHOICES.map { it.first } - PlayerInfo.CLOCK, PlayerInfo.DEFAULT)
        assertEquals(PlayerInfo.DEFAULT, Settings().playerInfo)
    }

    @Test
    fun turningOnAndOffKeepsTheOrder() {
        val withClock = PlayerInfo.toggled(PlayerInfo.DEFAULT, PlayerInfo.CLOCK)
        assertEquals(PlayerInfo.CLOCK, withClock.first())
        assertEquals(PlayerInfo.DEFAULT, PlayerInfo.toggled(withClock, PlayerInfo.CLOCK))
        assertEquals(PlayerInfo.DEFAULT - PlayerInfo.NEXT, PlayerInfo.toggled(PlayerInfo.DEFAULT, PlayerInfo.NEXT))
    }
}
