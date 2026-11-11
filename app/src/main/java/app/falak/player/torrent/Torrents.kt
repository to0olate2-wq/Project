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
        /** People connected to now, and people known to share it. */
        val peers: Int = 0,
        val found: Int = 0,
        val bytesPerSecond: Int = 0,
        /** How much of the film is on the device now, and how much the player has taken (READY). */
        val keptBytes: Long = 0,
        val playedBytes: Long = 0,
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

    /** In the app's own storage (a real disk: what libtorrent writes there can be read back at once). */
    private fun dir(context: Context): File = File(context.filesDir, "torrents")

    /** At app start: whatever a crash or a kill left behind (8.16 kept it in the shared storage). */
    fun clearLeftovers(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { dir(app).deleteRecursively() }
            runCatching { app.getExternalFilesDir(null)?.let { File(it, "torrents").deleteRecursively() } }
        }
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
                // A stream, not a download: only some of the film is on the device at a time, so one
                // bigger than the free space plays too. This much of it at most:
                val room = (folder.usableSpace - RESERVE).coerceAtMost(MAX_KEPT)
                val f = e.open(source, maxBytes = room, cancelled = { !me.isActive }) { note, found, connected ->
                    state.value = state.value.copy(note = note, found = found, peers = connected)
                }
                file = f
                ensureActive()
                state.value = State(Stage.BUFFERING, key, name = f.name, size = f.size)
                var quiet = 0
                var startAt = 0L
                while (true) {
                    ensureActive()
                    val s = f.stats()
                    state.value = state.value.copy(
                        peers = s.peers, found = s.found, bytesPerSecond = s.bytesPerSecond, keptBytes = s.keptBytes, progress = f.startProgress(),
                    )
                    if (f.startReady()) {
                        // The end too, where many films keep their index; not waited for long.
                        if (startAt == 0L) startAt = System.nanoTime()
                        if (f.endReady() || System.nanoTime() - startAt > END_WAIT_NS) break
                    }
                    quiet = if (s.bytesPerSecond == 0) quiet + 1 else 0
                    if (quiet > QUIET_TICKS) {
                        throw TorrentException(
                            if (s.peers == 0) "Nobody sharing this link could be reached. Try another one."
                            else "The ${s.peers} people sharing this link aren't sending anything. Try another one.",
                        )
                    }
                    delay(TICK_MS)
                }
                val srv = TorrentServer(f)
                server = srv
                state.value = state.value.copy(stage = Stage.READY, progress = 1f, url = srv.url)
                // While it plays: how it is going (shown on the film's page; it also tells where a film is stuck).
                while (true) {
                    delay(1_000)
                    val s = f.stats()
                    state.value = state.value.copy(
                        peers = s.peers, found = s.found, bytesPerSecond = s.bytesPerSecond, keptBytes = s.keptBytes, playedBytes = srv.served.get(),
                    )
                }
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

    /** Free space never touched, and the most of a film kept on the device at a time. */
    private const val RESERVE = 300L * 1024 * 1024
    private const val MAX_KEPT = 6L * 1024 * 1024 * 1024
    private const val TICK_MS = 500L

    /** A minute and a half with nothing arriving. */
    private const val QUIET_TICKS = 180
    private const val END_WAIT_NS = 6_000_000_000L
}
