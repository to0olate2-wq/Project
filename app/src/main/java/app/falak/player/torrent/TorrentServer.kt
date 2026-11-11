package app.falak.player.torrent

import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/** A file that is still arriving (a torrent's film), as the local server sees it. */
interface GrowingFile {
    val name: String
    val size: Long

    /** Where its bytes are written now: a new file each time [generation] changes. */
    val path: File

    /** Goes up when the file is started afresh (to make room): what was read from the old [path] no longer counts. */
    val generation: Int

    /** How many bytes from [offset] are on disk in one run (0 when the byte at [offset] isn't there yet). */
    fun readyFrom(offset: Long): Long

    /** The player is reading at [offset] ([waiting]: and has nothing to read there): what comes next there is fetched first. */
    fun want(offset: Long, waiting: Boolean)
}

/**
 * Serves a [GrowingFile] to the player on this device only (127.0.0.1, a random port and a random
 * word in the link, like the live buffer's LocalStream). The player asks for any part of the film
 * (it jumps to the end for the index, and wherever you seek); a part that hasn't arrived yet is
 * waited for, and asked for first. A part that doesn't come within [waitMs] drops the connection
 * (never a short "finished" answer), so the player tries again rather than stopping the film early.
 */
class TorrentServer(private val file: GrowingFile, private val waitMs: Long = 50_000) : Closeable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val token = java.util.UUID.randomUUID().toString().replace("-", "")
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "torrent-http").apply { isDaemon = true } }
    private val open = java.util.Collections.synchronizedSet(HashSet<Socket>())

    @Volatile
    private var closed = false

    /** How much the player has taken so far, and in how many requests (shown while it plays, to tell where a film is stuck). */
    val served = java.util.concurrent.atomic.AtomicLong()
    val requests = java.util.concurrent.atomic.AtomicInteger()

    /** The link the player opens (the file's ending tells it the kind of file). */
    val url: String
        get() = "http://127.0.0.1:${server.localPort}/$token/video" + extension()

    private fun extension(): String {
        val ext = file.name.substringAfterLast('.', "").lowercase()
        return if (ext.length in 2..4 && ext.all { it.isLetterOrDigit() }) ".$ext" else ""
    }

    init {
        Thread({
            while (!closed) {
                val socket = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                open.add(socket)
                pool.execute {
                    try {
                        socket.use { handle(it) }
                    } catch (e: Exception) {
                        // The player closed the connection (a seek, or it stopped): nothing to do.
                    } finally {
                        open.remove(socket)
                    }
                }
            }
        }, "torrent-http-accept").apply { isDaemon = true }.start()
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 15_000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        val request = reader.readLine()?.split(' ') ?: return
        var range: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("range:", ignoreCase = true)) range = line.substring(6).trim()
        }
        val out = socket.getOutputStream()
        val method = request.getOrNull(0).orEmpty()
        val path = request.getOrNull(1).orEmpty()
        if (!path.startsWith("/$token/")) return answer(out, "404 Not Found")
        if (method != "GET" && method != "HEAD") return answer(out, "405 Method Not Allowed")
        requests.incrementAndGet()

        val size = file.size
        var start = 0L
        var end = size - 1
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.substring(6).substringBefore(',').trim()
            val from = spec.substringBefore('-').trim()
            val to = spec.substringAfter('-', "").trim()
            if (from.isEmpty()) {
                // "bytes=-500": the last 500 bytes.
                val n = to.toLongOrNull() ?: return answer(out, "416 Range Not Satisfiable", "Content-Range: bytes */$size")
                start = (size - n).coerceAtLeast(0)
            } else {
                start = from.toLongOrNull() ?: return answer(out, "416 Range Not Satisfiable", "Content-Range: bytes */$size")
                if (to.isNotEmpty()) end = (to.toLongOrNull() ?: end).coerceAtMost(size - 1)
            }
            if (start >= size || start > end) return answer(out, "416 Range Not Satisfiable", "Content-Range: bytes */$size")
        } else {
            range = null
        }
        val head = StringBuilder()
        head.append(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        head.append("Content-Type: ").append(contentType()).append("\r\n")
        head.append("Content-Length: ").append(end - start + 1).append("\r\n")
        head.append("Accept-Ranges: bytes\r\n")
        if (range != null) head.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(size).append("\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
        if (method == "HEAD") return

        val buffer = ByteArray(64 * 1024)
        var pos = start
        var waited = 0L
        var told = -1L
        var raf: RandomAccessFile? = null
        var rafGeneration = -1
        try {
            while (pos <= end && !closed) {
                // Keeps what comes next at the front of the queue as the film plays on.
                if (told < 0 || pos - told >= TELL_EVERY) {
                    file.want(pos, waiting = false)
                    told = pos
                }
                val generation = file.generation
                val ready = file.readyFrom(pos)
                var got = 0
                if (ready > 0 && file.generation == generation) {
                    if (rafGeneration != generation) {
                        runCatching { raf?.close() }
                        raf = runCatching { RandomAccessFile(file.path, "r") }.getOrNull()
                        rafGeneration = if (raf != null) generation else -1
                    }
                    val f = raf
                    if (f != null) {
                        val n = minOf(ready, end - pos + 1, buffer.size.toLong()).toInt()
                        f.seek(pos)
                        got = f.read(buffer, 0, n)
                        // Started afresh meanwhile: what was read may be from the new, still empty file.
                        if (file.generation != generation) got = 0
                    }
                }
                if (got <= 0) {
                    file.want(pos, waiting = true)
                    // (Dropped, not ended: see the class comment.)
                    if (waited >= waitMs) return
                    Thread.sleep(POLL_MS)
                    waited += POLL_MS
                    continue
                }
                waited = 0
                out.write(buffer, 0, got)
                served.addAndGet(got.toLong())
                pos += got
            }
            out.flush()
        } finally {
            runCatching { raf?.close() }
        }
    }

    private fun contentType(): String = when (file.name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "ts", "m2ts" -> "video/mp2t"
        "mov" -> "video/quicktime"
        else -> "application/octet-stream"
    }

    private fun answer(out: OutputStream, status: String, header: String? = null) {
        val text = "HTTP/1.1 $status\r\n" + (if (header != null) "$header\r\n" else "") + "Content-Length: 0\r\nConnection: close\r\n\r\n"
        out.write(text.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        synchronized(open) { open.toList() }.forEach { runCatching { it.close() } }
        pool.shutdownNow()
    }

    private companion object {
        const val POLL_MS = 150L
        const val TELL_EVERY = 1024L * 1024
    }
}
