package app.falak.player

import android.content.Context
import android.os.Build
import app.falak.player.data.CrashText
import app.falak.player.data.Repository
import app.falak.player.ui.detectTv
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Crash reports (always on; described in PRIVACY.md). When the app stopped unexpectedly, the next
 * start sends the developer's counter what went wrong: the kind of stop, the error and where in
 * the app's code it happened, the app's last steps, the app version, TV or phone, the device model
 * and Android version, and the device's random number from the user count. Web addresses, IP
 * addresses, logins, emails and file names are taken out first (see [CrashText]). Nothing is
 * shown on screen. A report that can't be sent waits for the next start, a week at most. Builds
 * made without a counter address send nothing.
 */
object CrashReports {
    private const val PENDING = "crash_pending.json"
    private const val STALE_MS = 7 * 24 * 3_600_000L
    private const val MAX_REPORT = 8_000
    private val busy = AtomicBoolean(false)

    /** Sends [report] (last time's crash, or null) and anything left from before, off the main thread. */
    fun submit(context: Context, repository: Repository, report: String?) {
        val app = context.applicationContext
        Thread({
            if (!busy.compareAndSet(false, true)) return@Thread
            try {
                send(app, repository, report)
            } catch (e: Exception) {
                // Never in the way: the report waits for the next start.
            } finally {
                busy.set(false)
            }
        }, "crash-report").start()
    }

    private fun send(context: Context, repository: Repository, report: String?) {
        val file = File(context.filesDir, PENDING)
        val base = Stats.base
        if (base.isEmpty()) {
            file.delete()
            return
        }
        if (report != null) file.writeText(body(context, report))
        if (!file.exists()) return
        if (System.currentTimeMillis() - file.lastModified() > STALE_MS) {
            file.delete()
            return
        }
        val request = Request.Builder()
            .url("$base/v1/crash")
            .post(file.readText().toRequestBody("application/json".toMediaType()))
            .build()
        repository.http.newCall(request).execute().use { response ->
            // Taken, or refused as malformed (sending it again won't help): done either way.
            if (response.code in 200..499) file.delete()
        }
    }

    private fun body(context: Context, report: String): String {
        val type = CrashText.type(report)
        return buildJsonObject {
            put("id", Stats.installId(context))
            put("v", BuildConfig.VERSION_NAME)
            put("kind", if (detectTv(context)) "tv" else "phone")
            put("type", type)
            put("sig", CrashText.scrub(CrashText.signature(type, report)).take(200))
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}".take(80))
            put("android", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            put("report", CrashText.scrub(report).take(MAX_REPORT))
        }.toString()
    }
}
