package app.falak.player

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.falak.player.data.Channel
import app.falak.player.data.Notifier
import app.falak.player.data.OfflineItem
import app.falak.player.data.OfflineState
import app.falak.player.data.OfflineStore
import app.falak.player.data.Repository
import app.falak.player.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Downloads: films and episodes saved on this device to watch without internet. One at a time,
 * in the background (Android carries on with them when the app is closed), only on Wi-Fi unless
 * changed, picking up where they stopped. While the player streams something from the provider
 * they wait, as many providers allow one connection at a time.
 */
object OfflineCenter {
    private const val WORK = "offline-downloads"
    private const val CHANNEL = "downloads"
    private const val PROGRESS_ID = 4410
    private const val DONE_ID = 4411

    private lateinit var app: Context
    private val lock = Any()
    private var ready = false

    private val _items = MutableStateFlow<List<OfflineItem>>(emptyList())

    /** Every download, oldest first. */
    val items: StateFlow<List<OfflineItem>> = _items.asStateFlow()

    private val streams = HashSet<Any>()
    private val _watching = MutableStateFlow(false)

    /** True while anything streams from the provider (the player, Listen mode, a preview): downloads wait. */
    val watching: StateFlow<Boolean> = _watching.asStateFlow()

    /** [who] started (true) or stopped (false) streaming from the provider. */
    fun streaming(who: Any, on: Boolean) {
        synchronized(streams) {
            if (on) streams.add(who) else streams.remove(who)
            _watching.value = streams.isNotEmpty()
        }
    }

    /** The download being fetched now, so pausing or deleting it stops it at once. */
    @Volatile
    private var active: Pair<String, Call>? = null
    private var lastSave = 0L

    fun init(context: Context) {
        synchronized(lock) {
            if (ready) return
            app = context.applicationContext
            ready = true
            // A download cut off when the app was closed waits to carry on; a finished one whose
            // file has gone (cleared storage) is forgotten.
            _items.value = OfflineStore.read(storeFile()).mapNotNull { item ->
                when (item.state) {
                    OfflineState.DONE -> item.takeIf { File(it.file).exists() }
                    OfflineState.RUNNING -> item.copy(state = OfflineState.QUEUED)
                    else -> item
                }
            }
        }
    }

    private fun storeFile() = File(app.filesDir, "offline.json")

    /** Where downloads are kept: the app's own folder (removed with the app; no permission needed). */
    fun dir(): File = (app.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(app.filesDir, "downloads")).apply { mkdirs() }

    fun item(id: String): OfflineItem? = if (ready) _items.value.firstOrNull { it.id == id } else null

    /** Screenshot tests: downloads in every state, without fetching anything. */
    @androidx.annotation.VisibleForTesting
    fun showForTest(list: List<OfflineItem>) {
        _items.value = list
    }

    /** The saved copy of this film or episode, when it is fully downloaded and still there. */
    fun fileFor(id: String): File? {
        val i = item(id) ?: return null
        if (i.state != OfflineState.DONE) return null
        return File(i.file).takeIf { it.exists() }
    }

    /** Free space where downloads go, in bytes. */
    fun freeSpace(): Long = runCatching { StatFs(dir().path).availableBytes }.getOrDefault(0L)

    /** Space the finished downloads take, in bytes. */
    fun usedSpace(): Long = _items.value.filter { it.state == OfflineState.DONE }.sumOf { it.size }

    // ---- what the pages ask for ----

    /** Adds [ch] to the downloads ([show]: the series an episode belongs to). */
    fun download(ch: Channel, playlistId: String, userAgent: String, show: Channel? = null) {
        if (ch.url.isBlank() || ch.isSeriesFolder) return
        val existing = item(ch.id)
        if (existing != null) {
            if (existing.state != OfflineState.DONE) resume(ch.id)
            return
        }
        change(persist = true) { list ->
            list + OfflineItem(
                id = ch.id, playlistId = playlistId, name = ch.name, kind = ch.kind, logo = ch.logo, info = ch.info,
                seriesId = ch.seriesId, group = ch.group, url = ch.url,
                userAgent = ch.userAgent.ifBlank { userAgent }, referrer = ch.referrer,
                state = OfflineState.QUEUED, addedAt = System.currentTimeMillis(),
                show = show?.name.orEmpty(), showPoster = show?.logo.orEmpty(),
            )
        }
        schedule()
    }

    fun pause(id: String) {
        update(id) { if (it.state == OfflineState.RUNNING || it.state == OfflineState.QUEUED) it.copy(state = OfflineState.PAUSED, note = "") else it }
        interrupt(id)
    }

    fun resume(id: String) {
        update(id) { if (it.state != OfflineState.DONE) it.copy(state = OfflineState.QUEUED, note = "") else it }
        schedule()
    }

    /** Stops it if it's downloading and removes it, file and all. */
    fun delete(id: String) {
        val gone = item(id) ?: return
        change(persist = true) { list -> list.filterNot { it.id == id } }
        interrupt(id)
        deleteFiles(gone)
    }

    /** Wi-Fi only was changed: the waiting downloads follow the new choice. */
    fun rescheduleForNetwork() {
        if (_items.value.any { it.state == OfflineState.QUEUED || it.state == OfflineState.RUNNING }) schedule(replace = true)
    }

    /** The app opened: downloads left waiting carry on. */
    fun resumeWaiting() {
        if (ready && _items.value.any { it.state == OfflineState.QUEUED }) schedule()
    }

    /** Why the waiting downloads aren't moving yet, for the pages. */
    fun waitingNote(): String = when {
        watching.value -> "Waits while you watch"
        app.repo.state.value.settings.downloadWifiOnly && metered() -> "Waiting for Wi-Fi"
        else -> "Waiting to download"
    }

    private fun metered(): Boolean = runCatching {
        app.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false
    }.getOrDefault(false)

    private fun schedule(replace: Boolean = false) {
        val wifiOnly = app.repo.state.value.settings.downloadWifiOnly
        val request = OneTimeWorkRequestBuilder<OfflineWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        runCatching {
            WorkManager.getInstance(app).enqueueUniqueWork(
                WORK,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }

    // ---- the list ----

    private fun change(persist: Boolean, f: (List<OfflineItem>) -> List<OfflineItem>) {
        synchronized(lock) {
            _items.value = f(_items.value)
            val now = System.currentTimeMillis()
            if (persist || now - lastSave > 5_000) {
                lastSave = now
                runCatching { OfflineStore.write(storeFile(), _items.value) }
            }
        }
    }

    private fun update(id: String, persist: Boolean = true, f: (OfflineItem) -> OfflineItem) =
        change(persist) { list -> list.map { if (it.id == id) f(it) else it } }

    private fun interrupt(id: String) {
        val a = active
        if (a != null && a.first == id) a.second.cancel()
    }

    private fun deleteFiles(item: OfflineItem) {
        runCatching { if (item.file.isNotBlank()) File(item.file).delete() }
        runCatching { File(dir(), item.partName).delete() }
    }

    internal fun nextQueued(): OfflineItem? = _items.value.firstOrNull { it.state == OfflineState.QUEUED }

    /** Done with this round (the worker stopped): what was downloading waits for the next one. */
    internal fun requeueRunning() {
        change(persist = true) { list -> list.map { if (it.state == OfflineState.RUNNING) it.copy(state = OfflineState.QUEUED) else it } }
    }

    // ---- fetching ----

    internal sealed class Fetch {
        object Done : Fetch()
        object Stopped : Fetch()
        class Retry(val why: String) : Fetch()
        class Failed(val why: String) : Fetch()
    }

    /**
     * Downloads [start] into its part file, carrying on from what's already there, until it's
     * whole, [stopped] says to stop, it is paused or deleted, or the player starts streaming.
     */
    internal suspend fun fetch(start: OfflineItem, stopped: () -> Boolean, onProgress: (OfflineItem) -> Unit): Fetch = withContext(Dispatchers.IO) {
        val folder = dir()
        val part = File(folder, start.partName)
        var have = if (part.exists()) part.length() else 0L
        update(start.id) { it.copy(state = OfflineState.RUNNING, note = "", done = have) }
        val request = Request.Builder()
            .url(start.url)
            .header("User-Agent", start.userAgent.ifBlank { Repository.DEFAULT_UA })
            .apply {
                if (start.referrer.isNotBlank()) header("Referer", start.referrer)
                if (have > 0) header("Range", "bytes=$have-")
            }
            .build()
        val client = app.repo.http.newBuilder().readTimeout(45, TimeUnit.SECONDS).build()
        val call = client.newCall(request)
        active = start.id to call
        // Stopped by Android (no more Wi-Fi, say): drop the connection rather than wait on it.
        val stopping = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { call.cancel() }
        fun keepGoing() = !stopped() && !watching.value && item(start.id)?.state == OfflineState.RUNNING
        try {
            call.execute().use { response ->
                if (response.code == 416 && have > 0) {
                    // Nothing left to send: the part file is already whole.
                    return@withContext finish(start, part, have, response.header("Content-Type").orEmpty())
                }
                if (!response.isSuccessful) {
                    return@withContext when (response.code) {
                        401, 403, 404, 410 -> Fetch.Failed("Your provider refused it (${response.code})")
                        else -> Fetch.Retry("The provider answered ${response.code}")
                    }
                }
                val type = response.header("Content-Type").orEmpty()
                val body = response.body ?: return@withContext Fetch.Retry("No data came")
                // A list of parts (HLS) or a page instead of a video file (a big file wrongly
                // labelled as text is still saved).
                val small = body.contentLength() in 0..2_000_000
                if (OfflineStore.notAFile(type) && (small || "mpegurl" in type.lowercase() || "dash" in type.lowercase())) {
                    return@withContext Fetch.Failed("This title streams in parts and can't be saved")
                }
                if (response.code != 206 && have > 0) {
                    // The server can't carry on from the middle: start again.
                    have = 0
                    part.delete()
                }
                val length = body.contentLength()
                val total = when {
                    response.code == 206 -> OfflineStore.totalFromContentRange(response.header("Content-Range"))
                        ?: if (length >= 0) have + length else 0L
                    else -> length.coerceAtLeast(0L)
                }
                if (total > 0) {
                    val need = total - have
                    val free = freeSpace()
                    if (need > free - 200L * 1024 * 1024) {
                        return@withContext Fetch.Failed("Not enough space: it needs ${sizeLabel(need)}")
                    }
                }
                update(start.id) { it.copy(size = total, done = have) }
                FileOutputStream(part, have > 0).use { out ->
                    val input = body.byteStream()
                    val buffer = ByteArray(256 * 1024)
                    var lastReport = 0L
                    while (true) {
                        if (!keepGoing()) return@withContext Fetch.Stopped
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        have += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 700) {
                            lastReport = now
                            update(start.id, persist = false) { it.copy(done = have) }
                            item(start.id)?.let(onProgress)
                        }
                    }
                }
                if (total > 0 && have < total) return@withContext Fetch.Retry("The connection was cut")
                finish(start, part, have, type)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (call.isCanceled() || !keepGoing()) Fetch.Stopped else Fetch.Retry(e.message ?: "No connection")
        } finally {
            stopping?.dispose()
            if (active?.first == start.id) active = null
        }
    }

    private fun finish(item: OfflineItem, part: File, size: Long, contentType: String): Fetch {
        if (size <= 0) return Fetch.Failed("Nothing was sent")
        val target = File(part.parentFile, OfflineItem.safeName(item.id) + "." + OfflineStore.extensionFor(item.url, contentType))
        target.delete()
        if (!part.renameTo(target)) return Fetch.Retry("Couldn't save the file")
        if (item(item.id) == null) {
            // Deleted meanwhile.
            target.delete()
            return Fetch.Stopped
        }
        update(item.id) { it.copy(state = OfflineState.DONE, file = target.absolutePath, size = size, done = size, note = "") }
        return Fetch.Done
    }

    internal fun failed(id: String, why: String) = update(id) { it.copy(state = OfflineState.FAILED, note = why) }

    internal fun retrying(id: String, why: String) = update(id) { it.copy(state = OfflineState.QUEUED, note = "$why: trying again") }

    /** A deleted download's part file, once its fetch has stopped. */
    internal fun cleanUp(id: String) {
        if (item(id) == null) runCatching { File(dir(), OfflineItem.safeName(id) + ".part").delete() }
    }

    // ---- notifications ----

    private fun channel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = app.getSystemService(NotificationManager::class.java)
            if (nm?.getNotificationChannel(CHANNEL) == null) {
                nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    private fun openDownloads(): PendingIntent {
        val intent = Intent(app, MainActivity::class.java)
            .putExtra(Notifier.EXTRA_OPEN, "DOWNLOADS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(app, 4410, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun progressNotification(item: OfflineItem?, note: String? = null): Notification {
        channel()
        val pct = ((item?.fraction ?: 0f) * 100).toInt()
        val text = when {
            note != null -> note
            item == null -> "Getting ready…"
            item.size > 0 -> "$pct%  ·  ${sizeLabel(item.done)} of ${sizeLabel(item.size)}"
            else -> sizeLabel(item.done)
        }
        val waiting = _items.value.count { it.state == OfflineState.QUEUED }
        return NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(item?.let { "Downloading ${it.title}" } ?: "Downloads")
            .setContentText(text)
            .setSubText(if (waiting > 0) "$waiting more waiting" else null)
            .setProgress(100, pct, item == null || item.size <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openDownloads())
            .build()
    }

    internal fun foregroundInfo(item: OfflineItem?): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(PROGRESS_ID, progressNotification(item), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(PROGRESS_ID, progressNotification(item))
        }

    internal fun showProgress(item: OfflineItem, note: String? = null) {
        if (!canNotify()) return
        runCatching { NotificationManagerCompat.from(app).notify(PROGRESS_ID, progressNotification(item, note)) }
    }

    internal fun showDone(item: OfflineItem) {
        if (!canNotify()) return
        channel()
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Downloaded")
            .setContentText(item.title)
            .setAutoCancel(true)
            .setContentIntent(openDownloads())
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(DONE_ID, n) }
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** "1.4 GB", "820 MB". */
    fun sizeLabel(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024) else String.format(Locale.US, "%.0f MB", mb.coerceAtLeast(if (bytes > 0) 1.0 else 0.0))
    }
}

/** Works through the downloads waiting in line, one at a time (see [OfflineCenter]). */
class OfflineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        OfflineCenter.init(applicationContext)
        val tries = HashMap<String, Int>()
        var shown = false
        try {
            while (!isStopped) {
                val item = OfflineCenter.nextQueued() ?: break
                // Something from the provider is playing: wait for the player to close.
                if (OfflineCenter.watching.value) {
                    if (shown) OfflineCenter.showProgress(item, "Waits while you watch")
                    OfflineCenter.watching.first { !it }
                    continue
                }
                if (!shown) {
                    // Shown as a download in progress, so Android lets it carry on with the app closed.
                    shown = runCatching { setForeground(OfflineCenter.foregroundInfo(item)) }.isSuccess
                }
                when (val r = OfflineCenter.fetch(item, { isStopped }) { OfflineCenter.showProgress(it) }) {
                    is OfflineCenter.Fetch.Done -> {
                        tries.remove(item.id)
                        OfflineCenter.item(item.id)?.let { OfflineCenter.showDone(it) }
                    }
                    is OfflineCenter.Fetch.Stopped -> {
                        OfflineCenter.cleanUp(item.id)
                        // Stopped by Android or by the player starting: it waits in line again.
                        if (OfflineCenter.item(item.id)?.state == OfflineState.RUNNING) OfflineCenter.requeueRunning()
                    }
                    is OfflineCenter.Fetch.Retry -> {
                        val n = (tries[item.id] ?: 0) + 1
                        tries[item.id] = n
                        if (n > 3) {
                            OfflineCenter.failed(item.id, r.why)
                        } else {
                            OfflineCenter.retrying(item.id, r.why)
                            delay(10_000L * n)
                        }
                    }
                    is OfflineCenter.Fetch.Failed -> OfflineCenter.failed(item.id, r.why)
                }
            }
        } finally {
            // Whatever was downloading when this round ended waits for the next one.
            OfflineCenter.requeueRunning()
        }
        return Result.success()
    }
}
