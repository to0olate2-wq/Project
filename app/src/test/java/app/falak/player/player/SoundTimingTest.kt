package app.falak.player.player

import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Test

class SoundTimingTest {
    @Test
    fun theClockMovesBothWays() {
        // Sound later: the clock runs ahead, so frames come sooner.
        assertEquals(1_150_000L, SoundTiming.shifted(1_000_000L, 150_000L))
        // Sound earlier (a soundbar): the clock runs behind, the picture waits; never before 0.
        assertEquals(850_000L, SoundTiming.shifted(1_000_000L, -150_000L))
        assertEquals(0L, SoundTiming.shifted(100_000L, -300_000L))
        // "Not set yet" is passed on as it is.
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, SoundTiming.shifted(AudioSink.CURRENT_POSITION_NOT_SET, 150_000L))
    }

    @Test
    fun stepsStayInReachAndSayWhatTheyDo() {
        assertEquals(2_000, SoundTiming.clamp(2_050))
        assertEquals(-2_000, SoundTiming.clamp(-9_000))
        assertEquals("Sound 150 ms later", SoundTiming.label(150))
        assertEquals("Sound 200 ms earlier", SoundTiming.label(-200))
        assertEquals("In step (as the stream sends it)", SoundTiming.label(0))
    }
}
