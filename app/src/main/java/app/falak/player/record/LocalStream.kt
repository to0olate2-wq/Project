package app.falak.player.record

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * The player reads live TV from the buffer, and a recording still being made, through this:
 * a small web server on the device itself (127.0.0.1, which nothing outside the device can
 * reach), with a random key in every address. Both players (ExoPlayer and VLC) play its
 * addresses like any other stream. Everything is sent in pieces ("chunked"), so when a capture
 * fails the player sees the connection drop (an error it recovers from) rather than an ending.
 */
internal object LocalStream {
    private val token: String = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    @Volatile private var server: ServerSocket? = null
    private val captures = ConcurrentHashMap<String, Capture>()

    /** A recording being made: its file, and whether more is still coming. */
    class Growing(val file: File, val more: () -> Boolean)

    private val files = ConcurrentHashMap<String, Growing>()

    @Synchronized
    private fun port(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = s
        Thread({ accept(s) }, "local-stream").apply {
            isDaemon = true
            start()
        }
        return s.localPort
    }

    fun register(c: Capture) {
        captures[c.id] = c
    }

    fun unregister(c: Capture) {
        captures.remove(c.id, c)
    }

    /** The buffer of [c] from [offset] (a starting point the buffer marked). */
    fun urlFor(c: Capture, offset: Long): String {
        register(c)
        return "http://127.0.0.1:${port()}/b/$token/${c.id}/$offset"
    }

    /** A recording being made, from its start, carrying on as it grows. */
    fun urlForFile(id: String, g: Growing): String {
        files[id] = g
        return "http://127.0.0.1:${port()}/f/$token/$id"
    }

    fun forgetFile(id: String) {
        files.remove(id)
    }

    private fun accept(s: ServerSocket) {
        while (!s.isClosed) {
            val socket = try {
                s.accept()
            } catch (e: IOException) {
                break
            }
            Thread({ serve(socket) }, "local-stream-client").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use { sock ->
            try {
                sock.tcpNoDelay = true
                val req = app.falak.player.sync.MiniHttp.read(sock.getInputStream(), 0) ?: return
                val parts = req.path.trim('/').split('/')
                val out = sock.getOutputStream()
                if (parts.size < 3 || parts[1] != token) {
                    app.falak.player.sync.MiniHttp.send(out, 404, "text/plain", "Not here".toByteArray())
                    return
                }
                // "Range: bytes=N-": the player carrying on after a hiccup.
                val skip = req.headers["range"]?.removePrefix("bytes=")?.substringBefore('-')?.trim()?.toLongOrNull() ?: 0L
                when (parts[0]) {
                    "b" -> {
                        val c = captures[parts[2]]
                        val offset = parts.getOrNull(3)?.toLongOrNull()
                        if (c == null || offset == null || c.state == Capture.State.FAILED) {
                            app.falak.player.sync.MiniHttp.send(out, 404, "text/plain", "Gone".toByteArray())
                            return
                        }
                        streamBuffer(c, offset, skip, req.method == "HEAD", out)
                    }
                    "f" -> {
                        val g = files[parts[2]]
                        if (g == null || !g.file.exists()) {
                            app.falak.player.sync.MiniHttp.send(out, 404, "text/plain", "Gone".toByteArray())
                            return
                        }
                        streamFile(g, skip, req.method == "HEAD", out)
                    }
                    else -> app.falak.player.sync.MiniHttp.send(out, 404, "text/plain", "Not here".toByteArray())
                }
            } catch (e: IOException) {
                // the player let go
            } catch (e: InterruptedException) {
                // closing
            }
        }
    }

    private fun head(out: OutputStream, partial: Boolean, type: String) {
        val h = StringBuilder()
            .append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            .append("Content-Type: ").append(type).append("\r\n")
            .append("Transfer-Encoding: chunked\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("Connection: close\r\n\r\n")
        out.write(h.toString().toByteArray())
        out.flush()
    }

    private fun chunk(out: OutputStream, b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        out.write((Integer.toHexString(len) + "\r\n").toByteArray())
        out.write(b, off, len)
        out.write("\r\n".toByteArray())
    }

    private fun finish(out: OutputStream) {
        out.write("0\r\n\r\n".toByteArray())
        out.flush()
    }

    private fun streamBuffer(c: Capture, offset: Long, skipIn: Long, headOnly: Boolean, out: OutputStream) {
        val ring = c.ring ?: throw IOException("No buffer")
        head(out, skipIn > 0, if (c.mp4) "video/mp4" else "video/mp2t")
        if (headOnly) return finish(out)
        var skip = skipIn
        // fMP4: the header first, then the parts.
        val header = if (c.mp4) c.header else null
        if (header != null && skip < header.size) {
            chunk(out, header, skip.toInt(), header.size - skip.toInt())
            skip = 0
        } else if (header != null) {
            skip -= header.size
        }
        var pos = offset + skip
        val buf = ByteArray(64 * 1024)
        var idle = 0L
        while (true) {
            val n = try {
                ring.read(pos, buf, 0, buf.size, 1_000)
            } catch (e: Ring.Trimmed) {
                // Paused so long that this part was dropped: on from the oldest part kept.
                pos = ring.offsetAt(0)
                continue
            }
            when {
                n > 0 -> {
                    chunk(out, buf, 0, n)
                    pos += n
                    idle = 0
                    if (ring.end - pos < buf.size) out.flush()
                }
                n < 0 -> {
                    // The capture ended: a replay that's complete ends here; a live one failed, so
                    // the connection just drops (the player tries again, or plays the channel directly).
                    if (c.finite && c.state == Capture.State.ENDED) finish(out)
                    return
                }
                else -> {
                    out.flush()
                    idle += 1_000
                    // Nothing for a long while: let go, the player will ask again.
                    if (idle > 40_000) return
                }
            }
        }
    }

    private fun streamFile(g: Growing, skip: Long, headOnly: Boolean, out: OutputStream) {
        head(out, skip > 0, if (g.file.name.endsWith(".mp4")) "video/mp4" else "video/mp2t")
        if (headOnly) return finish(out)
        var pos = skip
        val buf = ByteArray(64 * 1024)
        RandomAccessFile(g.file, "r").use { f ->
            while (true) {
                val length = g.file.length()
                if (pos < length) {
                    f.seek(pos)
                    val n = f.read(buf, 0, minOf(buf.size.toLong(), length - pos).toInt())
                    if (n > 0) {
                        chunk(out, buf, 0, n)
                        pos += n
                        continue
                    }
                }
                if (!g.more()) {
                    if (pos >= g.file.length()) {
                        finish(out)
                        return
                    }
                    continue
                }
                out.flush()
                Thread.sleep(500)
            }
        }
    }
}
