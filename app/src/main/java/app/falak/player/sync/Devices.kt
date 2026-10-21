package app.falak.player.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.falak.player.data.SyncBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.ArrayDeque

/** Another device running The Player that is sharing right now. */
data class FoundDevice(val name: String, val address: String)

private const val SERVICE_TYPE = "_theplayer._tcp."

/** A friendly name for this device: the name set in Android, else its model. */
fun deviceName(context: Context): String {
    val set = try {
        android.provider.Settings.Global.getString(context.contentResolver, "device_name")
    } catch (e: Exception) {
        null
    }
    return set?.takeIf { it.isNotBlank() } ?: Build.MODEL.orEmpty().ifBlank { "Android device" }
}

/** Announces this device on the home network while sharing, so other devices list it by name. */
class Announcer(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun start(name: String, port: Int) {
        val manager = nsd ?: return
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            listener = l
        } catch (e: Exception) {
            // discovery is a convenience: the address and code still work
        }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        try {
            nsd?.unregisterService(l)
        } catch (e: Exception) {
            // already gone
        }
    }
}

/** Lists devices that are sharing on the home network. Callbacks arrive on the main thread. */
class DeviceFinder(context: Context, private val onChange: (List<FoundDevice>) -> Unit) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, FoundDevice>()
    private val toResolve = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null
    private var ownName: String? = null

    fun start(skipName: String?) {
        val manager = nsd ?: return
        ownName = skipName
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    if (info.serviceName == ownName) return@post
                    toResolve.add(info)
                    resolveNext()
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    if (found.remove(info.serviceName) != null) onChange(found.values.toList())
                }
            }
        }
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
            listener = l
        } catch (e: Exception) {
            // no discovery on this device: the address can still be typed in
        }
    }

    /** Older Android versions resolve one service at a time. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val manager = nsd ?: return
        if (resolving) return
        val next = toResolve.poll() ?: return
        resolving = true
        try {
            manager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        resolving = false
                        resolveNext()
                    }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        val host = info.host?.hostAddress
                        if (host != null && listener != null) {
                            val address = if (host.contains(':')) "[$host]:${info.port}" else "$host:${info.port}"
                            found[info.serviceName] = FoundDevice(info.serviceName, address)
                            onChange(found.values.toList())
                        }
                        resolveNext()
                    }
                }
            })
        } catch (e: Exception) {
            resolving = false
        }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        try {
            nsd?.stopServiceDiscovery(l)
        } catch (e: Exception) {
            // already stopped
        }
    }
}

/** Talks to another device's sharing page: copy its playlists here, or send ours there. */
object SyncClient {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun base(address: String): String {
        val a = address.trim().removePrefix("http://").removeSuffix("/")
        return "http://" + if (a.contains(':') && !a.endsWith("]")) a else "$a:${SyncServer.PORT}"
    }

    suspend fun fetch(http: OkHttpClient, address: String, code: String): SyncBundle = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(base(address) + "/api/bundle").header("X-Code", code.trim()).build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException(errorOf(text) ?: "The other device said no (${res.code})")
            json.decodeFromString(SyncBundle.serializer(), text)
        }
    }

    suspend fun send(http: OkHttpClient, address: String, code: String, bundle: SyncBundle): String = withContext(Dispatchers.IO) {
        val body = json.encodeToString(SyncBundle.serializer(), bundle).toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(base(address) + "/api/bundle").header("X-Code", code.trim()).post(body).build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException(errorOf(text) ?: "The other device said no (${res.code})")
            ((Json.parseToJsonElement(text) as? JsonObject)?.get("message") as? JsonPrimitive)?.content ?: "Sent"
        }
    }

    private fun errorOf(text: String): String? = try {
        ((Json.parseToJsonElement(text) as? JsonObject)?.get("error") as? JsonPrimitive)?.content
    } catch (e: Exception) {
        null
    }
}
