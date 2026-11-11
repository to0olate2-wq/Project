package app.falak.player.torrent

import java.io.File
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo

/**
 * Torrent playback (8.16, GitHub version only; the owner asked for it so add-ons such as Torrentio
 * play). libtorrent (through libtorrent4j) fetches one torrent at a time into [dir], in the order a
 * player reads a film: the start and the very end first (the end holds the index of many files),
 * then onwards from wherever the player is reading. Nothing is kept: [close] and [stop] delete
 * what was fetched, and so does the next [start] (after a crash).
 *
 * No Android here: it runs in a plain JVM test with two engines talking to each other.
 */
class TorrentEngine(private val dir: File) {
    private val session = SessionManager(false)

    @Volatile
    private var started = false

    /** [listen]: the address to listen on (tests use "127.0.0.1:0"), else every interface. */
    @Synchronized
    fun start(listen: String? = null, findPeers: Boolean = true) {
        if (started) return
        dir.deleteRecursively()
        dir.mkdirs()
        val sp = SettingsPack()
            .activeDownloads(2)
            .connectionsLimit(CONNECTIONS)
            // Sharing back is how torrents work, but a phone's or TV's uplink is for the film.
            .uploadRateLimit(UPLOAD_LIMIT)
        if (listen != null) sp.listenInterfaces(listen)
        if (!findPeers) {
            sp.setEnableDht(false)
            sp.setEnableLsd(false)
        }
        session.start(SessionParams(sp))
        started = true
    }

    val running: Boolean get() = started

    /** Where this engine listens ("127.0.0.1:41234"), for tests that connect two engines. */
    fun listenEndpoints(): List<String> = session.listenEndpoints()

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        runCatching { session.stop() }
        dir.deleteRecursively()
    }

    /**
     * Opens a torrent link: finds people sharing it, reads its description (what files it holds),
     * picks the film and starts fetching it. Takes a while (call it off the main thread); gives up
     * after [timeoutMs] with a [TorrentException] that says why. [stage] hears "Looking for people
     * sharing it…" and the like; [cancelled] is asked often.
     */
    fun open(
        source: TorrentSource,
        timeoutMs: Long = 60_000,
        cancelled: () -> Boolean = { false },
        /** Tests: someone known to have it. */
        peer: TcpEndpoint? = null,
        stage: (String) -> Unit = {},
    ): TorrentFile {
        if (!started) throw TorrentException("Torrent playback isn't running.")
        val hash = Sha1Hash.parseHex(source.infoHash)
        stage("Opening the link…")
        session.download(source.magnet(), dir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
        val handle = await(10_000, cancelled) { session.find(hash) }
            ?: throw TorrentException("This link couldn't be opened.")
        try {
            if (peer != null) handle.swig().connect_peer(peer.swig())
            stage("Looking for people sharing it…")
            await(timeoutMs, cancelled) { if (handle.status().hasMetadata()) true else null }
                ?: throw TorrentException(if (cancelled()) "Cancelled." else "Nobody is sharing this link right now. Try another one.")
            val info = handle.torrentFile() ?: throw TorrentException("This link couldn't be read.")
            val index = pickFile(info, source) ?: throw TorrentException("There is no film in this link.")
            handle.prioritizeFiles(Array(info.numFiles()) { if (it == index) Priority.DEFAULT else Priority.IGNORE })
            return TorrentFile(handle, info, index, dir).also { it.prime() }
        } catch (e: Throwable) {
            remove(handle)
            throw e
        }
    }

    /** Stops fetching it and deletes what was fetched. */
    fun close(file: TorrentFile) = remove(file.handle)

    private fun remove(handle: TorrentHandle) {
        runCatching {
            val hash = handle.infoHash()
            session.remove(handle, SessionHandle.DELETE_FILES)
            // It goes a moment later: the same link opened again straight away must not find the old one.
            await(5_000, { false }) { if (session.find(hash) == null) true else null }
        }
    }

    private fun <T : Any> await(timeoutMs: Long, cancelled: () -> Boolean, check: () -> T?): T? {
        val until = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            check()?.let { return it }
            if (cancelled() || System.nanoTime() >= until) return null
            Thread.sleep(200)
        }
    }

    companion object {
        private const val CONNECTIONS = 150
        private const val UPLOAD_LIMIT = 96 * 1024
        private val VIDEO = setOf("mkv", "mp4", "avi", "m4v", "mov", "ts", "m2ts", "webm", "wmv", "mpg", "mpeg", "flv")

        /**
         * The file to play: the one the add-on named (by number, else by name), else the biggest
         * video (a film's torrent often carries samples, subtitles and pictures too).
         */
        internal fun pickFile(info: TorrentInfo, source: TorrentSource): Int? {
            val files = info.files()
            val n = files.numFiles()
            fun real(i: Int) = i in 0 until n && !files.padFileAt(i) && files.fileSize(i) > 0
            source.fileIdx?.takeIf { real(it) }?.let { return it }
            if (source.fileName.isNotBlank()) {
                val wanted = source.fileName.substringAfterLast('/').lowercase()
                (0 until n).firstOrNull { real(it) && files.fileName(it).lowercase() == wanted }?.let { return it }
            }
            val candidates = (0 until n).filter { real(it) }
            val videos = candidates.filter { files.fileName(it).substringAfterLast('.', "").lowercase() in VIDEO }
            return (videos.ifEmpty { candidates }).maxByOrNull { files.fileSize(it) }
        }
    }
}

/** How a torrent's film is coming along. */
class TorrentStats(val peers: Int, val seeds: Int, val bytesPerSecond: Int)

/**
 * The film inside a torrent, as it arrives. Thread-safe: the local server's connections all call it.
 */
class TorrentFile internal constructor(
    internal val handle: TorrentHandle,
    info: TorrentInfo,
    val index: Int,
    saveDir: File,
) : GrowingFile {
    private val files = info.files()
    override val name: String = files.fileName(index)
    override val size: Long = files.fileSize(index)
    override val path: File = File(files.filePath(index, saveDir.absolutePath))

    private val pieceLength = info.pieceLength().toLong()

    /** Where the film starts inside the torrent (its files lie end to end). */
    private val base = files.fileOffset(index)
    private val firstPiece = (base / pieceLength).toInt()
    private val lastPiece = ((base + size - 1) / pieceLength).toInt()

    private var askedPiece = -1
    private var askedAt = 0L
    private var playingFrom = -1

    private fun pieceAt(offset: Long): Int = ((base + offset) / pieceLength).toInt().coerceIn(firstPiece, lastPiece)

    /** The start and the end of the film first. */
    internal fun prime() {
        ask(firstPiece, START_PIECES)
        for (i in maxOf(firstPiece, lastPiece - 1)..lastPiece) handle.setPieceDeadline(i, 1_000)
    }

    /** How much of the film has to be here before the player starts. */
    val startBytes: Long get() = minOf(size, maxOf(START_BYTES, pieceLength * 2))

    /** 0–1: how much of [startBytes] is here. */
    fun startProgress(): Float = (readyRun(0, startBytes).toFloat() / startBytes).coerceIn(0f, 1f)

    /** Ready to play: the start is here, and the end too (or so much of the start that waiting longer is pointless). */
    fun readyToPlay(): Boolean {
        val head = readyRun(0, startBytes * 3)
        return head >= startBytes && (handle.havePiece(lastPiece) || head >= minOf(size, startBytes * 3))
    }

    fun stats(): TorrentStats {
        val s = handle.status()
        return TorrentStats(s.numPeers(), s.numSeeds(), s.downloadPayloadRate())
    }

    override fun readyFrom(offset: Long): Long = readyRun(offset, MAX_RUN)

    private fun readyRun(offset: Long, limit: Long): Long {
        if (offset < 0 || offset >= size) return 0
        var piece = pieceAt(offset)
        if (!handle.havePiece(piece)) return 0
        val want = minOf(size, offset + limit)
        var end = minOf(size, (piece + 1) * pieceLength - base)
        while (end < want && piece < lastPiece && handle.havePiece(piece + 1)) {
            piece++
            end = minOf(size, (piece + 1) * pieceLength - base)
        }
        return minOf(end, want) - offset
    }

    @Synchronized
    override fun want(offset: Long) {
        val piece = pieceAt(offset)
        val now = System.nanoTime()
        if (piece == askedPiece && now - askedAt < 3_000_000_000L) return
        askedPiece = piece
        askedAt = now
        // The film carries on from here (not when the player only peeks at the end for the index).
        val tail = piece >= lastPiece - 2 && lastPiece - firstPiece > 8
        if (!tail && (playingFrom < 0 || piece < playingFrom || piece > playingFrom + WINDOW * 2)) {
            runCatching { handle.setSequentialRange(piece, lastPiece) }
        }
        if (!tail) playingFrom = piece
        ask(piece, WINDOW)
    }

    /** The next [count] pieces from [from] that aren't here yet: wanted now, the nearest soonest. */
    private fun ask(from: Int, count: Int) {
        var k = 0
        var i = from
        while (i <= lastPiece && k < count) {
            if (!handle.havePiece(i)) {
                handle.setPieceDeadline(i, 400 + k * 250)
                k++
            }
            i++
        }
    }

    private companion object {
        const val START_BYTES = 8L * 1024 * 1024
        const val START_PIECES = 16
        const val WINDOW = 12
        const val MAX_RUN = 4L * 1024 * 1024
    }
}
