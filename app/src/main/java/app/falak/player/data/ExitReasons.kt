package app.falak.player.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stops the app's own crash handler never sees: the video engine crashing in native code, the app
 * freezing until Android closes it ("not responding"), or Android closing it for memory while it
 * was on screen. Android 11+ keeps a short history of why the app ended; each launch reads what is
 * new since the last one, so these can be shown and copied like an ordinary crash report.
 */
object ExitReasons {
    private const val PREFS = "diagnostics"
    private const val SEEN = "exit-seen"

    /** A report for the last unexpected stop since the previous launch, or null. */
    fun takeUnexpected(context: Context, header: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            read(context, header)
        } catch (e: Exception) {
            null // diagnostics must never get in the way of starting
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun read(context: Context, header: String): String? {
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong(SEEN, -1L)
        val newest = infos.maxOfOrNull { it.timestamp } ?: return null
        prefs.edit().putLong(SEEN, maxOf(newest, seen)).apply()
        // The first launch with this check only notes where history stands.
        if (seen < 0) return null
        val bad = infos.filter { it.timestamp > seen && isProblem(it) }.maxByOrNull { it.timestamp } ?: return null
        return describe(bad, header)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun isProblem(i: ApplicationExitInfo): Boolean {
        val onScreen = i.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        return when (i.reason) {
            // Ordinary (Java) crashes are already saved by FalakApp's handler.
            ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR -> true
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_SIGNALED -> onScreen
            else -> false
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun describe(i: ApplicationExitInfo, header: String): String {
        val what = when (i.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Crashed inside the video engine or Android's media code (native crash)"
            ApplicationExitInfo.REASON_ANR -> "Stopped responding, so Android closed it (ANR)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Closed by Android to free memory while on screen"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Closed by Android for using too much memory or processor"
            else -> "Stopped by Android (signal ${i.status})"
        }
        val out = StringBuilder(header)
        out.append(what).append('\n')
        out.append("When: ").append(SimpleDateFormat("d MMM HH:mm:ss", Locale.US).format(Date(i.timestamp))).append('\n')
        i.description?.takeIf { it.isNotBlank() }?.let { out.append("Android says: ").append(it).append('\n') }
        out.append("Memory then: ").append(i.pss / 1024).append(" MB (PSS), ").append(i.rss / 1024).append(" MB (RSS)\n")
        trace(i)?.let { out.append('\n').append(it) }
        return out.toString().take(12000)
    }

    /** The useful part of the system's trace: the main thread for a freeze, the frames for a crash. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun trace(i: ApplicationExitInfo): String? {
        val bytes = i.traceInputStream?.use { s -> s.readNBytesCompat(1_000_000) } ?: return null
        return when (i.reason) {
            ApplicationExitInfo.REASON_ANR -> {
                val text = String(bytes)
                val start = text.indexOf("\"main\"")
                if (start < 0) null else text.substring(start).lineSequence().take(80).joinToString("\n")
            }
            ApplicationExitInfo.REASON_CRASH_NATIVE -> {
                // A binary record (tombstone): keep the readable bits that name the signal and the
                // libraries and functions on the crashing thread.
                val words = printable(bytes)
                val keep = words.filter { w ->
                    w.contains(".so") || w.startsWith("SIG") || w.contains("abort", true) || w.contains("Cause") ||
                        w.contains("fault", true) || w.contains("libvlc", true) || w.contains("media", true)
                }.distinct().take(40)
                if (keep.isEmpty()) null else "Crash details:\n" + keep.joinToString("\n")
            }
            else -> null
        }
    }

    private fun printable(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xff
            if (c in 32..126) {
                cur.append(c.toChar())
            } else {
                if (cur.length >= 5) out += cur.toString()
                cur.setLength(0)
            }
        }
        if (cur.length >= 5) out += cur.toString()
        return out
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (buf.size() < max) {
            val n = read(chunk, 0, minOf(chunk.size, max - buf.size()))
            if (n < 0) break
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }
}
