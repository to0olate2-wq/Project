package app.falak.player.sync

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

/** A tiny HTTP/1.1 reader and writer for the app's servers on the home network (one request per connection). */
internal object MiniHttp {
    class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: ByteArray) {
        /** A cookie sent by the browser. */
        fun cookie(name: String): String? = headers["cookie"]?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
    }

    fun read(input: InputStream, maxBody: Int): Request? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > maxBody) throw IOException("Too big")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val target = parts[1]
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        return Request(parts[0].uppercase(), target.substringBefore('?'), query, headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 8192) throw IOException("Line too long")
        }
        return buf.toString("UTF-8")
    }

    fun send(out: OutputStream, status: Int, type: String, body: ByteArray, extra: Map<String, String> = emptyMap()) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            429 -> "Too Many Requests"
            else -> "Error"
        }
        val head = StringBuilder()
            .append("HTTP/1.1 $status $reason\r\n")
            .append("Content-Type: $type\r\n")
            .append("Content-Length: ${body.size}\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("Connection: close\r\n")
        for ((k, v) in extra) head.append("$k: $v\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray())
        out.write(body)
        out.flush()
    }
}
