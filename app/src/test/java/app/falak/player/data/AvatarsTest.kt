package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Profile pictures (8.38). */
class AvatarsTest {
    @Test
    fun aPickStaysAndAnOldProfileGetsASteadyOne() {
        assertEquals("owl", Avatars.of("p1", "owl"))
        val steady = Avatars.of("p1", "")
        assertTrue(steady in Avatars.KEYS)
        assertEquals(steady, Avatars.of("p1", "not-one-of-ours"))
    }

    @Test
    fun aNewProfileGetsOneNobodyHas() {
        val taken = Avatars.KEYS.dropLast(1)
        repeat(20) { assertEquals(Avatars.KEYS.last(), Avatars.random(taken, kotlin.random.Random(it))) }
        // All taken: any of them.
        assertTrue(Avatars.random(Avatars.KEYS) in Avatars.KEYS)
    }
}
