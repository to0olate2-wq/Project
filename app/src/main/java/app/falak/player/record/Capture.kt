package app.falak.player.record

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A file a capture writes into (a recording). */
internal class Sink(val file: File) {
    private var out: BufferedOutputStream? = BufferedOutputStream(FileOutputStream(file, true), 256 * 1024)

    /** Bytes in the file so far. */
    @Volatile var bytes: Long = file.length()
        private set

    /** Why it stopped taking more (the storage is full, say), if it did. */
    @Volatile var error: String? = null
        private set

    /** fMP4 parts need their header at the start of the file. */
    @Volatile var hasHeader = false

    @Synchronized
    fun write(b: ByteArray, off: Int, len: Int) {
        val o = out ?: return
        try {
            o.write(b, off, len)
            bytes += len
        } catch (e: IOException) {
            error = "Couldn't save any more: " + (e.message ?: "the storage refused")
            close()
        }
    }

    @Synchronized
    fun flush() {
        runCatching { out?.flush() }
    }

    /** Starts the file again from nothing (a replay the provider sends again from its start). */
    @Synchronized
    fun restart() {
        runCatching { out?.close() }
        file.delete()
        bytes = 0
        hasHeader = false
        out = BufferedOutputStream(FileOutputStream(file, false), 256 * 1024)
    }

    @Synchronized
    fun close() {
        runCatching { out?.flush() }
        runCatching { out?.close() }
        out = null
    }

    val isOpen: Boolean get() = out != null
}

/**
 * One channel (or replay) read from the provider over a single connection, as it plays: a plain
 * stream (MPEG-TS) or HLS parts (MPEG-TS or fMP4, AES-128 too). What arrives goes into the
 * [ring] (pause and rewind) and into each [Sink] (recordings). A dropped connection is opened
 * again. Live captures run until [stop]; a [finite] one (a replay) ends with what the provider sends.
 */
internal class Capture(
    val id: String,
    val url: String,
    private val userAgent: String,
    private val referrer: String,
    http: OkHttpClient,
    ringDir: File?,
    val finite: Boolean = false,
    failFast: Boolean = false,
) {
    /** The player is waiting for it: give up at the first refusal so it can play the channel directly. */
    @Volatile var failFast: Boolean = failFast

    enum class State { STARTING, RUNNING, ENDED, FAILED }

    class Refused(why: String) : IOException(why)
    class Unsupported(why: String) : IOException(why)

    private val http: OkHttpClient = http.newBuilder()
        .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    @Volatile var state = State.STARTING
        private set
    @Volatile var problem: String? = null
        private set
    val ring: Ring? = ringDir?.let { Ring(it) }

    /** fMP4 parts: [header] goes before them (in every file and every read of the buffer). */
    @Volatile var mp4 = false
        private set
    @Volatile var header: ByteArray? = null
        private set
    @Volatile var startedAt = 0L
        private set
    @Volatile var lastDataAt = 0L
        private set

    /** Bytes received since it started (all connections). */
    @Volatile var received = 0L
        private set

    /** How much the buffer keeps: this long, at most this many bytes. */
    @Volatile var keepMs = 60_000L
    @Volatile var maxBytes = 1L shl 30

    private val sinks = CopyOnWriteArrayList<Sink>()
    private val writeLock = Any()
    @Volatile private var stopped = false
    @Volatile private var call: Call? = null
    private val scan = TsScan()
    private var lastMarkAt = 0L
    private var lastMarked = -1L
    private var lastTrimAt = 0L
    private var currentMap: String? = null
    private var thread: Thread? = null

    /** A replay whose connection dropped: the bytes it had, to carry on from there. */
    private var resumeFrom = 0L

    val stopping: Boolean get() = stopped

    fun start() {
        startedAt = System.currentTimeMillis()
        thread = Thread({ run() }, "capture-$id").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        stopped = true
        call?.cancel()
        thread?.interrupt()
    }

    /** Stopped for good (or never could start): the buffer has all there is. */
    val done: Boolean get() = state == State.ENDED || state == State.FAILED || (stopped && thread?.isAlive != true)

    /**
     * Starts saving into [sink]; with [from] (a position in the buffer), what's kept since then
     * goes in first, so a recording can begin a little in the past.
     */
    fun addSink(sink: Sink, from: Long? = null) {
        val r = ring
        if (from != null && r != null && sink.bytes == 0L) {
            // Most of it without holding the stream up, the last few bytes with it held.
            header?.let { h -> sink.write(h, 0, h.size); sink.hasHeader = true }
            val counted = object : java.io.OutputStream() {
                override fun write(b: Int) = sink.write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) = sink.write(b, off, len)
            }
            val upTo = r.copyTo(from, counted)
            synchronized(writeLock) {
                r.copyTo(upTo, counted)
                sinks += sink
            }
        } else {
            synchronized(writeLock) {
                header?.let { h -> if (!sink.hasHeader && sink.bytes == 0L) { sink.write(h, 0, h.size); sink.hasHeader = true } }
                sinks += sink
            }
        }
    }

    fun removeSink(sink: Sink) {
        synchronized(writeLock) { sinks -= sink }
        sink.close()
    }

    val sinkCount: Int get() = sinks.size

    private fun run() {
        var attempts = 0
        var sinceLast = received
        try {
            while (!stopped) {
                try {
                    if (looksLikeHls(url)) runHls(null, url) else runPlain()
                } catch (e: Refused) {
                    problem = e.message
                    if (received == 0L) return fail(e.message ?: "Your provider refused it")
                } catch (e: Unsupported) {
                    return fail(e.message ?: "This channel can't be saved")
                } catch (e: IOException) {
                    if (stopped) break
                    problem = e.message ?: "The connection dropped"
                } catch (e: InterruptedException) {
                    break
                }
                if (stopped || state == State.ENDED) break
                // It dropped: again, waiting longer after each try that brought nothing.
                if (received > sinceLast) attempts = 0 else attempts++
                sinceLast = received
                if (received == 0L && (failFast || attempts >= 3)) return fail(problem ?: "Nothing came from the provider")
                if (failFast && attempts >= 6) return fail(problem ?: "The connection keeps dropping")
                try {
                    Thread.sleep(minOf(10_000L, 1_000L shl minOf(attempts, 4)))
                } catch (e: InterruptedException) {
                    break
                }
            }
        } catch (e: Throwable) {
            if (!stopped) fail(e.message ?: e.javaClass.simpleName)
        } finally {
            if (state != State.FAILED && state != State.ENDED) state = if (received > 0) State.ENDED else State.FAILED
            ring?.close()
            sinks.forEach { it.flush() }
        }
    }

    private fun fail(why: String) {
        problem = why
        state = State.FAILED
    }

    private fun looksLikeHls(u: String): Boolean = u.substringBefore('?').lowercase().let { it.endsWith(".m3u8") || it.endsWith(".m3u") }

    private fun request(u: String, range: Long = 0): Request = Request.Builder()
        .url(u)
        .header("User-Agent", userAgent)
        .apply {
            if (referrer.isNotBlank()) header("Referer", referrer)
            if (range > 0) header("Range", "bytes=$range-")
        }
        .build()

    private fun refusal(code: Int): Boolean = code == 401 || code == 403 || code == 404 || code == 405 || code == 410 ||
        code == 451 || code == 456 || code == 458 || code == 509 || code == 884

    // ---- a plain stream ----

    private fun runPlain() {
        val from = if (finite) resumeFrom else 0L
        val c = http.newCall(request(url, from))
        call = c
        c.execute().use { response ->
            if (!response.isSuccessful) {
                if (refusal(response.code)) throw Refused("Your provider refused it (${response.code})")
                throw IOException("The provider answered ${response.code}")
            }
            val type = response.header("Content-Type").orEmpty().lowercase()
            if ("dash+xml" in type) throw Unsupported("This channel streams in a way that can't be saved")
            val body = response.body ?: throw IOException("Nothing came")
            val input = body.byteStream()
            if ("mpegurl" in type) {
                runHls(body.string(), response.request.url.toString())
                return
            }
            // A replay sent again from its start (the provider can't carry on from the middle).
            if (finite && from > 0 && response.code != 206) {
                resumeFrom = 0
                sinks.forEach { it.restart() }
            }
            scan.reset()
            val buf = ByteArray(64 * 1024)
            var first = true
            while (!stopped) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                if (first) {
                    first = false
                    // A list of parts (HLS) labelled as a video.
                    val head = String(buf, 0, minOf(n, 16), Charsets.ISO_8859_1)
                    if (Hls.isPlaylist(head)) {
                        val rest = input.readBytes()
                        runHls(String(buf, 0, n, Charsets.UTF_8) + String(rest, Charsets.UTF_8), response.request.url.toString())
                        return
                    }
                }
                deliver(buf, 0, n, segmentStart = false)
                if (finite) resumeFrom += n
            }
            if (finite && !stopped) state = State.ENDED
            // A live stream that ends: the provider closed it (run opens it again).
            if (!finite && !stopped) throw IOException("The provider closed the stream")
        }
    }

    // ---- HLS ----

    private fun text(u: String): Pair<String, String> {
        val c = http.newCall(request(u))
        call = c
        c.execute().use { r ->
            if (!r.isSuccessful) {
                if (refusal(r.code)) throw Refused("Your provider refused it (${r.code})")
                throw IOException("The provider answered ${r.code}")
            }
            return (r.body?.string().orEmpty()) to r.request.url.toString()
        }
    }

    private fun bytes(u: String): ByteArray? {
        val c = http.newCall(request(u))
        call = c
        c.execute().use { r ->
            if (r.code == 404 || r.code == 410) return null // a part already gone: skip it
            if (!r.isSuccessful) {
                if (refusal(r.code)) throw Refused("Your provider refused it (${r.code})")
                throw IOException("The provider answered ${r.code}")
            }
            return r.body?.bytes()
        }
    }

    private val keys = HashMap<String, ByteArray>()

    private fun runHls(given: String?, at: String) {
        var (list, base) = if (given != null) given to at else text(at)
        if (!Hls.isPlaylist(list)) throw IOException("The provider didn't send a list of parts")
        if (Hls.isMaster(list)) {
            val v = Hls.pick(Hls.parseMaster(list, base)) ?: throw Unsupported("This channel's sound comes separately, so it can't be saved")
            val t = text(v.uri)
            list = t.first
            base = t.second
        }
        val mediaUrl = base
        var lastSeq = -1L
        var lastNewAt = System.currentTimeMillis()
        while (!stopped) {
            val media = Hls.parseMedia(list, base)
            val segs = media.segments
            val todo = when {
                segs.isEmpty() -> emptyList()
                lastSeq < 0 -> if (finite || media.ended) segs else segs.takeLast(3)
                // The provider numbered its parts afresh: carry on from its newest.
                segs.last().sequence < lastSeq -> segs.takeLast(2)
                else -> segs.filter { it.sequence > lastSeq }
            }
            for (s in todo) {
                if (stopped) return
                segment(s)
                lastSeq = s.sequence
                lastNewAt = System.currentTimeMillis()
            }
            if (media.ended) {
                if (finite) state = State.ENDED else throw IOException("The channel's list of parts ended")
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastNewAt > 3 * media.targetMs + 15_000) throw IOException("The channel stopped sending")
            Thread.sleep(if (todo.isEmpty()) (media.targetMs / 2).coerceIn(1_000, 5_000) else (media.targetMs / 3).coerceIn(500, 3_000))
            val t = text(mediaUrl)
            list = t.first
            base = t.second
        }
    }

    private fun segment(s: Hls.Segment) {
        if (s.map != null && s.map != currentMap) {
            val h = bytes(s.map) ?: throw IOException("The stream's header is missing")
            currentMap = s.map
            val wasKnown = header != null
            header = h
            mp4 = true
            synchronized(writeLock) {
                for (sink in sinks) {
                    if (!sink.hasHeader && sink.bytes == 0L) {
                        sink.write(h, 0, h.size)
                        sink.hasHeader = true
                    }
                }
            }
            // A new header part-way (rare): it goes in the stream too.
            if (wasKnown) deliver(h, 0, h.size, segmentStart = true)
        }
        var data = bytes(s.uri) ?: return
        val key = s.key
        if (key != null) {
            if (key.method != "AES-128" || key.uri == null) throw Unsupported("This channel is protected and can't be saved")
            val k = keys.getOrPut(key.uri) { bytes(key.uri) ?: throw IOException("The stream's key is missing") }
            data = try {
                Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(Hls.iv(key, s.sequence)))
                    doFinal(data)
                }
            } catch (e: Exception) {
                throw Unsupported("This channel is protected and can't be saved")
            }
        }
        deliver(data, 0, data.size, segmentStart = true)
    }

    // ---- what arrived ----

    private fun deliver(b: ByteArray, off: Int, len: Int, segmentStart: Boolean) {
        val now = System.currentTimeMillis()
        val r = ring
        synchronized(writeLock) {
            val at = r?.end ?: received
            r?.append(b, off, len)
            if (r != null) {
                if (segmentStart) {
                    r.mark(now, at)
                    lastMarked = at
                    lastMarkAt = now
                } else if (!mp4) {
                    scan.feed(b, off, len, at)
                    val key = scan.lastKey
                    val packet = scan.lastPacket
                    if (now - lastMarkAt >= 1_000 && key > lastMarked) {
                        r.mark(now, key)
                        lastMarked = key
                        lastMarkAt = now
                    } else if (now - lastMarkAt >= 3_000 && packet > lastMarked) {
                        // No key frames flagged in this stream: a packet start will do.
                        r.mark(now, packet)
                        lastMarked = packet
                        lastMarkAt = now
                    }
                }
            }
            for (sink in sinks) sink.write(b, off, len)
            received += len
            lastDataAt = now
            if (state == State.STARTING) state = State.RUNNING
        }
        if (r != null && now - lastTrimAt > 2_000) {
            lastTrimAt = now
            r.trim(keepMs, maxBytes, now)
        }
    }
}
