package app.falak.player.record

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.OptIn
import app.falak.player.FreezeWatch
import app.falak.player.OfflineCenter
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMuxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * "Save to gallery" (8.36, phones): a saved recording is copied to the phone's Movies › The Player
 * folder, where gallery and file apps see it and where it stays if the app is removed.
 *
 * Recordings are MPEG-TS (.ts), which many gallery apps don't show, so the picture and sound are
 * first put in an MP4 as they are (Media3's Transformer: nothing re-encoded, unless the sound is a
 * kind MP4 can't hold, then only the sound). If that fails, the .ts itself is copied. Android 10
 * and later: through MediaStore (no permission). Android 7–9: into the folder itself, which needs
 * the storage permission (asked by the page first).
 */
object GallerySave {
    const val FOLDER = "The Player"

    /** One save: its progress (0–100), then done or an error. */
    data class Job(
        val id: String,
        val title: String,
        val percent: Int = 0,
        val done: Boolean = false,
        val error: String? = null,
        /** Said after it's done (e.g. kept as .ts). */
        val note: String = "",
    ) {
        val busy: Boolean get() = !done && error == null
    }

    private val _state = MutableStateFlow<Job?>(null)
    val state: StateFlow<Job?> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null

    /** Android 7–9 write the folder themselves: the storage permission is needed. */
    val needsPermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** Screen tests: a save shown as under way, done or failed. */
    internal fun showForTest(job: Job?) {
        _state.value = job
    }

    fun dismiss() {
        if (_state.value?.busy != true) _state.value = null
    }

    /** "News at Ten · BBC One · 17 Nov 2026 21.00" without signs a file name can't have. */
    fun fileName(r: Recording): String {
        val at = r.progStart.takeIf { it > 0 } ?: r.startedAt.takeIf { it > 0 } ?: r.start
        val day = java.text.SimpleDateFormat("d MMM yyyy HH.mm", java.util.Locale.US).format(java.util.Date(at))
        val base = listOf(r.title, r.channelName).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        return (base.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(80) + " · " + day).trim()
    }

    /** Starts saving [r] (on the main thread). Nothing happens while another save runs. */
    @OptIn(UnstableApi::class)
    fun start(context: Context, r: Recording) {
        if (_state.value?.busy == true) return
        val app = context.applicationContext
        val src = File(r.file)
        if (r.state != RecState.DONE || !src.exists()) {
            _state.value = Job(r.id, r.title, error = "This recording's file is missing")
            return
        }
        _state.value = Job(r.id, r.title)
        val name = fileName(r)
        if (src.name.endsWith(".mp4", ignoreCase = true)) {
            copyOut(app, r, src, "$name.mp4", "video/mp4", from = 0, deleteAfter = false, note = "")
            return
        }
        // Room for the MP4 made here and then its copy in the gallery.
        val work = app.getExternalFilesDir(null) ?: app.cacheDir
        work.listFiles()?.filter { it.name.startsWith("gallery-") }?.forEach { it.delete() }
        val free = runCatching { StatFs(work.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (free < src.length() * 21 / 10 + 100_000_000L) {
            _state.value = Job(r.id, r.title, error = "Not enough free space: saving needs about ${OfflineCenter.sizeLabel(src.length() * 2)} free for a moment")
            return
        }
        val tmp = File(work, "gallery-${r.id}.mp4")
        FreezeWatch.note("gallery save: making an MP4 (${OfflineCenter.sizeLabel(src.length())})")
        val t = Transformer.Builder(app)
            // 8.37: Media3's own MP4 writer, not Android's MediaMuxer, which stops the whole app
            // (a native abort, no way to catch it) on samples it doesn't like.
            .setMuxerFactory(InAppMuxer.Factory.Builder().build())
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    FreezeWatch.note("gallery save: MP4 made, copying")
                    transformer = null
                    copyOut(app, r, tmp, "$name.mp4", "video/mp4", from = 80, deleteAfter = true, note = "")
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    FreezeWatch.note("gallery save: MP4 failed (${exportException.errorCodeName}), copying the .ts")
                    transformer = null
                    tmp.delete()
                    // Kept as it was recorded: still in the folder, though some gallery apps don't list .ts.
                    copyOut(
                        app, r, src, "$name.ts", "video/mp2t", from = 0, deleteAfter = false,
                        note = "Saved as it was recorded (.ts): some gallery apps don't show this kind of video; your Files app does.",
                    )
                }
            })
            .build()
        transformer = t
        try {
            t.start(MediaItem.fromUri(Uri.fromFile(src)), tmp.path)
        } catch (e: Exception) {
            transformer = null
            copyOut(app, r, src, "$name.ts", "video/mp2t", from = 0, deleteAfter = false, note = "Saved as it was recorded (.ts).")
            return
        }
        val holder = ProgressHolder()
        main.post(object : Runnable {
            override fun run() {
                val now = transformer ?: return
                if (now.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    _state.value = _state.value?.copy(percent = holder.progress * 80 / 100)
                }
                main.postDelayed(this, 500)
            }
        })
    }

    /** Copies [file] into the gallery (off the main thread), [from]–100 %. */
    private fun copyOut(app: Context, r: Recording, file: File, display: String, mime: String, from: Int, deleteAfter: Boolean, note: String) {
        Thread({
            val result = runCatching { write(app, r, file, display, mime) { done -> report(from + (100 - from) * done / 100) } }
            if (deleteAfter) file.delete()
            main.post {
                val e = result.exceptionOrNull()
                _state.value = if (e == null) {
                    Toast.makeText(app, "“${r.title}” is in your gallery", Toast.LENGTH_LONG).show()
                    Job(r.id, r.title, percent = 100, done = true, note = note)
                } else {
                    Job(r.id, r.title, error = "Couldn't save it to the gallery: ${e.message ?: "unknown error"}")
                }
            }
        }, "gallery-save").start()
    }

    private fun report(percent: Int) {
        main.post { _state.value?.takeIf { it.busy }?.let { _state.value = it.copy(percent = percent.coerceIn(0, 99)) } }
    }

    private fun write(app: Context, r: Recording, file: File, display: String, mime: String, progress: (Int) -> Unit) {
        val taken = r.startedAt.takeIf { it > 0 } ?: r.start
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = app.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, display)
                put(MediaStore.Video.Media.MIME_TYPE, mime)
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + FOLDER)
                put(MediaStore.Video.Media.DATE_TAKEN, taken)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IOException("the gallery didn't take it")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("the gallery didn't open")
                out.use { o -> file.inputStream().use { copy(it, o, file.length(), progress) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER)
            if (!dir.exists() && !dir.mkdirs()) throw IOException("the Movies folder can't be written")
            var out = File(dir, display)
            var n = 2
            while (out.exists()) out = File(dir, display.substringBeforeLast('.') + " ($n)." + display.substringAfterLast('.')).also { n++ }
            try {
                out.outputStream().use { o -> file.inputStream().use { copy(it, o, file.length(), progress) } }
            } catch (e: Exception) {
                out.delete()
                throw e
            }
            MediaScannerConnection.scanFile(app, arrayOf(out.path), arrayOf(mime), null)
        }
    }

    private fun copy(input: InputStream, output: OutputStream, total: Long, progress: (Int) -> Unit) {
        val buf = ByteArray(1 shl 20)
        var done = 0L
        var said = -1
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            done += n
            val p = if (total > 0) (done * 100 / total).toInt() else 0
            if (p != said) {
                said = p
                progress(p)
            }
        }
    }
}
