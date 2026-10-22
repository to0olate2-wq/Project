package app.falak.player.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Night sound: clearer voices and softer bangs, for watching late without waking the house.
 * Voices get a little presence (around 2.5 kHz) and the boom below 120 Hz is turned down; then a
 * compressor brings loud moments down and quiet speech up, and a limiter keeps peaks in check.
 * With surround sound the centre channel (dialogue) is lifted and the subwoofer and rear lowered.
 *
 * It can be switched on and off while playing ([enabled]); the change fades over half a second so
 * there's no click. Works on decoded 16-bit sound; Dolby passthrough to a receiver is left alone.
 */
class NightSound : BaseAudioProcessor() {
    /** On or off right now (e.g. at sunset); fades in or out. */
    @Volatile var enabled = false

    private var channels = 2
    private var rate = 48_000
    private var mix = 0f
    private var mixStep = 0f

    // Per channel: low shelf and presence peak (biquads, direct form I).
    private lateinit var shelf: Biquad
    private lateinit var presence: Biquad
    private var weights = FloatArray(0)

    // Compressor (linked across channels).
    private var envDb = 0.0
    private var attack = 0.0
    private var release = 0.0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        channels = inputAudioFormat.channelCount
        rate = inputAudioFormat.sampleRate
        shelf = Biquad.lowShelf(rate, 120.0, -5.0, channels)
        presence = Biquad.peak(rate, 2_500.0, 4.0, 0.9, channels)
        weights = channelWeights(channels)
        attack = exp(-1.0 / (rate * 0.008))
        release = exp(-1.0 / (rate * 0.250))
        mixStep = 1f / (rate * 0.5f)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        val frameBytes = channels * 2
        if (size < frameBytes) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val start = input.position()
        val frames = size / frameBytes
        val out = replaceOutputBuffer(frames * frameBytes)
        val target = if (enabled) 1f else 0f

        // Off and faded out: pass the sound straight through.
        if (mix == 0f && target == 0f) {
            for (i in 0 until frames * channels) out.putShort(input.getShort(start + i * 2))
            input.position(start + frames * frameBytes)
            out.flip()
            return
        }

        val wet = FloatArray(channels)
        for (f in 0 until frames) {
            val base = start + f * frameBytes
            // Filters, channel weights, and the loudest channel of this frame.
            var peak = 0f
            for (c in 0 until channels) {
                val x = input.getShort(base + c * 2) / 32768f
                var y = shelf.process(c, x)
                y = presence.process(c, y)
                y *= weights[c]
                wet[c] = y
                val a = abs(y)
                if (a > peak) peak = a
            }
            // Gain reduction (dB) above the threshold, smoothed: fast down, slow back up.
            val levelDb = 20.0 * log10(max(peak.toDouble(), 1e-6))
            val over = levelDb - THRESHOLD_DB
            val reduce = if (over > 0) over * (1.0 - 1.0 / RATIO) else 0.0
            envDb = if (reduce > envDb) attack * envDb + (1 - attack) * reduce else release * envDb + (1 - release) * reduce
            val gain = 10.0.pow((MAKEUP_DB - envDb) / 20.0).toFloat()

            // Fade between the plain and the night sound.
            if (mix < target) mix = minOf(target, mix + mixStep) else if (mix > target) mix = maxOf(target, mix - mixStep)
            for (c in 0 until channels) {
                val dry = input.getShort(base + c * 2) / 32768f
                val v = dry * (1 - mix) + limit(wet[c] * gain) * mix
                out.putShort((v * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        input.position(start + frames * frameBytes)
        out.flip()
    }

    override fun onFlush() {
        envDb = 0.0
        if (::shelf.isInitialized) {
            shelf.clear()
            presence.clear()
        }
    }

    override fun onReset() {
        onFlush()
        mix = 0f
    }

    /** Dialogue louder than effects: centre up, subwoofer and surrounds down (5.1 / 7.1). */
    private fun channelWeights(n: Int): FloatArray = FloatArray(n) { c ->
        when {
            n < 6 -> 1f
            c == 2 -> 1.4f // centre
            c == 3 -> 0.5f // LFE
            c >= 4 -> 0.7f // rear / side
            else -> 1f
        }
    }

    /** Smooth ceiling just under full scale (no harsh clipping). */
    private fun limit(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        val over = (a - KNEE) / (1 - KNEE)
        val soft = KNEE + (1 - KNEE) * (over / (1 + over))
        return if (x < 0) -soft else soft
    }

    private class Biquad(
        private val b0: Float, private val b1: Float, private val b2: Float,
        private val a1: Float, private val a2: Float, channels: Int,
    ) {
        private val x1 = FloatArray(channels)
        private val x2 = FloatArray(channels)
        private val y1 = FloatArray(channels)
        private val y2 = FloatArray(channels)

        fun process(c: Int, x: Float): Float {
            val y = b0 * x + b1 * x1[c] + b2 * x2[c] - a1 * y1[c] - a2 * y2[c]
            x2[c] = x1[c]
            x1[c] = x
            y2[c] = y1[c]
            y1[c] = y
            return y
        }

        fun clear() {
            x1.fill(0f)
            x2.fill(0f)
            y1.fill(0f)
            y2.fill(0f)
        }

        companion object {
            // RBJ audio EQ cookbook.
            fun peak(rate: Int, freq: Double, gainDb: Double, q: Double, channels: Int): Biquad {
                val a = 10.0.pow(gainDb / 40)
                val w = 2 * PI * freq / rate
                val alpha = sin(w) / (2 * q)
                val a0 = 1 + alpha / a
                return Biquad(
                    ((1 + alpha * a) / a0).toFloat(), (-2 * cos(w) / a0).toFloat(), ((1 - alpha * a) / a0).toFloat(),
                    (-2 * cos(w) / a0).toFloat(), ((1 - alpha / a) / a0).toFloat(), channels,
                )
            }

            fun lowShelf(rate: Int, freq: Double, gainDb: Double, channels: Int): Biquad {
                val a = 10.0.pow(gainDb / 40)
                val w = 2 * PI * freq / rate
                val alpha = sin(w) / 2 * sqrt(2.0) // shelf slope 1
                val c = cos(w)
                val sa = 2 * sqrt(a) * alpha
                val a0 = (a + 1) + (a - 1) * c + sa
                return Biquad(
                    (a * ((a + 1) - (a - 1) * c + sa) / a0).toFloat(),
                    (2 * a * ((a - 1) - (a + 1) * c) / a0).toFloat(),
                    (a * ((a + 1) - (a - 1) * c - sa) / a0).toFloat(),
                    (-2 * ((a - 1) + (a + 1) * c) / a0).toFloat(),
                    (((a + 1) + (a - 1) * c - sa) / a0).toFloat(),
                    channels,
                )
            }
        }
    }

    private companion object {
        const val THRESHOLD_DB = -24.0
        const val RATIO = 3.5
        const val MAKEUP_DB = 6.0
        const val KNEE = 0.89f
    }
}
