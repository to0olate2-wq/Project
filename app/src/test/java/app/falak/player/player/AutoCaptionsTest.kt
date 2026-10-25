package app.falak.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AutoCaptionsTest {
    @Test
    fun rollsUpTheLastTwoLines() {
        assertNull(CaptionText.rollUp("   ", 42, 2))
        assertEquals("Good evening.", CaptionText.rollUp("Good evening.", 42, 2))
        val long = "We're going live now to the stadium, where the teams are coming out for the second half of the final."
        // Wrapped at 42 characters, the last two lines kept.
        assertEquals(
            "the teams are coming out for the second\nhalf of the final.",
            CaptionText.rollUp(long, 42, 2),
        )
        // A word longer than a line still shows.
        assertEquals("Supercalifragilistic", CaptionText.rollUp("Supercalifragilistic", 10, 2))
    }

    @Test
    fun aFinishedLineStaysAboveTheNextOne() {
        val t = CaptionText()
        assertEquals("Good evening", t.partial("Good evening"))
        assertEquals("Good evening, and welcome.", t.finish("Good evening, and welcome."))
        assertEquals("Good evening, and welcome. Tonight", t.partial("Tonight"))
        t.clear()
        assertNull(t.partial(""))
    }

    private fun pcm(vararg samples: Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        for (s in samples) b.putShort(s.toShort())
        b.flip()
        return b
    }

    @Test
    fun stereo48kBecomesMono16k() {
        // Six stereo frames at 48 kHz: two mono samples at 16 kHz (each the average of 3 frames, both channels).
        val b = pcm(16384, 16384, 16384, 16384, 16384, 16384, -16384, -16384, -16384, -16384, -16384, -16384)
        val (mono, rate) = Downmix.mono16k(b, channels = 2, rate = 48000)!!
        assertEquals(16000, rate)
        assertEquals(2, mono.size)
        assertEquals(0.5f, mono[0], 1e-4f)
        assertEquals(-0.5f, mono[1], 1e-4f)
        // The buffer itself isn't consumed.
        assertEquals(0, b.position())
    }

    @Test
    fun otherRatesStayAsTheyAre() {
        val (mono, rate) = Downmix.mono16k(pcm(32767, -32768, 0, 0), channels = 2, rate = 44100)!!
        assertEquals(44100, rate)
        assertEquals(2, mono.size)
        assertEquals(0f, mono[0], 1e-4f)
        assertNull(Downmix.mono16k(pcm(), channels = 2, rate = 48000))
        // Less than one 16 kHz sample's worth.
        assertNull(Downmix.mono16k(pcm(1, 2, 3, 4), channels = 2, rate = 48000))
    }
}
