package app.falak.player.torrent

import android.content.Context
import app.falak.player.BuildConfig
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * The app's one torrent at a time (8.16): [prepare] gets a link ready and [state] says how it is
 * going; once READY its [State.url] is a link on this device that the player opens like any film.
 * The film carries on arriving while it plays. [stop] ends it and deletes what was fetched.
 * GitHub version only: the Play version has no torrent library in it ([available] is false there).
 */
object Torrents {
    val available: Boolean get() = BuildConfig.TORRENTS || availableForTest

    /** Screen tests (which run the Play version). */
    @Volatile
    internal var availableForTest = false

    enum class Stage { IDLE, STARTING, BUFFERING, READY, FAILED }

    data class State(
        val stage: Stage = Stage.IDLE,
        /** The link it is about ([TorrentSource.infoHash] and file). */
        val key: String = "",
        /** What is happening, while STARTING ("Looking for people sharing it…"). */
        val note: String = "",
        val name: String = "",
        val size: Long = 0,
        val peers: Int = 0,
        val bytesPerSecond: Int = 0,
        /** 0–1 of the start of the film that has to be here before it plays. */
        val progress: Float = 0f,
        /** READY: the link the player opens. */
        val url: String = "",
        /** FAILED: why, in plain words. */
        val error: String = "",
    )

    val state = MutableStateFlow(State())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Touched only by the one job running at a time.
    private var engine: TorrentEngine? = null
    private var file: TorrentFile? = null
    private var server: TorrentServer? = null

    // Main thread only.
    private var job: Job? = null

    fun keyOf(source: TorrentSource): String = source.infoHash + "#" + (source.fileIdx ?: source.fileName)

    private fun dir(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "torrents")

    /** At app start: whatever a crash or a kill left behind. */
    fun clearLeftovers(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { dir(app).deleteRecursively() } }
    }

    /** Gets [source] ready to play (the same link again, still here: ready at once). */
    fun prepare(context: Context, source: TorrentSource) {
        val key = keyOf(source)
        val now = state.value
        if (now.key == key && (now.stage == Stage.READY || now.stage == Stage.STARTING || now.stage == Stage.BUFFERING)) return
        val app = context.applicationContext
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            release()
            state.value = State(Stage.STARTING, key, note = "Starting…")
            val me = coroutineContext.job
            try {
                val folder = dir(app)
                val e = engine ?: TorrentEngine(folder).also {
                    it.start()
                    engine = it
                }
                val f = e.open(source, cancelled = { !me.isActive }) { note -> state.value = state.value.copy(note = note) }
                file = f
                ensureActive()
                val free = folder.usableSpace
                if (free < f.size + RESERVE) {
                    throw TorrentException("Not enough free space: this film is ${gb(f.size)} and this device has ${gb(free)} free.")
                }
                state.value = State(Stage.BUFFERING, key, name = f.name, size = f.size)
                var quiet = 0
                while (!f.readyToPlay()) {
                    ensureActive()
                    val s = f.stats()
                    state.value = state.value.copy(peers = s.peers, bytesPerSecond = s.bytesPerSecond, progress = f.startProgress())
                    quiet = if (s.bytesPerSecond == 0) quiet + 1 else 0
                    if (quiet > QUIET_TICKS) throw TorrentException("Nobody is sharing this link right now. Try another one.")
                    delay(TICK_MS)
                }
                val srv = TorrentServer(f)
                server = srv
                state.value = state.value.copy(stage = Stage.READY, progress = 1f, url = srv.url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Cancelled while waiting inside the engine: the next job tidies up and says what's next.
                ensureActive()
                release()
                state.value = State(
                    Stage.FAILED,
                    key,
                    error = when (e) {
                        is TorrentException -> e.message.orEmpty()
                        is LinkageError -> "This device can't play torrent links."
                        else -> "This link couldn't be played (${e.javaClass.simpleName})."
                    },
                )
            }
        }
    }

    /** Ends it: nothing more is fetched or shared, and what was fetched is deleted. */
    fun stop() {
        if (job == null && state.value.stage == Stage.IDLE) return
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            release()
            runCatching { engine?.stop() }
            engine = null
            state.value = State()
        }
    }

    private fun release() {
        runCatching { server?.close() }
        server = null
        val f = file
        file = null
        if (f != null) runCatching { engine?.close(f) }
    }

    internal fun gb(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        else -> "${(bytes / (1L shl 20)).coerceAtLeast(1)} MB"
    }

    /** "1.4 MB/s", "350 kB/s". */
    fun speed(bytesPerSecond: Int): String = when {
        bytesPerSecond >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSecond / 1_000_000.0)
        else -> "${bytesPerSecond / 1000} kB/s"
    }

    private const val RESERVE = 300L * 1024 * 1024
    private const val TICK_MS = 500L

    /** Two minutes with nothing arriving. */
    private const val QUIET_TICKS = 240
}
