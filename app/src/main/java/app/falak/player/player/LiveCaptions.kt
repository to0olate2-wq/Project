package app.falak.player.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
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
 * mono copy; [watch] wraps ExoPlayer's audio output to know when that sound will be heard.
 * Recognition runs on a thread of its own. The engine works on 1.28 seconds of sound at a time, so
 * words come out 0.2 to 1.4 seconds after they're said; each [Word] carries the moment it's heard,
 * so with a head start ([HEAD_START_US], "In time with the speech") it can show as it's said.
 * [onText] gets the words to show (see [CaptionText]; null: nothing to show), on the main thread;
 * [onProblem] says when it can't work here (the speech engine doesn't run on this device, or it
 * can't keep up).
 */
class LiveCaptions(
    private val modelDir: File,
    private val onText: (List<Word>?) -> Unit,
    private val onProblem: (Problem) -> Unit,
) {
    enum class Problem { UNAVAILABLE, TOO_SLOW }

    /** Sound for the engine; [atUs]: when its first sample is heard (the audio output's timeline). */
    private class Chunk(val samples: FloatArray, val rate: Int, val reset: Boolean = false, val atUs: Long = Word.NOW)

    /** The audio output's time of the sound going in now (read by the tap, on the same thread). */
    @Volatile private var inputUs = Word.NOW

    /** Where the sound being heard was, and when that was seen (elapsedRealtimeNanos). */
    private class Heard(val us: Long, val atNanos: Long)

    @Volatile private var heard: Heard? = null

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

    /**
     * ExoPlayer's audio output, watched: the time of each piece of sound going in (for the tap)
     * and of the sound being heard (ExoPlayer asks for it every few milliseconds while playing).
     * Both are in the output's own timeline, so they compare directly.
     */
    fun watch(sink: AudioSink): AudioSink = object : ForwardingAudioSink(sink) {
        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            inputUs = presentationTimeUs
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
            val us = super.getCurrentPositionUs(sourceEnded)
            if (us != AudioSink.CURRENT_POSITION_NOT_SET) heard = Heard(us, SystemClock.elapsedRealtimeNanos())
            return us
        }

        override fun flush() {
            inputUs = Word.NOW
            heard = null
            super.flush()
        }

        override fun reset() {
            inputUs = Word.NOW
            heard = null
            super.reset()
        }
    }

    /**
     * Where the sound being heard is now, in the audio output's timeline (null: not known yet).
     * [playing]: it moves on from the last time it was seen (never more than a quarter second).
     */
    fun heardNowUs(playing: Boolean): Long? {
        val h = heard ?: return null
        if (!playing) return h.us
        val sinceUs = ((SystemClock.elapsedRealtimeNanos() - h.atNanos) / 1000).coerceIn(0L, 250_000L)
        return h.us + sinceUs
    }

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
        val clock = WordClock(SpeechModel.CHUNK_SECONDS)
        var audioSeconds = 0.0
        var busyNanos = 0L
        var slowWindows = 0
        try {
            while (!Thread.currentThread().isInterrupted) {
                val c = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                if (c.reset) {
                    recognizer.restart()
                    clock.restart()
                    text.clear()
                    main.post { onText(null) }
                    continue
                }
                if (!listening) continue
                val t0 = System.nanoTime()
                clock.fed(c.samples.size.toDouble() / c.rate, c.atUs)
                val heard = recognizer.feed(c.samples, c.rate, clock)
                busyNanos += System.nanoTime() - t0
                audioSeconds += c.samples.size.toDouble() / c.rate
                if (heard != null) {
                    val shown = if (heard.ended) text.finish(heard.words) else text.partial(heard.words)
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
        class Heard(val words: List<Word>, val ended: Boolean)

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

        /**
         * New sound; the words said so far in this line (each with when it's heard, from [clock]),
         * when they changed or the line ended.
         */
        fun feed(samples: FloatArray, rate: Int, clock: WordClock): Heard? {
            stream.acceptWaveform(samples, rate)
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
                clock.stepped()
            }
            val result = recognizer.getResult(stream)
            val words = clock.words(result.tokens, result.timestamps)
            val now = CaptionText.join(words)
            if (recognizer.isEndpoint(stream)) {
                recognizer.reset(stream)
                clock.lineEnded()
                last = ""
                return if (words.isNotEmpty()) Heard(words, ended = true) else null
            }
            if (now == last) return null
            last = now
            return Heard(words, ended = false)
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
            if (!queue.offer(Chunk(mono.first, mono.second, atUs = inputUs))) dropped++
        }
    }

    companion object {
        /** About 10 seconds of sound waiting at most. */
        private const val QUEUE = 512

        /**
         * "In time with the speech": how far ahead of what you hear the captions hear the sound
         * (ExoPlayer's audio output holds this much; the picture follows the sound as always). The
         * engine needs up to 1.4 seconds, plus its working time; ExoPlayer starts a channel with
         * 2.5 seconds loaded, so this never holds up the start.
         */
        const val HEAD_START_US = 2_000_000
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
 * A word of auto captions; [atUs]: when it's heard, in the audio output's timeline ([NOW]: show
 * it at once).
 */
data class Word(val text: String, val atUs: Long = NOW) {
    companion object {
        const val NOW = Long.MIN_VALUE

        /** [text]'s words, to show at once. */
        fun of(text: String): List<Word> = text.split(' ', '\n', '\t').filter { it.isNotEmpty() }.map { Word(it) }
    }
}

/**
 * Turns the speech engine's word times into the moments they're heard ([Word.atUs]). The engine
 * counts time in the sound it was given, from the start of each line: after a pause it starts a
 * new line at the end of the last step it worked on ([chunkSeconds] each, see [stepped]). The
 * sound came in pieces, each with the moment its first sample is heard ([fed]).
 */
class WordClock(private val chunkSeconds: Double) {
    private class Mark(val fedSec: Double, val us: Long)

    private val marks = ArrayDeque<Mark>()
    private var fedSec = 0.0
    private var lineStartSec = 0.0
    private var lineSteps = 0

    /** The engine starts afresh (a new channel, a jump). */
    fun restart() {
        marks.clear()
        fedSec = 0.0
        lineStartSec = 0.0
        lineSteps = 0
    }

    /** [seconds] of sound given to the engine, its first sample heard at [atUs] ([Word.NOW]: not known). */
    fun fed(seconds: Double, atUs: Long) {
        if (marks.lastOrNull()?.us != Word.NOW || atUs != Word.NOW) marks.addLast(Mark(fedSec, atUs))
        while (marks.size > MAX_MARKS) marks.removeFirst()
        fedSec += seconds
    }

    /** The engine worked on one more step of the line. */
    fun stepped() {
        lineSteps++
    }

    /** A pause ended the line: the next one starts after the steps already worked on. */
    fun lineEnded() {
        lineStartSec += lineSteps * chunkSeconds
        lineSteps = 0
        // What's before the new line isn't needed any more (the mark covering its start stays).
        while (marks.size > 1 && marks[1].fedSec <= lineStartSec) marks.removeFirst()
    }

    /** When the sound [lineSec] seconds into the current line is heard ([Word.NOW]: not known). */
    fun at(lineSec: Double): Long {
        val t = lineStartSec + lineSec
        val m = marks.lastOrNull { it.fedSec <= t + 1e-6 } ?: return Word.NOW
        if (m.us == Word.NOW) return Word.NOW
        return m.us + ((t - m.fedSec) * 1_000_000).toLong()
    }

    /**
     * The engine's tokens as words: a token starting with a space starts a word, the rest (word
     * pieces, punctuation) join it; each word is heard when its first token is.
     */
    fun words(tokens: Array<String>, times: FloatArray): List<Word> {
        val out = ArrayList<Word>()
        val text = StringBuilder()
        var at = Word.NOW
        for (i in tokens.indices) {
            val token = tokens[i]
            if (token.startsWith(" ") && text.isNotBlank()) {
                out += Word(text.toString().trim(), at)
                text.setLength(0)
            }
            if (text.isBlank()) at = if (i < times.size) at(times[i].toDouble()) else Word.NOW
            text.append(token)
        }
        if (text.isNotBlank()) out += Word(text.toString().trim(), at)
        return out
    }

    private companion object {
        /** About 45 seconds of sound in pieces. */
        const val MAX_MARKS = 2000
    }
}

/**
 * What to show, broadcast style: the words heard, the line said before kept until new words push
 * it up. [partial] and [finish] give all of them; [showing] picks those heard by now and
 * [rollUp] fits them to the screen (the last lines, each as wide as fits), so the player can fit
 * them again when the lines or the size change.
 */
class CaptionText {
    private var before: List<Word> = emptyList()
    private var current: List<Word> = emptyList()

    fun clear() {
        before = emptyList()
        current = emptyList()
    }

    /** The words so far in the line being spoken (with the line before); null when there are none. */
    fun partial(words: List<Word>): List<Word>? {
        current = words
        return shown()
    }

    /** The line was finished (a pause, or long enough). */
    fun finish(words: List<Word>): List<Word>? {
        before = words
        current = emptyList()
        return shown()
    }

    fun partial(text: String): String? = partial(Word.of(text))?.let { join(it) }

    fun finish(text: String): String? = finish(Word.of(text))?.let { join(it) }

    private fun shown(): List<Word>? = (before + current).ifEmpty { null }

    companion object {
        fun join(words: List<Word>): String = words.joinToString(" ") { it.text }

        /**
         * The words heard by [nowUs] (all of them when it's null: not in time with the speech),
         * and when the next one is heard (null: none waiting).
         */
        fun showing(words: List<Word>, nowUs: Long?): Pair<List<Word>, Long?> {
            if (nowUs == null) return words to null
            val heard = words.takeWhile { it.atUs == Word.NOW || it.atUs <= nowUs }
            return heard to words.getOrNull(heard.size)?.atUs
        }

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
