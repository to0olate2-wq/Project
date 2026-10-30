package app.falak.player.sync

import android.content.Context
import android.util.Base64
import app.falak.player.FreezeWatch
import app.falak.player.data.Repository
import app.falak.player.data.KidsClock
import app.falak.player.data.SyncSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The linked devices' shared secret: everything they send each other is encrypted with it. */
class SyncCrypto(key: String) {
    private val secret = SecretKeySpec(Base64.decode(key, Base64.NO_WRAP), "AES")

    /** A short public tag of the group, so devices find the ones linked with them. */
    val tag: String = MessageDigest.getInstance("SHA-256").digest(secret.encoded)
        .take(4).joinToString("") { "%02x".format(it) }

    fun seal(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }

    fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < 28) throw IOException("Too short")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, sealed, 0, 12))
        return c.doFinal(sealed, 12, sealed.size - 12)
    }

    companion object {
        private val random = SecureRandom()

        fun newKey(): String = Base64.encodeToString(ByteArray(32).also { random.nextBytes(it) }, Base64.NO_WRAP)
    }
}

/**
 * Keeps linked devices in step while the app is open: each one listens on the home network,
 * finds the others linked with it, and every few minutes (and soon after a change) swaps its
 * state with them. Both sides merge, so they end up the same.
 */
class AutoSync(private val context: Context, private val repo: Repository) {
    private val json = Json { ignoreUnknownKeys = true }
    private val me = deviceName(context)
    private var scope: CoroutineScope? = null
    private var server: ServerSocket? = null
    private var announcer: Announcer? = null
    private var finder: DeviceFinder? = null
    private var crypto: SyncCrypto? = null
    private val peers = ConcurrentHashMap<String, String>() // service name -> address
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private var soon: Job? = null
    private val http: OkHttpClient = repo.http.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val _status = MutableStateFlow<String?>(null)

    /** What happened last, for Settings ("Synced with Living room TV"). */
    val status: StateFlow<String?> = _status.asStateFlow()

    /** The key it's running with; null when stopped. */
    var runningKey: String? = null
        private set

    /** Starts listening and looking for linked devices (main thread). */
    fun start(key: String) {
        if (runningKey == key) return
        stop()
        val c = try {
            SyncCrypto(key)
        } catch (e: Exception) {
            return
        }
        val ss = try {
            ServerSocket(0)
        } catch (e: IOException) {
            return
        }
        runningKey = key
        crypto = c
        server = ss
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "sync-peer").apply { isDaemon = true } }
        Thread({
            while (!ss.isClosed) {
                val client = try {
                    ss.accept()
                } catch (e: IOException) {
                    break
                }
                pool.execute { serve(client, c) }
            }
            pool.shutdownNow()
        }, "sync-listen").apply { isDaemon = true }.start()

        val prefix = "tp-${c.tag}-"
        val name = prefix + repo.syncDeviceId().take(8)
        announcer = Announcer(context, SYNC_SERVICE).also { it.start(name, ss.localPort) }
        finder = DeviceFinder(context, SYNC_SERVICE) { list ->
            val before = peers.keys.toSet()
            peers.clear()
            list.filter { it.name.startsWith(prefix) && it.name != name }.forEach { peers[it.name] = it.address }
            if (peers.keys.any { it !in before }) kick.trySend(Unit) // a device just turned up: sync now
        }.also { it.start(skipName = name) }
        repo.onSyncChange = { syncSoon() }

        sc.launch {
            while (isActive) {
                withTimeoutOrNull(EVERY_MS) { kick.receive() }
                delay(1_500) // a burst of changes or finds goes as one
                syncAll()
            }
        }
    }

    fun stop() {
        runningKey = null
        repo.onSyncChange = null
        soon = null
        scope?.cancel()
        scope = null
        try {
            server?.close()
        } catch (e: IOException) {
            // closing anyway
        }
        server = null
        announcer?.stop()
        announcer = null
        finder?.stop()
        finder = null
        peers.clear()
        crypto = null
    }

    /** Sync with every linked device found, now. */
    fun syncNow() {
        kick.trySend(Unit)
    }

    /** After a change here: sync in a little while (more changes usually follow). */
    private fun syncSoon() {
        val sc = scope ?: return
        if (soon?.isActive == true) return
        soon = sc.launch {
            delay(SOON_MS)
            kick.trySend(Unit)
        }
    }

    private fun syncAll() {
        val c = crypto ?: return
        if (peers.isEmpty()) return
        var changed = false
        var reached = 0
        for (address in peers.values.toList()) {
            try {
                if (exchange(address, c)) changed = true
                reached++
            } catch (e: Exception) {
                // that device went to sleep or left the network: next time
            }
        }
        if (reached > 0) {
            val names = repo.state.value.sync.peers.values.sortedByDescending { it.lastSync }.take(2).joinToString(", ") { it.name }
            _status.value = "Synced with $names"
        }
        // Something came in: pass it on to the other devices too.
        if (changed && peers.size > 1) kick.trySend(Unit)
    }

    /** Sends our state, takes theirs back (already merged with ours on their side). */
    private fun exchange(address: String, c: SyncCrypto): Boolean {
        val body = c.seal(pack(snapshot()))
        val req = Request.Builder()
            .url("http://$address/sync")
            .header("X-Group", c.tag)
            .post(body.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            val bytes = res.body?.bytes() ?: throw IOException("Empty")
            return take(unpack(c.open(bytes)))
        }
    }

    /** What this device sends: the app's state, and the Kids profile's day here. */
    private fun snapshot(): SyncSnapshot = repo.syncSnapshot(me).copy(kids = KidsClock.share(context))

    /** Takes in what a linked device sent. Returns true when something here changed. */
    private fun take(theirs: SyncSnapshot): Boolean {
        val changed = repo.mergeSync(theirs)
        theirs.kids?.let { KidsClock.takeShare(context, it, repo.state.value.settings) }
        return changed
    }

    // ---- the listening side ----

    private fun serve(socket: Socket, c: SyncCrypto) {
        socket.use { s ->
            try {
                s.soTimeout = 20_000
                val input = BufferedInputStream(s.getInputStream())
                val first = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val out = s.getOutputStream()
                val parts = first.split(' ')
                if (parts.size < 2 || parts[0] != "POST" || parts[1] != "/sync") return reply(out, 404, ByteArray(0))
                if (headers["x-group"] != c.tag) return reply(out, 403, ByteArray(0))
                val length = headers["content-length"]?.toIntOrNull() ?: return reply(out, 411, ByteArray(0))
                if (length !in 1..MAX_BODY) return reply(out, 413, ByteArray(0))
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) return
                    read += n
                }
                val theirs = try {
                    unpack(c.open(body))
                } catch (e: Exception) {
                    return reply(out, 403, ByteArray(0)) // not from a linked device
                }
                val changed = take(theirs)
                reply(out, 200, c.seal(pack(snapshot())))
                _status.value = "Synced with ${theirs.device.ifBlank { "another device" }}"
                FreezeWatch.note("synced with another device")
                if (changed && peers.size > 1) kick.trySend(Unit)
            } catch (e: Exception) {
                // a dropped connection: the other side tries again later
            }
        }
    }

    private fun reply(out: java.io.OutputStream, status: Int, body: ByteArray) {
        val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
            "Content-Type: application/octet-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            if (buf.size() > 8192) return null
            buf.write(b)
        }
    }

    private fun pack(s: SyncSnapshot): ByteArray {
        val raw = ByteArrayOutputStream()
        GZIPOutputStream(raw).use { it.write(json.encodeToString(SyncSnapshot.serializer(), s).toByteArray()) }
        return raw.toByteArray()
    }

    private fun unpack(bytes: ByteArray): SyncSnapshot {
        val text = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }.toString(Charsets.UTF_8)
        return json.decodeFromString(SyncSnapshot.serializer(), text)
    }

    companion object {
        const val SYNC_SERVICE = "_theplayersync._tcp."
        private const val EVERY_MS = 5 * 60_000L
        private const val SOON_MS = 20_000L
        private const val MAX_BODY = 16 * 1024 * 1024
    }
}
