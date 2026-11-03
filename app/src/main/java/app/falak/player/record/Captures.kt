package app.falak.player.record

import android.content.Context
import android.os.StatFs
import app.falak.player.repo
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * The channels being read from the provider right now, one connection each: the player and a
 * recording of the same channel share one (many providers allow a single connection). A capture
 * stops when neither the player nor a recording needs it any more, and its buffer is deleted.
 */
internal object Captures {
    private class Entry(val capture: Capture) {
        var viewers = 0
        var keepMinutes = 30
    }

    private val all = HashMap<String, Entry>()
    private val serial = AtomicLong(System.currentTimeMillis() % 100_000)
    /** Kept while only a recording uses it, so the player can join it at once. */
    private const val IDLE_KEEP_MS = 60_000L

    private fun root(context: Context) = File(context.cacheDir, "timeshift")

    /** A fresh start: buffers left by a run that was closed suddenly go. */
    fun clearLeftovers(context: Context) {
        val dir = root(context)
        if (!dir.exists()) return
        // Moved aside at once (new buffers may start straight away), deleted in the background.
        val old = File(context.cacheDir, "timeshift-old-" + System.currentTimeMillis())
        val gone = if (dir.renameTo(old)) old else dir
        Thread({ runCatching { gone.deleteRecursively() } }, "timeshift-clear").start()
        context.cacheDir.listFiles()?.filter { it.name.startsWith("timeshift-old-") && it != old }?.forEach { f ->
            Thread({ runCatching { f.deleteRecursively() } }, "timeshift-clear").start()
        }
    }

    private fun newCapture(context: Context, url: String, ua: String, referrer: String, finite: Boolean, failFast: Boolean): Capture {
        val id = "c" + serial.incrementAndGet()
        val app = context.applicationContext
        val dir = if (finite) null else File(root(app), id)
        return Capture(id, url, ua, referrer, app.repo.http, dir, finite = finite, failFast = failFast)
    }

    /** Room the buffer may take: half of what's free, at most 4 GB (and never under 64 MB). */
    private fun budget(context: Context): Long {
        val free = runCatching { StatFs(context.cacheDir.path).availableBytes }.getOrDefault(0L)
        return ((free - (300L shl 20)) / 2).coerceIn(64L shl 20, 4L shl 30)
    }

    private fun apply(context: Context, e: Entry) {
        e.capture.keepMs = if (e.viewers > 0) e.keepMinutes * 60_000L else IDLE_KEEP_MS
        e.capture.maxBytes = budget(context)
    }

    /**
     * The player plays [url] through the buffer: the capture already reading it (a recording),
     * or a new one. [keepMinutes]: how far back it can rewind.
     */
    @Synchronized
    fun watch(context: Context, url: String, ua: String, referrer: String, keepMinutes: Int): Capture {
        val found = all[url]?.takeIf { !it.capture.done && !it.capture.stopping }
        // One that stopped (it failed): its files go.
        if (found == null) all[url]?.let { drop(it) }
        val e = found ?: Entry(newCapture(context, url, ua, referrer, finite = false, failFast = true)).also {
            all[url] = it
            it.capture.start()
        }
        e.viewers++
        e.keepMinutes = keepMinutes.coerceIn(5, 240)
        apply(context, e)
        LocalStream.register(e.capture)
        return e.capture
    }

    /** The player let go of [c] (another channel, or it closed). */
    @Synchronized
    fun unwatch(context: Context, c: Capture) {
        val e = all.values.firstOrNull { it.capture === c } ?: return stopNow(c)
        e.viewers = (e.viewers - 1).coerceAtLeast(0)
        if (e.viewers == 0 && c.sinkCount == 0) drop(e) else apply(context, e)
    }

    /** A recording of [url]: shares what the player has open, or starts its own. */
    @Synchronized
    fun forRecording(context: Context, url: String, ua: String, referrer: String, replay: Boolean): Capture {
        if (!replay) {
            all[url]?.takeIf { !it.capture.done && !it.capture.stopping }?.let { e ->
                // A recording waits through drops instead of giving up quickly.
                e.capture.failFast = false
                apply(context, e)
                return e.capture
            }
        }
        if (!replay) all[url]?.let { drop(it) }
        val c = newCapture(context, url, ua, referrer, finite = replay, failFast = false)
        val e = Entry(c)
        all[if (replay) "replay:" + c.id else url] = e
        apply(context, e)
        LocalStream.register(c)
        c.start()
        return c
    }

    /** A recording finished with [c]. */
    @Synchronized
    fun recordingDone(context: Context, c: Capture, sink: Sink) {
        c.removeSink(sink)
        val e = all.values.firstOrNull { it.capture === c } ?: return stopNow(c)
        if (e.viewers == 0 && c.sinkCount == 0) drop(e) else apply(context, e)
    }

    /** Whether the player is buffering, or something records, [url] right now. */
    @Synchronized
    fun isOpen(url: String): Boolean = all[url]?.capture?.let { !it.done } == true

    private fun drop(e: Entry) {
        all.entries.removeAll { it.value === e }
        stopNow(e.capture)
    }

    private fun stopNow(c: Capture) {
        c.stop()
        LocalStream.unregister(c)
        // The files go once the reading thread has let go of them.
        Thread({
            Thread.sleep(1_500)
            c.ring?.delete()
        }, "timeshift-delete").start()
    }
}
