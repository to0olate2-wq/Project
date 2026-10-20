package app.falak.player.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Evens out loudness between channels: measures the sound level as it plays and slowly turns
 * quiet channels up and loud ones down towards the same level, never letting peaks clip.
 * Works on decoded 16-bit sound (most channels); Dolby passthrough to a receiver is left alone.
 */
class VolumeLeveler : BaseAudioProcessor() {
    private var gain = 1.0
    private var blocks = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size < 2) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val start = input.position()
        val samples = size / 2

        // Loudness of this block.
        var sum = 0.0
        var peak = 0
        for (i in 0 until samples) {
            val s = input.getShort(start + i * 2).toInt()
            sum += (s * s).toDouble()
            val a = abs(s)
            if (a > peak) peak = a
        }
        val rms = sqrt(sum / samples)

        if (rms > SILENCE) {
            var target = (TARGET_RMS / rms).coerceIn(MIN_GAIN, MAX_GAIN)
            if (peak > 0) target = min(target, PEAK_CEILING / peak) // never boost a peak past full scale
            // Adapt fast for the first second of a channel, then: quick to turn down, slow to turn up.
            val speed = when {
                blocks < WARM_UP_BLOCKS -> 0.25
                target < gain -> 0.15
                else -> 0.01
            }
            gain += (target - gain) * speed
        }
        blocks++

        val out = replaceOutputBuffer(samples * 2)
        val g = gain
        for (i in 0 until samples) {
            val v = (input.getShort(start + i * 2) * g).toInt()
            out.putShort(v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
        input.position(start + size)
        out.flip()
    }

    /** A new channel or a seek: re-measure quickly, starting from the current level. */
    override fun onFlush() {
        blocks = 0
    }

    override fun onReset() {
        gain = 1.0
        blocks = 0
    }

    private companion object {
        /** About −21 dBFS: a comfortable TV level. */
        const val TARGET_RMS = 2_900.0
        /** Below about −47 dBFS is silence or a pause: keep the current level. */
        const val SILENCE = 150.0
        const val MIN_GAIN = 0.35
        const val MAX_GAIN = 4.0
        const val PEAK_CEILING = 32_000.0
        const val WARM_UP_BLOCKS = 40
    }
}
