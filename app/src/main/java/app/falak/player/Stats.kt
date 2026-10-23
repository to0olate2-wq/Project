package app.falak.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.falak.player.data.Repository
import app.falak.player.ui.detectTv
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.UUID

/**
 * The anonymous user count (always on; described in PRIVACY.md). When the app comes on screen,
 * and every 10 minutes while it stays there, it sends the developer's counter three things: a
 * random number made up on this device, the app version, and whether it's a TV or a phone.
 * Nothing else: no name, email, playlists or what's watched. Builds made without a counter
 * address (see stats/README.md) send nothing at all.
 */
object Stats {
    private const val BEAT_MS = 10 * 60_000L
    private const val PREFS = "stats"
    private const val KEY_ID = "id"

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var repo: Repository? = null
    private var running = false

    /** The counter's ping address, or "" when this build has none. */
    private val endpoint: String
        get() = BuildConfig.STATS_URL.trim().trimEnd('/').let { if (it.startsWith("https://")) "$it/v1/ping" else "" }

    private val beat = object : Runnable {
        override fun run() {
            if (!running) return
            send("beat")
            main.postDelayed(this, BEAT_MS)
        }
    }

    /** The app is on screen: "open" now, then every 10 minutes. */
    fun start(context: Context, repository: Repository) {
        if (running || endpoint.isEmpty()) return
        app = context.applicationContext
        repo = repository
        running = true
        send("open")
        main.postDelayed(beat, BEAT_MS)
    }

    /** Off screen: nothing more until the app is back. */
    fun stop() {
        running = false
        main.removeCallbacks(beat)
    }

    private fun send(event: String) {
        val context = app ?: return
        val repository = repo ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString().replace("-", "").also {
            prefs.edit().putString(KEY_ID, it).apply()
        }
        post(context, repository, id, event)
    }

    private fun post(context: Context, repository: Repository, id: String, event: String) {
        val kind = if (detectTv(context)) "tv" else "phone"
        val body = """{"id":"$id","event":"$event","v":"${BuildConfig.VERSION_NAME}","kind":"$kind"}"""
        val request = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        // Fire and forget: a missed count never bothers anyone.
        repository.http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }
}
