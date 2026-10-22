package app.falak.player.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KidsTest {
    @Test
    fun suggestsGroupsMadeForChildren() {
        listOf("Kids", "UK | Cartoons", "Disney Junior", "Nickelodeon", "أطفال", "AR | كرتون", "Family Movies").forEach {
            assertTrue(it, looksForKids(it))
        }
    }

    @Test
    fun leavesOtherGroupsAlone() {
        listOf("News", "Sports", "Documentary", "Animal Planet", "Movies 4K", "Adult Cartoons", "XXX").forEach {
            assertFalse(it, looksForKids(it))
        }
    }
}
