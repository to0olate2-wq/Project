package app.falak.player.sync

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.view.KeyEvent
import app.falak.player.FreezeWatch
import app.falak.player.data.Channel
import app.falak.player.data.Kind
import app.falak.player.data.LibraryIndex
import app.falak.player.data.Program
import app.falak.player.data.Repository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** What the phone remote asks of the app's screens; MainActivity provides it while it exists. */
interface RemoteHandler {
    /** The open playlist and the groups not to show (hidden, locked, outside the Kids profile). */
    fun library(): Pair<LibraryIndex, Set<String>>?
    fun play(list: List<Channel>, ch: Channel)
    fun openSeries(ch: Channel)
    fun search(text: String)
    fun home()
}

/** Where the phone remote reaches the app (set by the activities as they come and go). */
object RemoteBridge {
    @Volatile var handler: RemoteHandler? = null
    @Volatile var activity: WeakReference<Activity>? = null
}

/**
 * The phone remote: a page on the home network (http://<tv>:8766) that a paired phone uses as a
 * remote control and keyboard: arrows and OK, channel up/down, play/pause, search typed on the
 * phone, and the playlist's channels, films and series to start on the TV with a tap. Phones pair
 * once by scanning the QR code in Settings → Phone remote; after that the page works whenever the
 * app is open on the TV. Nothing goes beyond the home network.
 */
class RemoteServer(private val context: Context, private val repo: Repository) {
    private val json = Json { ignoreUnknownKeys = true }
    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(3) { r -> Thread(r, "remote-http").apply { isDaemon = true } }
    private val wrongCodes = AtomicInteger(0)
    private val device = deviceName(context)
    private val random = SecureRandom()

    /** The code shown on the TV while Settings → Phone remote is open (null: no new phones). */
    @Volatile var pairCode: String? = null
        private set

    val port: Int get() = server?.localPort ?: 0
    val running: Boolean get() = server != null

    fun start() {
        if (server != null) return
        val s = try {
            ServerSocket(PORT)
        } catch (e: IOException) {
            try {
                ServerSocket(0)
            } catch (e2: IOException) {
                return
            }
        }
        server = s
        Thread({
            while (!s.isClosed) {
                val client = try {
                    s.accept()
                } catch (e: IOException) {
                    break
                }
                try {
                    pool.execute { serve(client) }
                } catch (e: Exception) {
                    client.close()
                }
            }
        }, "remote-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        try {
            server?.close()
        } catch (e: IOException) {
            // closing anyway
        }
        server = null
    }

    /** Settings → Phone remote opened: a fresh code for pairing a phone. */
    fun openPairing(): String {
        val code = String.format(java.util.Locale.US, "%06d", random.nextInt(1_000_000))
        wrongCodes.set(0)
        pairCode = code
        return code
    }

    fun closePairing() {
        pairCode = null
    }

    // ---------- HTTP ----------

    private fun serve(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 15_000
                val req = MiniHttp.read(BufferedInputStream(s.getInputStream()), 64 * 1024) ?: return
                route(req, s.getOutputStream())
            } catch (e: Exception) {
                // one bad request never stops the server
            }
        }
    }

    private fun sendJson(out: OutputStream, status: Int, text: String, extra: Map<String, String> = emptyMap()) =
        MiniHttp.send(out, status, "application/json; charset=utf-8", text.toByteArray(), extra)

    private fun error(out: OutputStream, status: Int, message: String) =
        sendJson(out, status, buildJsonObject { put("error", message) }.toString())

    private fun body(req: MiniHttp.Request): JsonObject? = try {
        json.parseToJsonElement(String(req.body)) as? JsonObject
    } catch (e: Exception) {
        null
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun route(req: MiniHttp.Request, out: OutputStream) {
        if (req.method == "GET" && (req.path == "/" || req.path == "/index.html")) {
            MiniHttp.send(out, 200, "text/html; charset=utf-8", RemotePage.html(device).toByteArray())
            return
        }
        if (req.method == "POST" && req.path == "/api/pair") {
            pair(req, out)
            return
        }
        // Everything else: only paired phones.
        val token = req.headers["x-remote"]?.takeIf { it.isNotBlank() } ?: req.cookie(COOKIE)
        val hash = token?.let { hash(it) }
        if (hash == null || repo.state.value.remotes.none { it.hash == hash }) {
            error(out, 403, "Pair this phone first: on the TV, open Settings → Phone remote")
            return
        }
        repo.touchRemote(hash)
        when ("${req.method} ${req.path}") {
            "GET /api/state" -> sendJson(out, 200, stateJson())
            "GET /api/groups" -> groupsJson(req.query["kind"])?.let { sendJson(out, 200, it) } ?: error(out, 404, "No playlist open on the TV")
            "GET /api/items" -> itemsJson(req.query["kind"], req.query["group"].orEmpty(), req.query["q"].orEmpty())
                ?.let { sendJson(out, 200, it) } ?: error(out, 404, "No playlist open on the TV")
            "POST /api/play" -> play(body(req)?.str("id").orEmpty(), out)
            "POST /api/key" -> {
                val code = KEYS[body(req)?.str("key")] ?: return error(out, 400, "Unknown key")
                pressKey(code)
                sendJson(out, 200, "{}")
            }
            "POST /api/text" -> {
                val text = body(req)?.str("text").orEmpty().take(80)
                val h = RemoteBridge.handler ?: return error(out, 409, "Open the app's lists on the TV")
                main { h.search(text) }
                sendJson(out, 200, "{}")
            }
            "POST /api/home" -> {
                val h = RemoteBridge.handler ?: return error(out, 409, "The TV isn't ready")
                main { h.home() }
                sendJson(out, 200, "{}")
            }
            else -> error(out, 404, "Not found")
        }
    }

    private fun pair(req: MiniHttp.Request, out: OutputStream) {
        if (wrongCodes.get() >= MAX_WRONG) return error(out, 429, "Too many wrong codes. Close and reopen Phone remote on the TV.")
        val o = body(req) ?: return error(out, 400, "Bad request")
        val code = pairCode ?: return error(out, 403, "On the TV, open Settings → Phone remote to pair a phone")
        if (o.str("code") != code) {
            wrongCodes.incrementAndGet()
            return error(out, 403, "Wrong code")
        }
        val token = ByteArray(24).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        repo.addRemote(hash(token), phoneLabel(req.headers["user-agent"].orEmpty()))
        FreezeWatch.note("phone remote paired")
        sendJson(
            out, 200,
            buildJsonObject { put("token", token) }.toString(),
            mapOf("Set-Cookie" to "$COOKIE=$token; Max-Age=31536000; Path=/; SameSite=Strict; HttpOnly"),
        )
    }

    private fun stateJson(): String {
        val now = repo.nowPlaying
        val lib = RemoteBridge.handler?.library()
        return buildJsonObject {
            put("device", device)
            put("playlist", repo.currentPlaylist?.name ?: repo.state.value.playlists.firstOrNull { it.id == repo.state.value.lastPlaylistId }?.name.orEmpty())
            put("ready", lib != null)
            if (now != null) {
                put("playing", now.first)
                put("sub", now.second)
            }
            if (lib != null) {
                put("live", lib.first.live.size)
                put("films", lib.first.movies.size)
                put("series", lib.first.series.size)
            }
        }.toString()
    }

    private fun kindOf(s: String?): Kind = when (s?.uppercase()) {
        "MOVIE", "FILMS" -> Kind.MOVIE
        "SERIES" -> Kind.SERIES
        else -> Kind.LIVE
    }

    private fun groupsJson(kind: String?): String? {
        val (idx, excluded) = RemoteBridge.handler?.library() ?: return null
        return buildJsonArray {
            for ((g, list) in idx.groupsOf(kindOf(kind))) {
                if (g in excluded) continue
                addJsonObject {
                    put("name", g)
                    put("count", list.size)
                }
            }
        }.toString()
    }

    private fun itemsJson(kind: String?, group: String, q: String): String? {
        val (idx, excluded) = RemoteBridge.handler?.library() ?: return null
        val k = kindOf(kind)
        val pool: List<Channel> = if (group.isNotEmpty()) idx.groupsOf(k)[group].orEmpty() else when (k) {
            Kind.LIVE -> idx.live
            Kind.MOVIE -> idx.movies
            Kind.SERIES -> idx.series
        }
        val needle = q.trim()
        val epg = repo.epg.value
        val now = System.currentTimeMillis()
        val items = if (needle.isEmpty()) {
            pool.asSequence().filter { it.group !in excluded }.take(MAX_ITEMS).toList()
        } else {
            // The same smart search as on the TV, best matches first.
            idx.search.find(needle, MAX_ITEMS) { it.kind == k && it.group !in excluded && (group.isEmpty() || it.group == group) }
        }
        return buildJsonArray {
            for (ch in items) {
                addJsonObject {
                    put("id", ch.id)
                    put("name", ch.name)
                    put("logo", ch.logo)
                    val sub = if (ch.kind == Kind.LIVE) onNow(epg[ch.epgKey], now)?.title.orEmpty() else ch.group
                    put("sub", sub)
                }
            }
        }.toString()
    }

    private fun onNow(programs: List<Program>?, now: Long): Program? = programs?.firstOrNull { now >= it.start && now < it.stop }

    private fun play(id: String, out: OutputStream) {
        val h = RemoteBridge.handler ?: return error(out, 409, "The TV isn't ready")
        val (idx, excluded) = h.library() ?: return error(out, 404, "No playlist open on the TV")
        val ch = idx.byId[id]?.takeIf { it.group !in excluded } ?: return error(out, 404, "Not found")
        when {
            ch.isSeriesFolder -> main { h.openSeries(ch) }
            ch.kind == Kind.LIVE -> {
                // Zapping on the TV goes through the channel's group, as when it's picked there.
                val list = idx.groupsOf(Kind.LIVE)[ch.group].orEmpty().filter { it.group !in excluded }.ifEmpty { listOf(ch) }
                main { h.play(list, ch) }
            }
            else -> main { h.play(listOf(ch), ch) }
        }
        sendJson(out, 200, buildJsonObject { put("message", if (ch.isSeriesFolder) "Opening on the TV" else "Playing on the TV") }.toString())
    }

    /** A key on the TV, as if pressed on its remote. */
    private fun pressKey(code: Int) {
        try {
            Instrumentation().sendKeyDownUpSync(code)
        } catch (e: Exception) {
            // Not allowed here (a system window on top?): hand it to the app's own screen.
            val a = RemoteBridge.activity?.get() ?: return
            a.runOnUiThread {
                a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
        }
    }

    private fun main(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).take(16).joinToString("") { "%02x".format(it) }

    /** "iPhone · Safari", "Android phone · Chrome"… from the browser's User-Agent. */
    private fun phoneLabel(ua: String): String {
        val what = when {
            "iPhone" in ua -> "iPhone"
            "iPad" in ua -> "iPad"
            "Android" in ua && "Mobile" in ua -> "Android phone"
            "Android" in ua -> "Android tablet"
            "Macintosh" in ua -> "Mac"
            "Windows" in ua -> "Windows PC"
            else -> "Phone"
        }
        val browser = when {
            "EdgA" in ua || "Edg/" in ua -> "Edge"
            "SamsungBrowser" in ua -> "Samsung Internet"
            "CriOS" in ua || "Chrome" in ua -> "Chrome"
            "FxiOS" in ua || "Firefox" in ua -> "Firefox"
            "Safari" in ua -> "Safari"
            else -> null
        }
        return listOfNotNull(what, browser).joinToString(" · ")
    }

    companion object {
        const val PORT = 8766
        private const val COOKIE = "tpr"
        private const val MAX_WRONG = 10
        private const val MAX_ITEMS = 150
        private val KEYS = mapOf(
            "up" to KeyEvent.KEYCODE_DPAD_UP,
            "down" to KeyEvent.KEYCODE_DPAD_DOWN,
            "left" to KeyEvent.KEYCODE_DPAD_LEFT,
            "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "ok" to KeyEvent.KEYCODE_DPAD_CENTER,
            "back" to KeyEvent.KEYCODE_BACK,
            "playpause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            "chup" to KeyEvent.KEYCODE_CHANNEL_UP,
            "chdown" to KeyEvent.KEYCODE_CHANNEL_DOWN,
            "info" to KeyEvent.KEYCODE_INFO,
            "last" to KeyEvent.KEYCODE_LAST_CHANNEL,
        )
    }
}
