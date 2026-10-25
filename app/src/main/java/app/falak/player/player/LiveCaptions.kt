package app.falak.player.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Auto captions: what's said on an English channel, turned into captions on this device as it
 * plays (sherpa-onnx's streaming recognizer with [SpeechModel]; nothing leaves the device).
 *
 * [newTap] goes into ExoPlayer's audio processors: it passes the sound on untouched and keeps a
 * mono copy. Recognition runs on a thread of its own, about a second behind the speech. [onText]
 * gets the words to show (see [CaptionText]; null: nothing to show), on the main thread;
 * [onProblem] says when it can't work here (the speech engine doesn't run on this device, or it
 * can't keep up).
 */
class LiveCaptions(
    private val modelDir: File,
    private val onText: (String?) -> Unit,
    private val onProblem: (Problem) -> Unit,
) {
    enum class Problem { UNAVAILABLE, TOO_SLOW }

    private class Chunk(val samples: FloatArray, val rate: Int, val reset: Boolean = false)

    @Volatile private var listening = false

    /** The model is loaded and recognition keeps up with what comes in. */
    @Volatile private var ready = false
    @Volatile private var dropped = 0

    /** The speech engine can't run on this device (said once; no more tries). */
    @Volatile private var broken = false
    private val queue = ArrayBlockingQueue<Chunk>(QUEUE)
    private val main = Handler(Looper.getMainLooper())
    private var thread: Thread? = null

    /** A fresh tap for a new ExoPlayer's audio chain. */
    fun newTap(): AudioProcessor = Tap()

    /** Captions for what plays from now on (a new channel starts afresh). */
    fun start() {
        if (broken) return
        listening = true
        dropped = 0
        queue.clear()
        queue.offer(Chunk(FloatArray(0), 0, reset = true))
        if (thread?.isAlive != true) {
            thread = Thread({ run() }, "auto-captions").apply {
                priority = Thread.NORM_PRIORITY - 1
                start()
            }
        }
    }

    /** No captions for now (the speech engine stays loaded for the next channel). */
    fun stop() {
        listening = false
        queue.clear()
        main.post { onText(null) }
    }

    /** Stops and lets go of the speech engine and its memory. */
    fun release() {
        stop()
        thread?.interrupt()
        thread = null
    }

    private fun run() {
        val recognizer = try {
            Engine(modelDir)
        } catch (t: Throwable) {
            broken = true
            listening = false
            main.post { onProblem(Problem.UNAVAILABLE) }
            return
        }
        // Loading took a moment: start from what comes in now.
        queue.clear()
        dropped = 0
        ready = true
        val text = CaptionText()
        var audioSeconds = 0.0
        var busyNanos = 0L
        var slowWindows = 0
        try {
            while (!Thread.currentThread().isInterrupted) {
                val c = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                if (c.reset) {
                    recognizer.restart()
                    text.clear()
                    main.post { onText(null) }
                    continue
                }
                if (!listening) continue
                val t0 = System.nanoTime()
                val heard = recognizer.feed(c.samples, c.rate)
                busyNanos += System.nanoTime() - t0
                audioSeconds += c.samples.size.toDouble() / c.rate
                if (heard != null) {
                    val shown = if (heard.ended) text.finish(heard.text) else text.partial(heard.text)
                    if (shown != null) main.post { if (listening) onText(shown) }
                }
                // Every 30 seconds of sound: did recognising it take nearly as long (twice in a row),
                // or did sound pile up unheard? Then this device can't keep up.
                if (audioSeconds >= 30.0) {
                    val load = busyNanos / 1e9 / audioSeconds
                    slowWindows = if (load > 0.9) slowWindows + 1 else 0
                    audioSeconds = 0.0
                    busyNanos = 0L
                    if (slowWindows >= 2 || dropped > QUEUE) {
                        listening = false
                        queue.clear()
                        main.post {
                            onText(null)
                            onProblem(Problem.TOO_SLOW)
                        }
                    }
                }
            }
        } catch (e: InterruptedException) {
            // released
        } finally {
            ready = false
            recognizer.close()
        }
    }

    /** sherpa-onnx's streaming recognizer, kept on the captions thread. */
    private class Engine(dir: File) {
        class Heard(val text: String, val ended: Boolean)

        private val recognizer: com.k2fsa.sherpa.onnx.OnlineRecognizer
        private var stream: com.k2fsa.sherpa.onnx.OnlineStream
        private var last = ""

        init {
            fun f(name: String) = File(dir, name).absolutePath
            val config = com.k2fsa.sherpa.onnx.OnlineRecognizerConfig(
                featConfig = com.k2fsa.sherpa.onnx.FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = com.k2fsa.sherpa.onnx.OnlineModelConfig(
                    transducer = com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig(
                        encoder = f("encoder.onnx"),
                        decoder = f("decoder.onnx"),
                        joiner = f("joiner.onnx"),
                    ),
                    tokens = f("tokens.txt"),
                    numThreads = 2,
                    provider = "cpu",
                    modelType = "zipformer2",
                ),
                // A pause ends a line; a line never runs past 12 seconds.
                endpointConfig = com.k2fsa.sherpa.onnx.EndpointConfig(
                    rule1 = com.k2fsa.sherpa.onnx.EndpointRule(false, 2.0f, 0.0f),
                    rule2 = com.k2fsa.sherpa.onnx.EndpointRule(true, 0.8f, 0.0f),
                    rule3 = com.k2fsa.sherpa.onnx.EndpointRule(false, 0.0f, 12.0f),
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
            )
            recognizer = com.k2fsa.sherpa.onnx.OnlineRecognizer(null, config)
            stream = recognizer.createStream()
        }

        /** New sound; what's been said so far in this line, when it changed (or the line ended). */
        fun feed(samples: FloatArray, rate: Int): Heard? {
            stream.acceptWaveform(samples, rate)
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            val now = recognizer.getResult(stream).text.trim()
            if (recognizer.isEndpoint(stream)) {
                recognizer.reset(stream)
                last = ""
                return if (now.isNotEmpty()) Heard(now, ended = true) else null
            }
            if (now == last) return null
            last = now
            return Heard(now, ended = false)
        }

        fun restart() {
            stream.release()
            stream = recognizer.createStream()
            last = ""
        }

        fun close() {
            stream.release()
            recognizer.release()
        }
    }

    /** Passes the sound on untouched; while listening, sends a mono copy (16 kHz when it divides). */
    private inner class Tap : BaseAudioProcessor() {
        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
            if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

        override fun queueInput(inputBuffer: ByteBuffer) {
            val size = inputBuffer.remaining()
            if (size == 0) return
            if (listening && ready) copy(inputBuffer)
            replaceOutputBuffer(size).put(inputBuffer).flip()
        }

        override fun onFlush() {
            // A jump (new channel, seek): the next words start a new line.
            if (listening) queue.offer(Chunk(FloatArray(0), 0, reset = true))
        }

        private fun copy(input: ByteBuffer) {
            val f = inputAudioFormat
            val channels = f.channelCount
            if (channels <= 0 || f.sampleRate <= 0) return
            val mono = Downmix.mono16k(input.duplicate().order(ByteOrder.nativeOrder()), channels, f.sampleRate) ?: return
            if (!queue.offer(Chunk(mono.first, mono.second))) dropped++
        }
    }

    companion object {
        /** About 10 seconds of sound waiting at most. */
        private const val QUEUE = 512
    }
}

/** Mono, and 16 kHz when the rate is a multiple of it (48 kHz: every 3 frames averaged). */
object Downmix {
    /** 16-bit PCM from [buf]'s position to its limit; null when it holds no whole frame. */
    fun mono16k(buf: ByteBuffer, channels: Int, rate: Int): Pair<FloatArray, Int>? {
        val start = buf.position()
        val frames = (buf.limit() - start) / (2 * channels)
        if (frames <= 0) return null
        val step = if (rate % 16000 == 0) rate / 16000 else 1
        val outFrames = frames / step
        if (outFrames <= 0) return null
        val out = FloatArray(outFrames)
        val scale = 1f / (32768f * channels * step)
        for (o in 0 until outFrames) {
            var sum = 0f
            val first = o * step
            for (k in 0 until step) {
                val at = start + (first + k) * 2 * channels
                for (c in 0 until channels) sum += buf.getShort(at + c * 2)
            }
            out[o] = sum * scale
        }
        return out to rate / step
    }
}

/**
 * What to show, broadcast style: the words heard, the line said before kept until new words push
 * it up. [partial] and [finish] give all of it; [rollUp] fits it to the screen (the last lines,
 * each as wide as fits), so the player can fit it again when the lines or the size change.
 */
class CaptionText {
    private var before = ""
    private var current = ""

    fun clear() {
        before = ""
        current = ""
    }

    /** The words so far in the line being spoken (with the line before); null when there are none. */
    fun partial(text: String): String? {
        current = text
        return shown()
    }

    /** The line was finished (a pause, or long enough). */
    fun finish(text: String): String? {
        before = text
        current = ""
        return shown()
    }

    private fun shown(): String? = words("$before $current").joinToString(" ").ifEmpty { null }

    companion object {
        private fun words(text: String) = text.split(' ', '\n', '\t').filter { it.isNotEmpty() }

        /** [text]'s words wrapped at [lineChars], the last [lines] lines; null when there are none. */
        fun rollUp(text: String, lineChars: Int, lines: Int): String? = rollUp(text, lines) { it.length <= lineChars }

        /**
         * [text]'s words wrapped into lines that [fits] (a word too long for a line still gets one),
         * the last [lines] of them; null when there are none.
         */
        fun rollUp(text: String, lines: Int, fits: (String) -> Boolean): String? {
            val words = words(text)
            if (words.isEmpty()) return null
            val out = ArrayList<String>()
            var line = ""
            for (w in words) {
                if (line.isEmpty()) {
                    line = w
                } else {
                    val longer = "$line $w"
                    if (fits(longer)) {
                        line = longer
                    } else {
                        out += line
                        line = w
                    }
                }
            }
            out += line
            return out.takeLast(lines.coerceAtLeast(1)).joinToString("\n")
        }
    }
}
