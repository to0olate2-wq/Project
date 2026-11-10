package app.falak.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** A channel with no logo (or a broken logo link) shows its initials, not an empty box. */
class InitialsTest {
    @Test
    fun leavesOutTagsAndQuality() {
        assertEquals("MBC", initialsOf("AR | MBC 1"))
        assertEquals("MBC", initialsOf("AR: MBC Drama HD"))
        assertEquals("BBC", initialsOf("[UK] BBC One FHD"))
        assertEquals("SS", initialsOf("UK - Sky Sports News"))
        assertEquals("RC", initialsOf("|AR| Rotana Cinema"))
        assertEquals("ESPN", initialsOf("ESPN 2 HD"))
    }

    @Test
    fun acronymsWholeOtherwiseFirstLetters() {
        assertEquals("CNN", initialsOf("CNN"))
        assertEquals("TF1", initialsOf("TF1"))
        assertEquals("AJ", initialsOf("Al Jazeera News HD"))
        assertEquals("BS", initialsOf("beIN Sports 2"))
        assertEquals("R", initialsOf("Rotana"))
    }

    @Test
    fun otherScriptsAndOddNames() {
        assertEquals("ق", initialsOf("قناة الجزيرة"))
        assertEquals("4K", initialsOf("4K"))
        assertEquals("TV", initialsOf(""))
        assertEquals("TV", initialsOf(" | "))
    }
}
