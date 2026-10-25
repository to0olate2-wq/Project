package app.falak.player.sync

import app.falak.player.data.Kind
import app.falak.player.data.Playlist
import app.falak.player.data.Repository
import app.falak.player.data.SourceType
import app.falak.player.data.SyncBundle
import app.falak.player.data.XtreamApi
import app.falak.player.data.maskHost
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small web server on the local network, running only while "Your devices" is open in the app.
 * A phone or computer browser on the same Wi-Fi opens http://<this device>:<port>, enters the
 * 6-digit code shown on screen, and can add, rename and remove playlists (no typing with the TV
 * remote), download a backup, or load one. Another device running The Player uses the same
 * address and code to copy everything across.
 */
class SyncServer(
    private val repo: Repository,
    private val deviceName: String,
    /** A problem report to hand to the phone (opened from the report on screen), if any. */
    private val report: (() -> String?)? = null,
    private val onEvent: (String) -> Unit,
) {
    /** The code people type on the other device; new every time sharing starts. */
    val code: String = String.format(java.util.Locale.US, "%06d", SecureRandom().nextInt(1_000_000))

    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "sync-http").apply { isDaemon = true } }
    private val wrongCodes = AtomicInteger(0)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val port: Int get() = server?.localPort ?: 0

    /** Starts listening; returns the port. */
    fun start(): Int {
        val s = try {
            ServerSocket(PORT)
        } catch (e: IOException) {
            ServerSocket(0) // someone else has the usual port
        }
        server = s
        Thread({
            while (!s.isClosed) {
                val client = try {
                    s.accept()
                } catch (e: IOException) {
                    break
                }
                pool.execute { serve(client) }
            }
        }, "sync-accept").apply { isDaemon = true }.start()
        return s.localPort
    }

    fun stop() {
        try {
            server?.close()
        } catch (e: IOException) {
            // closing anyway
        }
        pool.shutdownNow()
    }

    // ---------- HTTP ----------

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
        /** An upload's body, still unread: its handler writes it straight to storage ([length] bytes). */
        val input: InputStream? = null,
        val length: Long = 0,
        /** The body was over [MAX_BODY] and wasn't read. */
        val tooBig: Boolean = false,
    )

    private fun serve(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 20_000
                val input = BufferedInputStream(s.getInputStream())
                val req = read(input) ?: return
                val out = s.getOutputStream()
                route(req, out, s.inetAddress)
            } catch (e: Exception) {
                // one bad request never stops the server
            } catch (e: OutOfMemoryError) {
                // nor crashes the app
            }
        }
    }

    private fun read(input: InputStream): Request? {
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
        val method = parts[0].uppercase()
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        // An M3U file goes to storage as it arrives (see uploadPlaylist), never whole into memory.
        if (method == "POST" && path == UPLOAD_PATH) return Request(method, path, query, headers, ByteArray(0), input, length)
        if (length > MAX_BODY) return Request(method, path, query, headers, ByteArray(0), tooBig = true)
        val body = ByteArray(length.toInt())
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) break
            read += n
        }
        return Request(method, path, query, headers, body)
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

    private fun send(out: OutputStream, status: Int, type: String, body: ByteArray, extra: Map<String, String> = emptyMap()) {
        val reason = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 413 -> "Payload Too Large"
            429 -> "Too Many Requests"; 507 -> "Insufficient Storage"
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

    private fun sendJson(out: OutputStream, status: Int, text: String) =
        send(out, status, "application/json; charset=utf-8", text.toByteArray())

    private fun error(out: OutputStream, status: Int, message: String) =
        sendJson(out, status, buildJsonObject { put("error", message) }.toString())

    // ---------- API ----------

    private fun route(req: Request, out: OutputStream, from: InetAddress) {
        if (req.method == "GET" && (req.path == "/" || req.path == "/index.html")) {
            send(out, 200, "text/html; charset=utf-8", SyncPage.html(deviceName).toByteArray())
            return
        }
        if (req.path == "/api/info") {
            sendJson(out, 200, buildJsonObject { put("app", "The Player"); put("device", deviceName) }.toString())
            return
        }
        // Everything else needs the code shown on the TV.
        if (wrongCodes.get() >= MAX_WRONG) {
            error(out, 429, "Too many wrong codes. Close and reopen sharing on the TV for a new code.")
            return
        }
        val given = req.headers["x-code"] ?: req.query["code"]
        if (given != code) {
            wrongCodes.incrementAndGet()
            error(out, 403, "Wrong code")
            return
        }
        val who = from.hostAddress ?: "another device"
        if (req.tooBig) return error(out, 413, "That's too big to send this way")
        when ("${req.method} ${req.path}") {
            "GET /api/playlists" -> sendJson(out, 200, playlistsJson())
            "GET /api/bundle" -> {
                val text = json.encodeToString(SyncBundle.serializer(), repo.exportBundle(deviceName))
                val extra = if (req.query["download"] == "1") {
                    mapOf("Content-Disposition" to "attachment; filename=\"the-player-backup.json\"")
                } else {
                    emptyMap()
                }
                send(out, 200, "application/json; charset=utf-8", text.toByteArray(), extra)
                onEvent("Copied your playlists to $who")
            }
            "POST /api/bundle" -> {
                val bundle = try {
                    json.decodeFromString(SyncBundle.serializer(), String(req.body))
                } catch (e: Exception) {
                    error(out, 400, "That isn't a backup from The Player")
                    return
                }
                val result = repo.importBundle(bundle)
                onEvent("${result.describe()} from ${bundle.from.ifBlank { who }}")
                sendJson(out, 200, buildJsonObject { put("message", result.describe()) }.toString())
            }
            "POST /api/playlists" -> addPlaylist(req, out, who)
            "POST $UPLOAD_PATH" -> uploadPlaylist(req, out, who)
            "POST /api/link" -> {
                // Another device running The Player links with this one: from now on they sync by themselves.
                val o = body(req) ?: return error(out, 400, "Bad request")
                val name = o.str("device").ifBlank { who }
                val key = repo.syncKeyForLink(o.str("deviceId"), name)
                sendJson(
                    out, 200,
                    buildJsonObject {
                        put("key", key)
                        put("deviceId", repo.syncDeviceId())
                        put("device", deviceName)
                    }.toString(),
                )
                onEvent("Linked with $name: your playlists and favorites now stay in sync")
            }
            "GET /api/groups" -> {
                val text = groupsJson(req.query["id"].orEmpty()) ?: return error(out, 404, "No such playlist")
                sendJson(out, 200, text)
            }
            "POST /api/groups" -> setGroups(req, out)
            "GET /report" -> {
                val text = report?.invoke() ?: return error(out, 404, "No report")
                send(out, 200, "text/html; charset=utf-8", SyncPage.report(deviceName, text).toByteArray())
                onEvent("Report opened on $who")
            }
            "POST /api/playlists/rename" -> {
                val o = body(req) ?: return error(out, 400, "Bad request")
                val id = o.str("id")
                val name = o.str("name").trim()
                val p = repo.state.value.playlists.firstOrNull { it.id == id } ?: return error(out, 404, "No such playlist")
                if (name.isEmpty()) return error(out, 400, "Enter a name")
                repo.updatePlaylist(p.copy(name = name))
                onEvent("Renamed “${p.name}” to “$name”")
                sendJson(out, 200, playlistsJson())
            }
            "POST /api/playlists/delete" -> {
                val o = body(req) ?: return error(out, 400, "Bad request")
                val p = repo.state.value.playlists.firstOrNull { it.id == o.str("id") } ?: return error(out, 404, "No such playlist")
                repo.removePlaylist(p.id)
                onEvent("Removed “${p.name}”")
                sendJson(out, 200, playlistsJson())
            }
            else -> error(out, 404, "Not found")
        }
    }

    /** The name given, or the next free "Playlist 3". */
    private fun playlistName(given: String?): String = given.orEmpty().trim().ifBlank {
        val existing = repo.state.value.playlists
        var n = existing.size + 1
        while (existing.any { it.name == "Playlist $n" }) n++
        "Playlist $n"
    }

    /**
     * An M3U file from the page, sent as it is and written to storage as it arrives: no copy in
     * memory and no size limit but the free space, so big playlists go through on TV boxes too.
     */
    private fun uploadPlaylist(req: Request, out: OutputStream, who: String) {
        val input = req.input ?: return error(out, 400, "Bad request")
        val length = req.length
        if (length <= 0L) return error(out, 400, "Choose an M3U file")
        val id = UUID.randomUUID().toString()
        val file = repo.importedM3u(id)
        val free = file.parentFile?.usableSpace ?: Long.MAX_VALUE
        if (free < length + SPARE_SPACE) {
            return error(out, 507, "There isn't enough free space on the TV for this file (${length / (1024 * 1024)} MB)")
        }
        try {
            file.outputStream().buffered(256 * 1024).use { o ->
                val buf = ByteArray(64 * 1024)
                var left = length
                while (left > 0L) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("The file didn't arrive whole. Try again.")
                    o.write(buf, 0, n)
                    left -= n
                }
            }
        } catch (e: IOException) {
            file.delete()
            return error(out, 400, "The file didn't arrive whole. Try again.")
        }
        if (!looksLikeM3u(file)) {
            file.delete()
            return error(out, 400, "That file isn't an M3U playlist")
        }
        val name = playlistName(req.query["name"])
        val p = Playlist(id, name, SourceType.M3U_FILE, file.absolutePath, epgUrl = req.query["epg"].orEmpty().trim())
        repo.update { s -> s.copy(playlists = s.playlists + p, lastPlaylistId = s.lastPlaylistId ?: p.id) }
        onEvent("Added “$name” from $who")
        sendJson(out, 200, playlistsJson())
    }

    /** Starts like an M3U playlist, or has a channel line near the start (the first megabyte). */
    private fun looksLikeM3u(file: File): Boolean {
        val head = ByteArray(1024 * 1024)
        var got = 0
        file.inputStream().use { i ->
            while (got < head.size) {
                val n = i.read(head, got, head.size - got)
                if (n < 0) break
                got += n
            }
        }
        val text = String(head, 0, got, Charsets.UTF_8)
        return text.removePrefix("\uFEFF").trimStart().startsWith("#EXTM3U", true) || text.contains("#EXTINF", true)
    }

    private fun addPlaylist(req: Request, out: OutputStream, who: String) {
        val o = body(req) ?: return error(out, 400, "Bad request")
        val id = UUID.randomUUID().toString()
        val name = playlistName(o.str("name"))
        val epg = o.str("epgUrl").trim()
        val p = when (o.str("type")) {
            "m3u" -> {
                val url = o.str("url").trim()
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                    return error(out, 400, "Enter the playlist link (it starts with http)")
                }
                Playlist(id, name, SourceType.M3U_URL, url, epgUrl = epg)
            }
            "file" -> {
                val text = o.str("m3u")
                if (!text.trimStart().startsWith("#EXTM3U", true) && !text.contains("#EXTINF", true)) {
                    return error(out, 400, "That file isn't an M3U playlist")
                }
                Playlist(id, name, SourceType.M3U_FILE, repo.saveImportedM3u(id, text), epgUrl = epg)
            }
            "xtream" -> {
                val server = o.str("url").trim()
                val user = o.str("username").trim()
                val pass = o.str("password").trim()
                if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) return error(out, 400, "Fill in server, username and password")
                val normalized = XtreamApi.normalize(server)
                try {
                    runBlocking { repo.xtreamLogin(normalized, user, pass, "") }
                } catch (e: Exception) {
                    return error(out, 400, app.falak.player.data.hideAddresses(e.message ?: "Couldn't sign in", normalized))
                }
                Playlist(id, name, SourceType.XTREAM, normalized, user, pass, epg)
            }
            else -> return error(out, 400, "Choose M3U link, M3U file or Xtream")
        }
        repo.update { s -> s.copy(playlists = s.playlists + p, lastPlaylistId = s.lastPlaylistId ?: p.id) }
        onEvent("Added “$name” from $who")
        sendJson(out, 200, playlistsJson())
    }

    /** A playlist's groups per kind (Live, Films, Series) with item counts and hidden state. */
    private fun groupsJson(id: String): String? {
        val s = repo.state.value
        val p = s.playlists.firstOrNull { it.id == id } ?: return null
        val hidden = s.settings.hiddenGroups[id].orEmpty().toHashSet()
        val groups = repo.savedGroups(id)
        return buildJsonObject {
            put("id", p.id)
            put("name", p.name)
            put("ready", groups != null)
            putJsonArray("kinds") {
                for ((kind, label) in listOf(Kind.LIVE to "Live", Kind.MOVIE to "Films", Kind.SERIES to "Series")) {
                    val m = groups?.get(kind)
                    if (m.isNullOrEmpty()) continue
                    addJsonObject {
                        put("kind", kind.name.lowercase())
                        put("label", label)
                        putJsonArray("groups") {
                            for ((g, n) in m) addJsonObject {
                                put("name", g)
                                put("count", n)
                                put("hidden", g in hidden)
                            }
                        }
                    }
                }
            }
        }.toString()
    }

    private fun setGroups(req: Request, out: OutputStream) {
        val o = body(req) ?: return error(out, 400, "Bad request")
        val p = repo.state.value.playlists.firstOrNull { it.id == o.str("id") } ?: return error(out, 404, "No such playlist")
        val hide = (o["hidden"] as? JsonPrimitive)?.booleanOrNull ?: return error(out, 400, "Bad request")
        val groups = (o["groups"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        if (groups.isEmpty()) return error(out, 400, "Choose a group")
        repo.setGroupsHidden(p.id, groups, hide)
        val verb = if (hide) "Hid" else "Showed"
        onEvent(if (groups.size == 1) "$verb “${groups[0]}” in “${p.name}”" else "$verb ${groups.size} groups in “${p.name}”")
        sendJson(
            out, 200,
            buildJsonObject {
                putJsonArray("hidden") { repo.state.value.settings.hiddenGroups[p.id].orEmpty().forEach { add(JsonPrimitive(it)) } }
            }.toString(),
        )
    }

    private fun playlistsJson(): String = buildJsonArray {
        val last = repo.state.value.lastPlaylistId
        for (p in repo.state.value.playlists) {
            add(
                buildJsonObject {
                    put("id", p.id)
                    put("name", p.name)
                    put("last", p.id == last)
                    put("type", when (p.type) { SourceType.M3U_URL -> "M3U link"; SourceType.M3U_FILE -> "M3U file"; SourceType.XTREAM -> "Xtream" })
                    put("server", if (p.type == SourceType.M3U_FILE) "" else maskHost(p.url))
                    put("user", p.username)
                },
            )
        }
    }.toString()

    private fun body(req: Request): JsonObject? = try {
        Json.parseToJsonElement(String(req.body)) as? JsonObject
    } catch (e: Exception) {
        null
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    companion object {
        const val PORT = 8765
        private const val MAX_BODY = 40 * 1024 * 1024
        /** Where the page sends an M3U file as it is (see uploadPlaylist). */
        private const val UPLOAD_PATH = "/api/playlists/upload"
        /** Room left for the app after an upload. */
        private const val SPARE_SPACE = 50L * 1024 * 1024
        private const val MAX_WRONG = 20

        /** This device's address on the home network (Wi-Fi or Ethernet), e.g. 192.168.1.23. */
        fun localAddress(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .sortedByDescending { it.isSiteLocalAddress }
                .firstOrNull()?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
