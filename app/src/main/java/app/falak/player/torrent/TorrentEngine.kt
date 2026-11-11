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
 * play). libtorrent (through libtorrent4j) fetches one torrent at a time into [dir].
 *
 * It works as a stream, not a download (8.17: "like Stremio"): only the part of the film around
 * where the player is reading is asked for (see [TorrentFile]), within a limit on how much is kept
 * on the device, so a film bigger than the free space still plays. Nothing is kept afterwards:
 * [close] and [stop] delete what was fetched, and so does the next [start] (after a crash).
 *
 * No Android here: it runs in a plain JVM test with two engines talking to each other.
 */
class TorrentEngine(private val dir: File) {
    private val session = SessionManager(false)

    @Volatile
    private var started = false

    /** Whether people are looked for out on the internet (not in the test, where the other side is given). */
    private var lookOutside = true

    /** [listen]: the address to listen on (tests use "127.0.0.1:46882"), else every interface. */
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
            // Only a stretch of the film is wanted at a time: with nothing wanted for a moment, the
            // people who have all of it must not be hung up on (they are needed again right away).
            .setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.close_redundant_connections.swigValue(), false)
        if (listen != null) sp.listenInterfaces(listen)
        lookOutside = findPeers
        if (findPeers) {
            sp.setDhtBootstrapNodes(DHT_START)
        } else {
            sp.setEnableDht(false)
            sp.setEnableLsd(false)
        }
        session.start(SessionParams(sp))
        started = true
    }

    val running: Boolean get() = started

    /** How many DHT nodes are known (0 for long: the network isn't letting torrents through). */
    fun dhtNodes(): Long = runCatching { session.dhtNodes() }.getOrDefault(0L)

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        runCatching { session.stop() }
        dir.deleteRecursively()
    }

    /**
     * Opens a torrent link: finds people sharing it, reads its description (what files it holds)
     * and picks the film; from then on [TorrentFile] fetches what the player reads. Takes a while
     * (call it off the main thread); gives up after [timeoutMs] with a [TorrentException] that says
     * why. [progress] hears what is happening and how many people were found and connected;
     * [cancelled] is asked often. [maxBytes]: how much of the film may be on the device at a time.
     */
    fun open(
        source: TorrentSource,
        maxBytes: Long = Long.MAX_VALUE,
        timeoutMs: Long = 60_000,
        cancelled: () -> Boolean = { false },
        /** Tests: someone known to have it. */
        peer: TcpEndpoint? = null,
        progress: (note: String, found: Int, connected: Int) -> Unit = { _, _, _ -> },
    ): TorrentFile {
        if (!started) throw TorrentException("Torrent playback isn't running.")
        val hash = Sha1Hash.parseHex(source.infoHash)
        progress("Opening the link…", 0, 0)
        val folder = File(dir, "g0")
        // (Nothing is fetched until the film has been picked out of it.)
        val magnet = source.magnet(if (lookOutside) TorrentSource.PUBLIC_TRACKERS else emptyList())
        session.download(magnet, folder, TorrentFlags.SEQUENTIAL_DOWNLOAD.or_(TorrentFlags.DEFAULT_DONT_DOWNLOAD))
        val handle = await(10_000, cancelled) { session.find(hash) }
            ?: throw TorrentException("This link couldn't be opened.")
        try {
            if (peer != null) handle.swig().connect_peer(peer.swig())
            var found = 0
            var connected = 0
            await(timeoutMs, cancelled) {
                val s = handle.status()
                found = maxOf(found, s.listPeers())
                connected = maxOf(connected, s.numPeers())
                progress("Looking for people sharing it…", found, connected)
                if (s.hasMetadata()) true else null
            } ?: throw TorrentException(
                when {
                    cancelled() -> "Cancelled."
                    found == 0 -> "Nobody sharing this link was found. Try another one. (If none ever work, this network may be blocking torrents.)"
                    connected == 0 -> "$found people share this link, but none of them could be reached. Try another one."
                    else -> "The people sharing this link didn't answer in time. Try another one."
                },
            )
            val info = handle.torrentFile() ?: throw TorrentException("This link couldn't be read.")
            val index = pickFile(info, source) ?: throw TorrentException("There is no film in this link.")
            return TorrentFile(this, info, index, handle, folder, maxBytes, listOfNotNull(peer)).also { it.begin() }
        } catch (e: Throwable) {
            remove(handle)
            throw e
        }
    }

    /** Stops fetching it and deletes what was fetched. */
    fun close(file: TorrentFile) {
        remove(file.handle)
        runCatching { dir.listFiles()?.forEach { it.deleteRecursively() } }
    }

    /**
     * The same torrent again in an empty [folder], the old copy deleted: how the film's older
     * parts make room for the ones the player needs next (see [TorrentFile]).
     */
    internal fun again(old: TorrentHandle, info: TorrentInfo, index: Int, folder: File, peers: List<TcpEndpoint>): TorrentHandle {
        val hash = old.infoHash()
        remove(old)
        val files = Array(info.numFiles()) { if (it == index) Priority.DEFAULT else Priority.IGNORE }
        session.download(info, folder, null, files, peers, TorrentFlags.SEQUENTIAL_DOWNLOAD)
        return await(10_000, { false }) { session.find(hash) } ?: throw TorrentException("The film couldn't be carried on.")
    }

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
        private const val DHT_START = "router.bittorrent.com:6881,dht.transmissionbt.com:6881,router.utorrent.com:6881,dht.libtorrent.org:25401"
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
class TorrentStats(
    /** People connected to right now. */
    val peers: Int,
    /** People known to share it. */
    val found: Int,
    val bytesPerSecond: Int,
    /** How much of the film is on the device now. */
    val keptBytes: Long,
)

/**
 * The film inside a torrent, fetched as a stream: only a stretch ahead of where the player is
 * reading is asked for ([want] moves it along), plus the very end (many files keep their index
 * there). At most [maxBytes] of it are on the device at a time: when that is used up and the
 * player needs more, the torrent is started again in an empty folder ([TorrentEngine.again]) and
 * carries on from where the player is; what was behind is gone (seeking back fetches it again).
 * [generation] counts those restarts: [path] changes with each.
 *
 * Thread-safe: the local server's connections all call it.
 */
class TorrentFile internal constructor(
    private val engine: TorrentEngine,
    private val info: TorrentInfo,
    val index: Int,
    handle: TorrentHandle,
    folder: File,
    maxBytes: Long,
    private val knownPeers: List<TcpEndpoint>,
) : GrowingFile {
    private val files = info.files()
    override val name: String = files.fileName(index)
    override val size: Long = files.fileSize(index)

    private val pieceLength = info.pieceLength().toLong()

    /** Where the film starts inside the torrent (its files lie end to end). */
    private val base = files.fileOffset(index)
    private val firstPiece = (base / pieceLength).toInt()
    private val lastPiece = ((base + size - 1) / pieceLength).toInt()
    private val pieces = lastPiece - firstPiece + 1

    /** How many pieces may be on the device at a time, and how many ahead of the player are asked for. */
    private val budget: Int = (maxBytes / pieceLength).coerceAtMost(pieces.toLong()).toInt()
    private val ahead: Int = (minOf(AHEAD_BYTES, maxBytes / 2) / pieceLength).toInt().coerceIn(MIN_AHEAD, maxOf(MIN_AHEAD, budget - TAIL - 1))

    private val lock = Any()

    @Volatile
    internal var handle: TorrentHandle = handle
        private set

    @Volatile
    override var generation: Int = 0
        private set

    @Volatile
    override var path: File = File(files.filePath(index, folder.absolutePath))
        private set

    private val root: File = folder.parentFile ?: folder

    /** The pieces asked for since the last restart (they are, or soon will be, on the device). */
    private val asked = BooleanArray(pieces)
    private var askedCount = 0
    private var lastWant = -1
    private var lastWantAt = 0L
    private var playing = -1

    init {
        if (budget < MIN_AHEAD + TAIL + 1) {
            throw TorrentException("Not enough free space to play this film: about ${(pieceLength * (MIN_AHEAD + TAIL + 2) shr 20) + 1} MB are needed.")
        }
    }

    private fun pieceAt(offset: Long): Int = ((base + offset) / pieceLength).toInt().coerceIn(firstPiece, lastPiece)
    private fun isTail(piece: Int) = pieces > 8 && piece > lastPiece - TAIL

    /** The film is picked: its file is the one written to, and nothing of it is asked for yet. */
    internal fun begin() {
        synchronized(lock) {
            handle.prioritizeFiles(Array(info.numFiles()) { if (it == index) Priority.DEFAULT else Priority.IGNORE })
            clearAsked()
            startFrom(firstPiece)
        }
    }

    /** (After the file priorities: those put every piece of the film back on the list.) */
    private fun clearAsked() {
        handle.prioritizePieces(Array(info.numPieces()) { Priority.IGNORE })
        asked.fill(false)
        askedCount = 0
    }

    /** The stretch from [piece] and the end of the film, the nearest pieces soonest. */
    private fun startFrom(piece: Int) {
        playing = piece
        if (pieces > 8) for (i in lastPiece - TAIL + 1..lastPiece) ask(i, 1_500)
        stretch(piece)
    }

    private fun ask(piece: Int, deadlineMs: Int): Boolean {
        val i = piece - firstPiece
        if (!asked[i]) {
            if (askedCount >= budget) return false
            asked[i] = true
            askedCount++
            handle.piecePriority(piece, Priority.DEFAULT)
        }
        if (deadlineMs > 0 && !handle.havePiece(piece)) handle.setPieceDeadline(piece, deadlineMs)
        return true
    }

    /** Asks for the [ahead] pieces from [from] (as far as the budget goes), the first few with a deadline. */
    private fun stretch(from: Int) {
        var urgent = 0
        var i = from
        while (i <= lastPiece && i < from + ahead) {
            val soon = if (urgent < URGENT && !handle.havePiece(i)) 400 + urgent++ * 250 else 0
            if (!ask(i, soon)) break
            i++
        }
    }

    /** How much of the film has to be here before the player starts (one piece is often megabytes). */
    val startBytes: Long get() = minOf(size, maxOf(START_BYTES, minOf(pieceLength, 4 * START_BYTES)))

    /** 0–1: how much of [startBytes] is here. */
    fun startProgress(): Float = (readyRun(0, startBytes).toFloat() / startBytes).coerceIn(0f, 1f)

    /** The start of the film is here. */
    fun startReady(): Boolean = readyRun(0, startBytes) >= startBytes

    /** The end of the film is here too (players that need the index there start at once). */
    fun endReady(): Boolean = runCatching { handle.havePiece(lastPiece) }.getOrDefault(false)

    fun stats(): TorrentStats = try {
        val s = handle.status()
        TorrentStats(s.numPeers(), s.listPeers(), s.downloadPayloadRate(), minOf(s.totalDone(), askedCount * pieceLength))
    } catch (e: RuntimeException) {
        // (Being started afresh at this very moment.)
        TorrentStats(0, 0, 0, 0)
    }

    override fun readyFrom(offset: Long): Long = readyRun(offset, MAX_RUN)

    private fun readyRun(offset: Long, limit: Long): Long {
        if (offset < 0 || offset >= size) return 0
        val h = handle
        var piece = pieceAt(offset)
        try {
            if (!h.isValid || !h.havePiece(piece)) return 0
            val want = minOf(size, offset + limit)
            var end = minOf(size, (piece + 1) * pieceLength - base)
            while (end < want && piece < lastPiece && h.havePiece(piece + 1)) {
                piece++
                end = minOf(size, (piece + 1) * pieceLength - base)
            }
            return minOf(end, want) - offset
        } catch (e: RuntimeException) {
            // (Being started afresh at this very moment: nothing is ready.)
            return 0
        }
    }

    override fun want(offset: Long, waiting: Boolean) {
        synchronized(lock) {
            val piece = pieceAt(offset)
            val now = System.nanoTime()
            if (piece == lastWant && now - lastWantAt < (if (waiting) 1_000_000_000L else 3_000_000_000L)) return
            lastWant = piece
            lastWantAt = now
            if (isTail(piece)) {
                // The player peeking at the end for the index: the film itself carries on where it was.
                for (i in piece..lastPiece) ask(i, 500)
                return
            }
            // A seek: what the old place still lacked is no longer wanted.
            if (playing >= 0 && (piece < playing || piece >= playing + ahead)) drop(piece)
            playing = piece
            if (waiting && !asked[piece - firstPiece] && askedCount >= budget) {
                // No room left for what the player needs now: start afresh from here.
                restart(piece)
                return
            }
            stretch(piece)
        }
    }

    /** Pieces asked for but not here yet, outside the stretch from [keepFrom] (and not the end): let go. */
    private fun drop(keepFrom: Int) {
        for (i in 0 until pieces) {
            val piece = firstPiece + i
            if (!asked[i] || isTail(piece) || (piece >= keepFrom && piece < keepFrom + ahead)) continue
            if (!handle.havePiece(piece)) {
                handle.piecePriority(piece, Priority.IGNORE)
                asked[i] = false
                askedCount--
            }
        }
    }

    private fun restart(from: Int) {
        val old = handle
        val peers = (knownPeers + runCatching { old.peerInfo().mapNotNull { endpoint(it.ip()) } }.getOrDefault(emptyList())).take(60)
        generation++
        val folder = File(root, "g$generation")
        val fresh = engine.again(old, info, index, folder, peers)
        handle = fresh
        path = File(files.filePath(index, folder.absolutePath))
        clearAsked()
        startFrom(from)
        // (libtorrent deletes the old folder's files; the folder itself goes here.)
        runCatching { File(root, "g${generation - 1}").deleteRecursively() }
    }

    private fun endpoint(text: String): TcpEndpoint? = runCatching {
        val host = text.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        val port = text.substringAfterLast(':').toInt()
        if (host.isEmpty() || port <= 0) null else TcpEndpoint(host, port)
    }.getOrNull()

    private companion object {
        const val START_BYTES = 2L * 1024 * 1024
        const val AHEAD_BYTES = 192L * 1024 * 1024
        const val MIN_AHEAD = 4
        const val URGENT = 8
        const val TAIL = 2
        const val MAX_RUN = 4L * 1024 * 1024
    }
}
