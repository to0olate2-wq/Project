package app.falak.player.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class NightSoundTest {
    private val rate = 48_000

    /** One second of a 1 kHz tone at [amp] (0..1), stereo 16-bit. */
    private fun tone(amp: Double): ByteBuffer {
        val b = ByteBuffer.allocateDirect(rate * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until rate) {
            val v = (sin(2 * PI * 1000 * i / rate) * amp * 32767).toInt().toShort()
            b.putShort(v)
            b.putShort(v)
        }
        b.flip()
        return b
    }

    private fun peakOfLastHalf(p: NightSound, input: ByteBuffer): Int {
        p.queueInput(input)
        val out = p.output
        val n = out.remaining() / 2
        var peak = 0
        for (i in n / 2 until n) peak = maxOf(peak, abs(out.getShort(out.position() + i * 2).toInt()))
        return peak
    }

    private fun processor(on: Boolean) = NightSound().apply {
        enabled = on
        configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        flush()
    }

    @Test
    fun loudGetsQuieterAndQuietGetsLouder() {
        val loud = (0.9 * 32767).toInt()
        val quiet = (0.03 * 32767).toInt()
        val loudOut = peakOfLastHalf(processor(true), tone(0.9))
        val quietOut = peakOfLastHalf(processor(true), tone(0.03))
        assertTrue("loud $loudOut should be under $loud", loudOut < loud * 0.7)
        assertTrue("quiet $quietOut should be over $quiet", quietOut > quiet * 1.4)
    }

    @Test
    fun offPassesSoundUnchanged() {
        val p = processor(false)
        val input = tone(0.5)
        val copy = ByteArray(input.remaining()).also { input.duplicate().get(it) }
        p.queueInput(input)
        val out = p.output
        val got = ByteArray(out.remaining()).also { out.get(it) }
        assertTrue(copy.contentEquals(got))
    }
}
