package app.falak.player.record

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.StatFs
import app.falak.player.data.Channel
import app.falak.player.data.Kind
import app.falak.player.data.Playlist
import app.falak.player.data.Program
import app.falak.player.data.Repository
import app.falak.player.repo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Recordings: live programmes saved on this device while they're on (now, planned from the TV
 * guide, or every episode of a series), and replays saved from the provider's archive. They run
 * in the background ([RecordingService], shown as a notification) even with the app closed; an
 * alarm wakes the app for the next one. A recording of the channel being watched shares its
 * connection, and starts from the programme's beginning when the pause buffer still has it.
 */
object RecordCenter {
    private lateinit var app: Context
    private val lock = Any()
    @Volatile private var ready = false

    private val _book = MutableStateFlow(RecordBook())
    val book: StateFlow<RecordBook> = _book.asStateFlow()

    /** Recordings being made: their capture and file. */
    private class Running(val capture: Capture, val sink: Sink)

    private val running = HashMap<String, Running>()

    /** One thread does the recording work (starting, stopping, copying from the buffer). */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "recorder").apply { isDaemon = true } }
    private var lastSave = 0L

    /** Stops this far before the storage is full. */
    private const val SPARE_BYTES = 300L shl 20
    const val ALARM_ACTION = "app.falak.player.RECORD_ALARM"

    fun init(context: Context) {
        synchronized(lock) {
            if (ready) return
            app = context.applicationContext
            ready = true
            val read = RecordBookStore.read(file())
            // A finished one whose file has gone (cleared storage, a drive taken out) is forgotten.
            _book.value = read.copy(
                recordings = read.recordings.filter { it.state != RecState.DONE || it.file.isBlank() || File(it.file).exists() },
            )
        }
    }

    private fun file() = File(app.filesDir, "recordings.json")

    fun recording(id: String): Recording? = _book.value.recordings.firstOrNull { it.id == id }

    private fun change(persist: Boolean = true, f: (RecordBook) -> RecordBook) {
        synchronized(lock) {
            val next = f(_book.value)
            if (next === _book.value) return
            _book.value = next
            val now = System.currentTimeMillis()
            if (persist || now - lastSave > 10_000) {
                lastSave = now
                runCatching { RecordBookStore.write(file(), next) }
            }
        }
    }

    private fun update(id: String, persist: Boolean = true, f: (Recording) -> Recording) =
        change(persist) { b -> b.copy(recordings = b.recordings.map { if (it.id == id) f(it) else it }) }

    private fun newId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) + (1000..9999).random()

    // ---- where they're saved ----

    /** A place recordings can go: this device, or a USB drive or memory card. */
    class Place(val path: String, val label: String, val free: Long)

    fun places(): List<Place> {
        val dirs = runCatching { app.getExternalFilesDirs(Environment.DIRECTORY_MOVIES).toList() }.getOrDefault(emptyList())
        return dirs.filterNotNull().mapIndexedNotNull { i, d ->
            val ok = runCatching { Environment.getExternalStorageState(d) == Environment.MEDIA_MOUNTED }.getOrDefault(i == 0)
            if (!ok) return@mapIndexedNotNull null
            val free = runCatching { StatFs(d.path).availableBytes }.getOrDefault(0L)
            Place(d.path, if (i == 0) "This device" else "USB drive or memory card" + if (dirs.size > 2) " $i" else "", free)
        }
    }

    /** The folder recordings go to now. */
    fun dir(): File {
        val chosen = app.repo.state.value.settings.recordDir
        val base = places().firstOrNull { it.path == chosen }?.path?.let(::File)
            ?: app.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(app.filesDir, "media")
        return File(base, "Recordings").apply { mkdirs() }
    }

    fun freeSpace(): Long = runCatching { StatFs(dir().path).availableBytes }.getOrDefault(0L)

    fun usedSpace(): Long = _book.value.recordings.filter { it.state == RecState.DONE || it.state == RecState.RECORDING }.sumOf { it.size }

    private fun fileFor(r: Recording): File {
        val day = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date(if (r.progStart > 0) r.progStart else r.start))
        val name = "$day ${r.channelName} - ${r.title}".replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(120)
        var f = File(dir(), "$name.ts")
        var n = 2
        while (f.exists()) f = File(dir(), "$name ($n).ts").also { n++ }
        return f
    }

    // ---- what the pages ask for ----

    /** The address a channel is recorded from: as the player would play it. */
    fun streamUrl(ch: Channel): String {
        val s = app.repo.state.value.settings
        return if (s.preferHls && ch.url.contains("/live/") && ch.url.endsWith(".ts")) ch.url.removeSuffix(".ts") + ".m3u8" else ch.url
    }

    private fun agent(ch: Channel, p: Playlist?): String =
        ch.userAgent.ifBlank { p?.userAgent.orEmpty() }.ifBlank { Repository.DEFAULT_UA }

    private fun early(): Long = app.repo.state.value.settings.recordEarly.coerceIn(0, 30) * 60_000L
    private fun late(): Long = app.repo.state.value.settings.recordLate.coerceIn(0, 60) * 60_000L

    private fun recordingOf(p: Playlist, ch: Channel, prog: Program?, start: Long, stop: Long, from: Long = 0, ruleId: String = ""): Recording =
        Recording(
            id = newId(), playlistId = p.id, channelId = ch.id, channelName = ch.name, logo = ch.logo,
            title = prog?.title?.ifBlank { null } ?: ch.name, desc = prog?.desc.orEmpty(),
            start = start, stop = stop, progStart = prog?.start ?: 0, progStop = prog?.stop ?: 0, from = from,
            url = streamUrl(ch), userAgent = agent(ch, p), referrer = ch.referrer,
            ruleId = ruleId, addedAt = System.currentTimeMillis(),
        )

    /**
     * Records [ch] now until [until]. With [prog] (the programme on now), from its start when the
     * pause buffer still has it.
     */
    fun recordNow(p: Playlist, ch: Channel, prog: Program?, until: Long): Recording? {
        if (ch.kind != Kind.LIVE || ch.url.isBlank()) return null
        val now = System.currentTimeMillis()
        busyNow(ch.id)?.let { return it }
        val from = prog?.start?.takeIf { it < now }?.let { it - early() } ?: 0L
        val r = recordingOf(p, ch, prog, now, until.coerceAtLeast(now + 60_000L), from = from)
        change { b -> b.copy(recordings = b.recordings + r) }
        kick()
        return r
    }

    /** Plans [prog] on [ch] (from the TV guide); if it's on now, it starts at once. */
    fun schedule(p: Playlist, ch: Channel, prog: Program, ruleId: String = ""): Recording? {
        if (ch.kind != Kind.LIVE || ch.url.isBlank()) return null
        val existing = planned(ch.id, prog)
        if (existing != null) return existing
        val now = System.currentTimeMillis()
        if (prog.stop <= now) return null
        val start = prog.start - early()
        val r = recordingOf(p, ch, prog, maxOf(start, now), prog.stop + late(), from = if (start < now) start else 0L, ruleId = ruleId)
        change { b -> b.copy(recordings = b.recordings + r) }
        if (r.start <= now) kick() else planAlarm()
        return r
    }

    /** Saves a past programme from the provider's archive ([url]: its replay). */
    fun saveReplay(p: Playlist, ch: Channel, prog: Program, url: String): Recording {
        val now = System.currentTimeMillis()
        planned(ch.id, prog)?.takeIf { it.replay }?.let { return it }
        // However the provider sends it, it can't take much longer than the programme itself.
        val r = recordingOf(p, ch, prog, now, now + (prog.stop - prog.start) + 20 * 60_000L).copy(url = url, replay = true)
        change { b -> b.copy(recordings = b.recordings + r) }
        kick()
        return r
    }

    /** "Record series": every programme called [prog]'s name on [ch], this one too. */
    fun addRule(p: Playlist, ch: Channel, prog: Program, programsOf: (String) -> List<Program>?): SeriesRule {
        val existing = _book.value.rules.firstOrNull { it.playlistId == p.id && it.channelId == ch.id && RecordRules.key(it.title) == RecordRules.key(prog.title) }
        val rule = existing ?: SeriesRule(newId(), p.id, ch.id, ch.name, prog.title, ch.logo, System.currentTimeMillis())
        if (existing == null) change { b -> b.copy(rules = b.rules + rule) }
        schedule(p, ch, prog, rule.id)
        planRules(p, programsOf, mapOf(ch.id to ch))
        return rule
    }

    fun removeRule(id: String) {
        change { b ->
            b.copy(
                rules = b.rules.filterNot { it.id == id },
                recordings = b.recordings.filterNot { it.ruleId == id && it.state == RecState.SCHEDULED },
            )
        }
        planAlarm()
    }

    fun ruleFor(channelId: String, title: String): SeriesRule? =
        _book.value.rules.firstOrNull { it.channelId == channelId && RecordRules.key(it.title) == RecordRules.key(title) }

    /**
     * The guide was loaded: the series rules plan what's coming up in the next 3 days.
     * [channels]: the playlist's live channels by id (the rule's channel is looked up there).
     */
    fun planRules(p: Playlist, programsOf: (String) -> List<Program>?, channels: Map<String, Channel>) {
        if (!ready) return
        val b = _book.value
        if (b.rules.none { it.playlistId == p.id }) return
        val now = System.currentTimeMillis()
        val added = RecordRules.plan(b, p.id, { id -> channels[id]?.let { programsOf(it.epgKey) } }, now, 3 * 86_400_000L) { rule, prog ->
            val ch = channels[rule.channelId] ?: return@plan null
            val start = prog.start - early()
            recordingOf(p, ch, prog, maxOf(start, now), prog.stop + late(), from = if (start < now) start else 0L, ruleId = rule.id)
        }
        if (added.isEmpty()) return
        change { bk -> bk.copy(recordings = bk.recordings + added) }
        if (added.any { it.start <= now }) kick() else planAlarm()
    }

    /** A recording planned or under way for [prog] on [channelId]. */
    fun planned(channelId: String, prog: Program): Recording? = _book.value.recordings.firstOrNull {
        it.channelId == channelId && it.progStart == prog.start && (it.state == RecState.SCHEDULED || it.state == RecState.RECORDING)
    }

    /** The recording [channelId] is being recorded by now, if any. */
    fun busyNow(channelId: String): Recording? = _book.value.recordings.firstOrNull { it.channelId == channelId && it.state == RecState.RECORDING }

    /** Stops a recording now, keeping what it has (or cancels a planned one). */
    fun stop(id: String) {
        val r = recording(id) ?: return
        if (r.state == RecState.SCHEDULED) {
            cancel(id)
            return
        }
        worker.execute { end(id, note = null, stopTime = System.currentTimeMillis()) }
    }

    /** A planned recording is called off (a series rule won't plan it again). */
    fun cancel(id: String) {
        val r = recording(id) ?: return
        change { b ->
            b.copy(
                recordings = b.recordings.filterNot { it.id == id },
                skipped = if (r.ruleId.isNotEmpty()) (b.skipped + r.slot).takeLast(300) else b.skipped,
            )
        }
        planAlarm()
    }

    /** Removes a recording and its file (stopping it first if it's being made). */
    fun delete(id: String) {
        val r = recording(id) ?: return
        worker.execute {
            if (r.state == RecState.RECORDING) end(id, note = null, stopTime = System.currentTimeMillis())
            val done = recording(id) ?: r
            change { b -> b.copy(recordings = b.recordings.filterNot { it.id == id }, skipped = if (r.ruleId.isNotEmpty()) (b.skipped + r.slot).takeLast(300) else b.skipped) }
            runCatching { if (done.file.isNotBlank()) File(done.file).delete() }
            LocalStream.forgetFile(id)
        }
    }

    fun deleteMany(ids: Collection<String>) = ids.forEach { delete(it) }

    /** A recording as something the player plays (the file; one still being made, as it grows). */
    fun playable(r: Recording): Channel? {
        val f = r.file.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() && it.length() > 0 } ?: return null
        val url = if (r.state == RecState.RECORDING) {
            LocalStream.urlForFile(r.id, LocalStream.Growing(f) { recording(r.id)?.state == RecState.RECORDING })
        } else {
            android.net.Uri.fromFile(f).toString()
        }
        val day = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(if (r.progStart > 0) r.progStart else r.startedAt.takeIf { it > 0 } ?: r.start))
        return Channel(
            id = "rec:" + r.id, name = r.title, group = "Recordings", url = url, logo = r.logo,
            kind = Kind.MOVIE, info = "${r.channelName} · $day",
        )
    }

    // ---- running them ----

    /** Something to start now: the background recorder runs (it stops itself when nothing's left). */
    fun kick() {
        if (!ready) return
        runCatching { androidx.core.content.ContextCompat.startForegroundService(app, Intent(app, RecordingService::class.java)) }
            .onFailure { planAlarm(soon = true) }
    }

    /**
     * Starts what's due, ends what's over, follows what's running. True while something records
     * or is about to start (the service keeps going).
     */
    internal fun tick(): Boolean {
        val now = System.currentTimeMillis()
        val list = _book.value.recordings
        for (r in list) {
            when (r.state) {
                RecState.SCHEDULED -> when {
                    now >= r.stop -> update(r.id) { it.copy(state = RecState.FAILED, note = "Missed: the device was off or asleep") }
                    now >= r.start -> begin(r)
                    else -> Unit
                }
                RecState.RECORDING -> follow(r, now)
                else -> Unit
            }
        }
        // Planned ones left over from a recording that was running when the app closed.
        synchronized(running) {
            val ids = _book.value.recordings.filter { it.state == RecState.RECORDING }.mapTo(HashSet()) { it.id }
            running.keys.filter { it !in ids }.forEach { id -> running.remove(id)?.let { Captures.recordingDone(app, it.capture, it.sink) } }
        }
        planAlarm()
        val soon = _book.value.recordings.any { it.state == RecState.SCHEDULED && it.start - now < 90_000L }
        return synchronized(running) { running.isNotEmpty() } || soon
    }

    /** Runs [tick] on the recorder's thread; [then] gets its answer. */
    internal fun tickAsync(then: (Boolean) -> Unit) {
        worker.execute {
            val busy = runCatching { tick() }.getOrDefault(false)
            then(busy)
        }
    }

    private fun begin(r: Recording) {
        if (freeSpace() < SPARE_BYTES) {
            update(r.id) { it.copy(state = RecState.FAILED, note = "Not enough space to record") }
            return
        }
        val f = r.file.takeIf { it.isNotBlank() }?.let(::File) ?: fileFor(r)
        val capture = Captures.forRecording(app, r.url, r.userAgent.ifBlank { Repository.DEFAULT_UA }, r.referrer, r.replay)
        val sink = Sink(f)
        // From the programme's start when the buffer still has it (and only for a fresh file).
        val from = if (!r.replay && r.from > 0 && f.length() == 0L) {
            capture.ring?.let { ring -> ring.oldestTime()?.takeIf { it <= r.from + 30_000L }?.let { ring.offsetAt(r.from) } }
        } else {
            null
        }
        runCatching { capture.addSink(sink, from) }
        synchronized(running) { running[r.id] = Running(capture, sink) }
        val now = System.currentTimeMillis()
        update(r.id) { it.copy(state = RecState.RECORDING, file = f.absolutePath, startedAt = if (it.startedAt > 0) it.startedAt else now, note = "") }
    }

    private fun follow(r: Recording, now: Long) {
        val run = synchronized(running) { running[r.id] }
        if (run == null) {
            // Recording when the app was closed suddenly: it carries on in the same file.
            if (now < r.stop) begin(r) else update(r.id) { finished(it, run = null, note = "Cut short when the app closed") }
            return
        }
        val c = run.capture
        when {
            now >= r.stop -> end(r.id, note = null, stopTime = now)
            r.replay && c.state == Capture.State.ENDED -> end(r.id, note = null, stopTime = now)
            run.sink.error != null -> end(r.id, note = run.sink.error, stopTime = now)
            freeSpace() < SPARE_BYTES -> end(r.id, note = "Stopped early: the storage is full", stopTime = now)
            c.state == Capture.State.FAILED || (c.done && !r.replay) -> {
                // The channel dropped for good: a fresh connection into the same file.
                val why = c.problem
                Captures.recordingDone(app, c, run.sink)
                synchronized(running) { running.remove(r.id) }
                if (c.received == 0L && run.sink.bytes == 0L && now - r.startedAt > 30_000L) {
                    update(r.id) { finished(it, run = null, note = why ?: "The channel didn't play") }
                } else {
                    update(r.id, persist = false) { it.copy(note = "Reconnecting…") }
                    begin(r)
                }
            }
            else -> update(r.id, persist = false) { it.copy(size = run.sink.bytes, note = "") }
        }
    }

    private fun end(id: String, note: String?, stopTime: Long) {
        val run = synchronized(running) { running.remove(id) }
        if (run != null) Captures.recordingDone(app, run.capture, run.sink)
        update(id) { r ->
            if (r.state != RecState.RECORDING) return@update r
            var done = finished(r, run, note)
            // fMP4 parts make an MP4 file, not a TS one.
            if (run?.capture?.mp4 == true && done.file.endsWith(".ts")) {
                val from = File(done.file)
                val to = File(done.file.removeSuffix(".ts") + ".mp4")
                if (from.renameTo(to)) done = done.copy(file = to.absolutePath)
            }
            done.copy(endedAt = stopTime)
        }
        LocalStream.forgetFile(id)
        planAlarm()
    }

    private fun finished(r: Recording, run: Running?, note: String?): Recording {
        val size = run?.sink?.bytes ?: File(r.file).takeIf { r.file.isNotBlank() }?.length() ?: 0L
        return if (size <= 0) {
            r.copy(state = RecState.FAILED, size = 0, note = note ?: run?.capture?.problem ?: "Nothing was recorded")
        } else {
            r.copy(state = RecState.DONE, size = size, note = note.orEmpty())
        }
    }

    /** Android stopped the background recorder (its time limit): what's being recorded is kept as it is. */
    internal fun stopAll(note: String) {
        worker.execute {
            val ids = synchronized(running) { running.keys.toList() }
            ids.forEach { end(it, note, System.currentTimeMillis()) }
        }
    }

    // ---- waking up for the next one ----

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        app, 4471, Intent(app, RecordReceiver::class.java).setAction(ALARM_ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Recordings start on time with the app closed only with exact alarms (Android 12+ asks for them). */
    fun canWakeOnTime(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return runCatching { app.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true }.getOrDefault(false)
    }

    /** An alarm for the next planned recording ([soon]: in a few seconds, to start one now). */
    fun planAlarm(soon: Boolean = false) {
        if (!ready) return
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent()
        val now = System.currentTimeMillis()
        val next = if (soon) now + 3_000L else _book.value.recordings.filter { it.state == RecState.SCHEDULED }.minOfOrNull { it.start }
        if (next == null) {
            runCatching { am.cancel(pi) }
            return
        }
        val at = maxOf(now + 2_000L, next - 20_000L)
        val exact = runCatching {
            if (canWakeOnTime()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                true
            } else {
                false
            }
        }.getOrDefault(false)
        if (!exact) runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    /** The app opened (or the device started): what's due starts, the next alarm is set. */
    fun onAppStart() {
        if (!ready) return
        val now = System.currentTimeMillis()
        val due = _book.value.recordings.any {
            (it.state == RecState.SCHEDULED && it.start <= now && it.stop > now) || it.state == RecState.RECORDING
        }
        if (due) kick() else planAlarm()
    }

    /** Settings → Recordings: the folder is changed. */
    fun setPlace(path: String) {
        app.repo.updateSettings { it.copy(recordDir = path) }
    }
}
