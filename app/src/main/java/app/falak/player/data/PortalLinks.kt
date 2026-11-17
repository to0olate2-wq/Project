package app.falak.player.data

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A MAC portal's links last a short while and are asked for at each play ([PortalApi.playLink]).
 * So that every player in the app (the main one, two screens, the mini player, previews, recordings,
 * downloads) can play a portal's channel like any other, its channels carry a link to this small
 * server on the device (127.0.0.1 only, a secret token in every link): asked for one, it asks the
 * portal for a fresh link and sends the player on to it (an HTTP redirect).
 *
 * The port and token are kept (SharedPreferences "portal_links") so saved lists stay right; when
 * the port is taken, a new one is used and saved lists are mended as they are read ([fix]).
 */
object PortalLinks {
    private const val PREFS = "portal_links"

    /** Where links go now: "http://127.0.0.1:port/token". */
    @Volatile var base: String = ""
        private set

    @Volatile private var server: ServerSocket? = null

    /** Asks the portal (playlist id, module, episode, command) for a link to play now. */
    @Volatile var resolve: ((String, String, Int, String) -> String)? = null

    /** "Still here" for one portal (some stop streams without it). */
    @Volatile var keepAlive: ((String) -> Unit)? = null

    /** A link made in the last 20 s is given again (players ask twice; HLS asks every few seconds). */
    private val recent = ConcurrentHashMap<String, Pair<Long, String>>()

    /** Playlist id → when a link was last made for it (the "still here" call runs for 3 h after). */
    private val used = ConcurrentHashMap<String, Long>()

    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "portal-links").apply { isDaemon = true } }

    /** Starts the server once (it lives as long as the app). */
    fun ensure(context: Context) {
        if (server != null) return
        synchronized(this) {
            if (server != null) return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val token = prefs.getString("token", null) ?: Portal.newToken().lowercase().also { prefs.edit().putString("token", it).apply() }
            val wanted = prefs.getInt("port", 0)
            val loopback = InetAddress.getByName("127.0.0.1")
            val socket = (if (wanted > 0) runCatching { ServerSocket(wanted, 50, loopback) }.getOrNull() else null)
                ?: ServerSocket(0, 50, loopback)
            if (socket.localPort != wanted) prefs.edit().putInt("port", socket.localPort).apply()
            server = socket
            base = "http://127.0.0.1:${socket.localPort}/$token"
            Thread({ serve(socket) }, "portal-links-accept").apply { isDaemon = true }.start()
            Thread({ watchdog() }, "portal-links-alive").apply { isDaemon = true }.start()
        }
    }

    /** The link a portal item is played with. */
    fun link(playlistId: String, module: String, series: Int, cmd: String, ext: String): String =
        "$base/$playlistId/$module/$series/${encode(cmd)}.$ext"

    /** A link saved with an older port or token, made right ("" and other links as they are). */
    fun fix(url: String): String {
        if (base.isEmpty() || url.startsWith(base) || !url.startsWith("http://127.0.0.1:")) return url
        val parts = url.removePrefix("http://").split('/', limit = 3)
        if (parts.size < 3) return url
        return "$base/${parts[2]}"
    }

    private const val B64 = android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING

    fun encode(cmd: String): String = android.util.Base64.encodeToString(cmd.toByteArray(), B64)

    fun decode(s: String): String = String(android.util.Base64.decode(s, B64))

    /** What a link asks for: playlist id, module, episode, command; null when it isn't one of ours. */
    class Target(val playlistId: String, val module: String, val series: Int, val cmd: String)

    fun parse(path: String, token: String): Target? {
        val parts = path.substringBefore('?').trimStart('/').split('/')
        if (parts.size != 5 || parts[0] != token) return null
        val cmd = try {
            decode(parts[4].substringBeforeLast('.'))
        } catch (e: IllegalArgumentException) {
            return null
        }
        return Target(parts[1], parts[2], parts[3].toIntOrNull() ?: 0, cmd)
    }

    private fun serve(socket: ServerSocket) {
        while (!socket.isClosed) {
            val s = try {
                socket.accept()
            } catch (e: Exception) {
                break
            }
            workers.execute { answer(s) }
        }
    }

    private fun answer(s: Socket) {
        s.use { sock ->
            sock.soTimeout = 20_000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
            val line = reader.readLine() ?: return
            // The rest of the request (headers) isn't needed.
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
            }
            val bits = line.split(' ')
            val out = sock.getOutputStream()
            fun reply(status: String, extra: String = "", body: String = "") {
                val b = body.toByteArray()
                out.write(("HTTP/1.1 $status\r\n$extra" + "Content-Length: ${b.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                if (bits.firstOrNull() != "HEAD") out.write(b)
                out.flush()
            }
            val token = base.substringAfterLast('/')
            val target = if (bits.size >= 2 && (bits[0] == "GET" || bits[0] == "HEAD")) parse(bits[1], token) else null
            if (target == null) {
                reply("404 Not Found")
                return
            }
            val key = bits[1]
            val now = System.currentTimeMillis()
            try {
                val url = recent[key]?.takeIf { now - it.first < 20_000 }?.second
                    ?: (resolve ?: throw java.io.IOException("Not ready"))(target.playlistId, target.module, target.series, target.cmd)
                        .also { recent[key] = now to it }
                used[target.playlistId] = now
                reply("302 Found", "Location: $url\r\nCache-Control: no-store\r\n")
            } catch (e: Exception) {
                reply("502 Bad Gateway", "Content-Type: text/plain; charset=utf-8\r\n", e.message ?: "The portal gave no link")
            }
            if (recent.size > 200) recent.entries.removeIf { now - it.value.first > 60_000 }
        }
    }

    /** Every 2 minutes, "still here" to each portal used in the last 3 hours. */
    private fun watchdog() {
        while (true) {
            try {
                TimeUnit.SECONDS.sleep(120)
                val now = System.currentTimeMillis()
                for ((id, at) in used) {
                    if (now - at > 3 * 3_600_000L) {
                        used.remove(id)
                        continue
                    }
                    runCatching { keepAlive?.invoke(id) }
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                // try again in 2 minutes
            }
        }
    }
}
