package app.falak.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Notices when the screen thread stops responding and writes down exactly where it is stuck, with
 * the last things the app did. Android's own "not responding" report only shows a few lines; this
 * keeps the whole picture, so a freeze can be traced to the screen and the code that caused it.
 */
object FreezeWatch {
    const val FILE = "freeze.txt"

    /** A freeze this long is reported even if the app recovered by itself. */
    const val REPORT_AFTER_MS = 8_000L
    private const val SAMPLE_AT_MS = 4_000L

    private val crumbs = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile private var lastPong = SystemClock.uptimeMillis()
    @Volatile private var started = false

    /** Remembers what the app just did (screen opened, channel started…). Cheap; keeps the last 30. */
    fun note(what: String) {
        val line = clock.format(Date()) + "  " + what
        synchronized(crumbs) {
            crumbs.addLast(line)
            while (crumbs.size > 30) crumbs.removeFirst()
        }
    }

    fun recent(): String = synchronized(crumbs) { crumbs.joinToString("\n") }

    fun start(filesDir: File) {
        if (started) return
        started = true
        val main = Handler(Looper.getMainLooper())
        val mainThread = Looper.getMainLooper().thread
        Thread({
            var stuckSince = 0L
            var samples = 0
            val text = StringBuilder()
            while (true) {
                main.post { lastPong = SystemClock.uptimeMillis() }
                try {
                    Thread.sleep(1_000)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                val now = SystemClock.uptimeMillis()
                val stuck = now - lastPong
                if (stuck < 1_500) {
                    if (samples > 0) {
                        // Recovered: record for how long, so short stalls can be told from real freezes.
                        val total = now - stuckSince
                        save(filesDir, text.toString().replace("{DURATION}", "${total / 1000} s (recovered)"))
                    }
                    samples = 0
                    stuckSince = 0L
                    text.setLength(0)
                    continue
                }
                if (stuckSince == 0L) stuckSince = lastPong
                // Two samples (at about 4 and 8 seconds): the same place twice means a real loop.
                val due = if (samples == 0) SAMPLE_AT_MS else SAMPLE_AT_MS * 2
                if (samples < 2 && stuck >= due) {
                    if (samples == 0) {
                        text.append("Screen froze for {DURATION}\n")
                        text.append("Last steps:\n").append(recent()).append("\n")
                    }
                    samples++
                    text.append("\nWhere it was stuck (after ${stuck / 1000} s):\n")
                    mainThread.stackTrace.take(70).forEach { text.append("  ").append(it.toString()).append('\n') }
                    save(filesDir, text.toString().replace("{DURATION}", "${stuck / 1000}+ s (still frozen)"))
                }
            }
        }, "freeze-watch").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun save(dir: File, text: String) {
        try {
            File(dir, FILE).writeText(text.take(16_000))
        } catch (e: Exception) {
            // diagnostics only
        }
    }

    /**
     * The saved freeze, if it is worth showing: always after Android closed the app as not
     * responding ([anr]), otherwise only when it lasted [REPORT_AFTER_MS] or more. Clears it.
     */
    fun take(dir: File, anr: Boolean): String? {
        val f = File(dir, FILE)
        if (!f.exists()) return null
        val text = try {
            f.readText()
        } catch (e: Exception) {
            null
        } finally {
            f.delete()
        }
        if (text.isNullOrBlank()) return null
        if (anr) return text
        val secs = Regex("froze for (\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        return text.takeIf { secs * 1000 >= REPORT_AFTER_MS }
    }
}
