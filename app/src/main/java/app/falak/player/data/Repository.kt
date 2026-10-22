package app.falak.player.data

import app.falak.player.FreezeWatch
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

@OptIn(ExperimentalSerializationApi::class)
class Repository(private val context: Context) {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val stateFile = File(context.filesDir, "state.json")
    private val cacheDir = File(context.filesDir, "cache").apply { mkdirs() }
    private val importDir = File(context.filesDir, "imports").apply { mkdirs() }

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _epg = MutableStateFlow<Map<String, List<Program>>>(emptyMap())
    val epg: StateFlow<Map<String, List<Program>>> = _epg.asStateFlow()

    /** Per-source results of the open playlist's last guide download (Settings → Guide sources). */
    private val _guideStats = MutableStateFlow<List<GuideSourceStat>>(emptyList())
    val guideStats: StateFlow<List<GuideSourceStat>> = _guideStats.asStateFlow()

    /** Set right before opening the player: the list to zap through and where it came from. */
    @Volatile var playQueue: List<Channel> = emptyList()
    @Volatile var currentPlaylist: Playlist? = null
    /** The series whose episodes are in [playQueue], if any (for "Continue watching"). */
    @Volatile var currentSeries: Channel? = null
    /** The whole open playlist, so the player can browse groups without going back. */
    @Volatile var currentIndex: LibraryIndex = LibraryIndex.EMPTY
    /** Groups the user hid or that are PIN-locked right now; the player hides them too. */
    @Volatile var excludedGroups: Set<String> = emptySet()
    /** Last channel/video shown in the player, so the lists can return to it. */
    @Volatile var lastPlayedId: String? = null

    /** The player is on screen: heavy background work (playlist refresh) waits until it closes. */
    @Volatile var playerOpen = false

    /** The guide is playing a small preview (counts as playing for background work). */
    @Volatile var previewOpen = false

    /** A page the player asked to open when it closed ("MOVIES", "SETTINGS"…), for the main screen. */
    @Volatile var pendingNav: String? = null

    /** With [pendingNav]: the item that was playing, to go back to when leaving that page. */
    @Volatile var pendingReturnIndex: Int? = null

    /** The "start with the last channel" check has run in this process (it only runs on a fresh start). */
    @Volatile var autoPlayChecked = false

    /** Remembers the live channel being watched, to start with it next time. */
    fun setLastLive(playlistId: String, channelId: String) {
        if (_state.value.lastLive[playlistId] != channelId) update { it.copy(lastLive = it.lastLive + (playlistId to channelId)) }
    }

    /**
     * The open playlist, ready to show, kept for as long as the app is in memory: closing and
     * reopening the app (or its main screen being rebuilt) shows it at once, with no reload.
     */
    class OpenLibrary(val playlistId: String, val index: LibraryIndex, val epgUrl: String, val serverTz: String, val savedAt: Long)

    @Volatile var openLibrary: OpenLibrary? = null

    /** Which playlist the guide in memory belongs to, when it was saved, and which channels it covers. */
    private class GuideMemo(val playlistId: String, val savedAt: Long, val scope: Set<String>, val matched: Int, val total: Int)

    @Volatile private var guideMemo: GuideMemo? = null

    /** The guide in memory is this playlist's. */
    fun hasGuideFor(playlistId: String): Boolean = guideMemo?.playlistId == playlistId

    /**
     * Returns (and clears) the crash saved by FalakApp on the previous run, or else a report of a
     * stop Android recorded that the app couldn't see itself (native crash, freeze, memory).
     */
    fun takeCrashReport(): String? {
        val header = "The Player ${app.falak.player.BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE} " +
            "(SDK ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n\n"
        var unexpected = ExitReasons.takeUnexpected(context, header) // always read, so it's only shown once
        // The app's own record of a freeze has the whole stuck call chain and the last steps.
        val freeze = FreezeWatch.take(context.filesDir, anr = unexpected?.contains("(ANR)") == true)
        if (freeze != null) unexpected = (unexpected ?: header) + "\n\n" + freeze
        val f = File(context.filesDir, CRASH_FILE)
        if (!f.exists()) return unexpected
        return try { f.readText() } catch (e: Exception) { null } finally { f.delete() }
    }

    // ---- saved state (playlists, favorites, recents) ----

    private fun readState(): AppState = try {
        if (stateFile.exists()) json.decodeFromString(AppState.serializer(), stateFile.readText()) else AppState()
    } catch (e: Exception) {
        AppState()
    }

    /** Changes apply instantly in memory; the file is written shortly after on a background thread. */
    fun update(transform: (AppState) -> AppState) {
        synchronized(this) { _state.value = transform(_state.value) }
        scheduleWrite()
    }

    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "state-writer").apply { priority = Thread.MIN_PRIORITY }
    }
    private val writePending = AtomicBoolean(false)

    private fun scheduleWrite() {
        if (!writePending.compareAndSet(false, true)) return
        writer.execute {
            try {
                Thread.sleep(300) // several quick taps -> one write
            } catch (e: InterruptedException) {
                // write now
            }
            writePending.set(false)
            try {
                val tmp = File(stateFile.path + ".tmp")
                tmp.writeText(json.encodeToString(AppState.serializer(), _state.value))
                if (!tmp.renameTo(stateFile)) {
                    stateFile.delete()
                    tmp.renameTo(stateFile)
                }
            } catch (e: Throwable) {
                // a full disk must never crash the app; the next change tries again
            }
        }
    }

    /**
     * One low-priority thread for heavy background work (guide downloads, quiet playlist refreshes):
     * jobs run one at a time and never compete with the screen for the CPU.
     */
    val background: CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "background-work")
    }.asCoroutineDispatcher()

    fun addPlaylist(p: Playlist) = update { it.copy(playlists = it.playlists + p, lastPlaylistId = p.id) }

    fun removePlaylist(id: String) {
        update { s ->
            s.copy(
                playlists = s.playlists.filterNot { it.id == id },
                favorites = s.favorites - id,
                favLists = s.favLists - id,
                settings = s.settings.copy(
                    guideOverrides = s.settings.guideOverrides - id,
                    hiddenGroups = s.settings.hiddenGroups - id,
                ),
                recents = s.recents - id,
                lastPlaylistId = s.lastPlaylistId.takeIf { it != id },
                notices = s.notices.filterNot { it.playlistId == id },
                lastLive = s.lastLive - id,
            )
        }
        if (openLibrary?.playlistId == id) openLibrary = null
        File(cacheDir, "$id.json").delete()
        File(cacheDir, "$id.bin").delete()
        File(cacheDir, "$id.guide.json").delete()
        File(cacheDir, "$id.guide.bin").delete()
        File(importDir, "$id.m3u").delete()
    }

    fun setLast(id: String) {
        if (_state.value.lastPlaylistId != id) update { it.copy(lastPlaylistId = id) }
    }

    fun toggleFavorite(playlistId: String, channelId: String) = update { s ->
        val cur = s.favorites[playlistId].orEmpty()
        val next = if (channelId in cur) cur - channelId else cur + channelId
        s.copy(favorites = s.favorites + (playlistId to next))
    }

    // ---- the user's own lists ----

    /** Ids in a list; [DEFAULT_LIST] is the built-in Favourites. */
    fun listIds(s: AppState, playlistId: String, listId: String): List<String> =
        if (listId == DEFAULT_LIST) s.favorites[playlistId].orEmpty()
        else s.favLists[playlistId].orEmpty().firstOrNull { it.id == listId }?.ids.orEmpty()

    private fun editList(playlistId: String, listId: String, edit: (List<String>) -> List<String>) = update { s ->
        if (listId == DEFAULT_LIST) {
            s.copy(favorites = s.favorites + (playlistId to edit(s.favorites[playlistId].orEmpty())))
        } else {
            val lists = s.favLists[playlistId].orEmpty().map { if (it.id == listId) it.copy(ids = edit(it.ids)) else it }
            s.copy(favLists = s.favLists + (playlistId to lists))
        }
    }

    fun setInList(playlistId: String, listId: String, channelId: String, inList: Boolean) =
        editList(playlistId, listId) { ids -> if (inList) (if (channelId in ids) ids else ids + channelId) else ids - channelId }

    /** Moves an item up (negative) or down; [toTop] puts it first. */
    fun moveInList(playlistId: String, listId: String, channelId: String, delta: Int, toTop: Boolean = false) =
        editList(playlistId, listId) { ids ->
            val i = ids.indexOf(channelId)
            if (i < 0) return@editList ids
            val next = ids.toMutableList()
            next.removeAt(i)
            next.add(if (toTop) 0 else (i + delta).coerceIn(0, next.size), channelId)
            next
        }

    fun createList(playlistId: String, name: String, firstId: String? = null): String {
        val id = "l" + java.util.UUID.randomUUID().toString().take(8)
        update { s ->
            val lists = s.favLists[playlistId].orEmpty() + FavList(id, name.trim().ifEmpty { "My list" }, listOfNotNull(firstId))
            s.copy(favLists = s.favLists + (playlistId to lists))
        }
        return id
    }

    fun renameList(playlistId: String, listId: String, name: String) = update { s ->
        val lists = s.favLists[playlistId].orEmpty().map { if (it.id == listId) it.copy(name = name.trim().ifEmpty { it.name }) else it }
        s.copy(favLists = s.favLists + (playlistId to lists))
    }

    fun deleteList(playlistId: String, listId: String) = update { s ->
        s.copy(favLists = s.favLists + (playlistId to s.favLists[playlistId].orEmpty().filterNot { it.id == listId }))
    }

    /** Moves a whole list left/right among the user's lists. */
    fun moveList(playlistId: String, listId: String, delta: Int) = update { s ->
        val lists = s.favLists[playlistId].orEmpty().toMutableList()
        val i = lists.indexOfFirst { it.id == listId }
        if (i < 0) return@update s
        val l = lists.removeAt(i)
        lists.add((i + delta).coerceIn(0, lists.size), l)
        s.copy(favLists = s.favLists + (playlistId to lists))
    }

    fun updateSettings(transform: (Settings) -> Settings) = update { it.copy(settings = transform(it.settings)) }

    // ---- What's new ----

    /** Records what a refresh added to [p] and returns the entry. */
    fun addNotice(p: Playlist, added: List<Channel>): Notice {
        val groups = added.groupingBy { it.kind to it.group }.eachCount().entries
            .sortedByDescending { it.value }.take(8)
            .map { (k, n) -> NoticeGroup(k.first, k.second, n) }
        // Films and series with pictures first: they make the best preview.
        val items = added.sortedByDescending { (if (it.kind != Kind.LIVE) 2 else 0) + (if (it.logo.isNotBlank()) 1 else 0) }
            .take(12).map { it.id }
        val notice = Notice(
            id = System.currentTimeMillis(),
            playlistId = p.id,
            playlistName = p.name,
            live = added.count { it.kind == Kind.LIVE },
            films = added.count { it.kind == Kind.MOVIE },
            series = added.count { it.kind == Kind.SERIES },
            groups = groups,
            items = items,
        )
        update { s -> s.copy(notices = (listOf(notice) + s.notices).take(40)) }
        return notice
    }

    fun markNoticesRead() {
        if (_state.value.notices.any { !it.read }) update { s -> s.copy(notices = s.notices.map { it.copy(read = true) }) }
    }

    fun clearNotices() = update { it.copy(notices = emptyList()) }

    /** Hides or shows whole groups of a playlist (from this device or the phone page). */
    fun setGroupsHidden(playlistId: String, groups: Collection<String>, hidden: Boolean) = updateSettings { s ->
        val cur = LinkedHashSet(s.hiddenGroups[playlistId].orEmpty())
        if (hidden) cur += groups else cur -= groups.toSet()
        s.copy(hiddenGroups = s.hiddenGroups + (playlistId to cur.toList()))
    }

    /** A saved playlist's groups per kind with item counts, or null if it was never loaded here. */
    fun savedGroups(playlistId: String): Map<Kind, LinkedHashMap<String, Int>>? {
        val f = File(cacheDir, "$playlistId.bin")
        if (!f.exists()) return null
        return try {
            Store.readGroups(f)
        } catch (e: Exception) {
            null
        }
    }

    /** pos = 0 clears the entry (finished or barely started). Keeps the most recent 300. */
    fun saveProgress(key: String, pos: Long, dur: Long, ch: Channel? = null, seriesId: String = "") = update { s ->
        val next = LinkedHashMap(s.progress)
        next.remove(key)
        if (pos > 0) {
            next[key] = Watch(
                pos = pos, dur = dur,
                title = ch?.name.orEmpty(), image = ch?.logo.orEmpty(), url = ch?.url.orEmpty(),
                kind = ch?.kind ?: Kind.MOVIE, seriesId = seriesId,
            )
        }
        while (next.size > 300) next.remove(next.keys.first())
        s.copy(progress = next)
    }

    /** Xtream access for this playlist: Xtream logins and get.php-style M3U links. */
    fun xtreamFor(p: Playlist): XtreamApi? = when (p.type) {
        SourceType.XTREAM -> XtreamApi(http, p.url, p.username, p.password, p.userAgent)
        SourceType.M3U_URL -> XtreamApi.fromM3uLink(p.url)?.let { (server, user, pass) ->
            XtreamApi(http, server, user, pass, p.userAgent)
        }
        SourceType.M3U_FILE -> null
    }

    suspend fun loadVodInfo(p: Playlist, streamId: String): VodInfo? = withContext(Dispatchers.IO) {
        xtreamFor(p)?.vodInfo(streamId)
    }

    suspend fun loadSeries(p: Playlist, seriesId: String): SeriesInfo = withContext(Dispatchers.IO) {
        val api = xtreamFor(p) ?: throw IOException("Episodes need an Xtream login for this playlist")
        api.seriesInfo(seriesId)
    }

    fun addRecent(playlistId: String, channelId: String) = update { s ->
        val cur = s.recents[playlistId].orEmpty()
        s.copy(recents = s.recents + (playlistId to (listOf(channelId) + (cur - channelId)).take(40)))
    }

    // ---- loading ----

    suspend fun importFile(uri: Uri, id: String): String = withContext(Dispatchers.IO) {
        val dest = File(importDir, "$id.m3u")
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open that file")
        input.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        dest.absolutePath
    }

    suspend fun xtreamLogin(server: String, user: String, pass: String, ua: String) =
        withContext(Dispatchers.IO) { XtreamApi(http, server, user, pass, ua).login() }

    /** The saved copy of a playlist, or null if there is none (or it can't be read). Fast. */
    suspend fun cachedChannels(p: Playlist): PlaylistCache? = withContext(Dispatchers.IO) {
        val bin = File(cacheDir, "${p.id}.bin")
        if (bin.exists()) {
            try {
                return@withContext Store.readPlaylist(bin)
            } catch (e: Exception) {
                bin.delete()
            }
        }
        // Caches from before 3.2 were JSON: read once, then switch to the fast format.
        val old = File(cacheDir, "${p.id}.json")
        if (!old.exists()) return@withContext null
        try {
            val c = old.inputStream().buffered().use { json.decodeFromStream(PlaylistCache.serializer(), it) }
            val stamped = c.copy(savedAt = old.lastModified())
            runCatching { Store.writePlaylist(bin, stamped) }.onSuccess { old.delete() }
            stamped
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns the cached list unless [refresh] is true or there is no cache yet. [keep] is the
     * list already in memory: unchanged entries are reused instead of being held twice.
     */
    suspend fun loadChannels(
        p: Playlist, refresh: Boolean, quietly: Boolean = false, keep: Map<String, Channel> = emptyMap(),
    ): PlaylistCache =
        withContext(if (quietly) background else Dispatchers.IO) {
        if (!refresh) cachedChannels(p)?.let { return@withContext it }
        val reuse = Reuse.from(keep)
        val result = when (p.type) {
            SourceType.M3U_URL -> loadXtreamStyleLink(p, reuse) ?: downloadM3u(p, reuse)
            SourceType.M3U_FILE -> {
                val parsed = M3uParser.parse(File(p.url).bufferedReader(), reuse)
                PlaylistCache(parsed.channels, parsed.epgUrl)
            }
            SourceType.XTREAM -> {
                val api = XtreamApi(http, p.url, p.username, p.password, p.userAgent)
                PlaylistCache(api.loadAll(reuse), api.epgUrl, api.serverTimezone())
            }
        }.copy(savedAt = System.currentTimeMillis())
        if (result.channels.isEmpty()) throw IOException("No channels found in this playlist")
        try {
            Store.writePlaylist(File(cacheDir, "${p.id}.bin"), result)
            File(cacheDir, "${p.id}.json").delete()
        } catch (e: Throwable) {
            File(cacheDir, "${p.id}.bin").delete() // the cache is only a speed-up
        }
        result
    }

    /**
     * Most provider links look like http://host:port/get.php?username=…&password=….
     * Many providers block get.php downloads (e.g. HTTP 884), so load those through the
     * Xtream API instead. Returns null when the link isn't that kind or the API doesn't answer.
     */
    private fun loadXtreamStyleLink(p: Playlist, reuse: Reuse): PlaylistCache? {
        val (server, user, pass) = XtreamApi.fromM3uLink(p.url) ?: return null
        return try {
            val api = XtreamApi(http, server, user, pass, p.userAgent)
            val list = api.loadAll(reuse)
            if (list.isEmpty()) null else PlaylistCache(list, api.epgUrl, api.serverTimezone())
        } catch (e: Exception) {
            null // fall back to downloading the M3U file
        }
    }

    /** Downloads an M3U file, retrying with other common player User-Agents if the provider refuses. */
    private fun downloadM3u(p: Playlist, reuse: Reuse): PlaylistCache {
        val agents = if (p.userAgent.isNotBlank()) listOf(p.userAgent) else FALLBACK_UAS
        var lastCode = 0
        for (ua in agents) {
            val req = Request.Builder().url(p.url.trim()).header("User-Agent", ua).build()
            http.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body ?: throw IOException("Empty playlist")
                    val parsed = M3uParser.parse(body.charStream().buffered(), reuse)
                    return PlaylistCache(parsed.channels, parsed.epgUrl)
                }
                lastCode = res.code
            }
        }
        throw IOException(
            if (lastCode == 404) "Playlist not found (HTTP 404). Check the link."
            else "Your provider refused the playlist download (HTTP $lastCode). " +
                "Try adding it with the Xtream option, or check the subscription is active."
        )
    }

    /**
     * TV guide for a playlist: the provider's guide plus any extra sources, matched by id or
     * channel name (see [GuideMatcher]) and merged (first source with data for a channel wins).
     * Uses the disk cache unless [force] or it is older than 8 hours. Returns a short status line.
     */
    suspend fun loadGuide(p: Playlist, channels: List<Channel>, providerUrl: String, force: Boolean): String =
        withContext(background) {
            val file = File(cacheDir, "${p.id}.guide.bin")
            File(cacheDir, "${p.id}.guide.json").delete() // pre-3.2 format
            val now = System.currentTimeMillis()
            // [channels] are the visible ones: hidden groups get no guide (no matching, no memory).
            val overrides = _state.value.settings.guideOverrides[p.id].orEmpty()
            val matcher = GuideMatcher(channels, overrides)
            // Already in memory, fresh and covering these channels: nothing to read or download.
            guideMemo?.let { m ->
                if (!force && m.playlistId == p.id && now - m.savedAt < 8 * 3_600_000L && matcher.keys.all { it in m.scope } && _epg.value.isNotEmpty()) {
                    return@withContext guideStatus(GuideCache(m.savedAt, emptyMap(), m.matched, m.total))
                }
            }
            var hadGuide = false
            if (file.exists()) {
                val cached = try {
                    Store.readGuide(file, only = matcher.keys)
                } catch (e: Exception) {
                    null
                }
                if (cached != null) {
                    hadGuide = cached.programs.isNotEmpty()
                    setRawGuide(cached.programs)
                    guideChannels = cached.channels
                    publishGuide(p.epgShiftMinutes)
                    _guideStats.value = readGuideStats(p.id)
                    // A group shown again since the last download has channels the saved guide
                    // never looked for: download again rather than leave them empty.
                    val covers = matcher.keys.all { it in cached.scope }
                    guideMemo = GuideMemo(p.id, cached.savedAt, cached.scope, cached.matched, cached.total)
                    if (!force && covers && now - cached.savedAt < 8 * 3_600_000L) return@withContext guideStatus(cached)
                }
            }
            val sources = (listOf(providerUrl) + p.extraEpg).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (sources.isEmpty()) return@withContext "No TV guide for this playlist yet"
            if (matcher.keys.isEmpty()) return@withContext ""
            val merged = HashMap<String, List<Program>>()
            val listed = ArrayList<GuideChannel>()
            val failed = ArrayList<String>()
            val stats = ArrayList<GuideSourceStat>()
            // The first guide with data is the yardstick the later ones are checked against.
            var reference = ""
            val job = coroutineContext[kotlinx.coroutines.Job]
            for (url in sources) {
                val isProvider = url == providerUrl.trim()
                val label = if (isProvider) "Your provider's guide" else sourceLabel(url)
                val host = url.substringAfter("://").substringBefore('/')
                coroutineContext.ensureActive()
                try {
                    val req = Request.Builder().url(url).header("User-Agent", p.userAgent.ifBlank { DEFAULT_UA }).build()
                    http.newCall(req).execute().use { res ->
                        val body = res.body
                        if (!res.isSuccessful || body == null) throw IOException("HTTP ${res.code}")
                        val applied = p.epgSourceShift[url] ?: 0
                        val part = Epg.parse(body.byteStream(), matcher, now, source = sourceLabel(url), shiftMs = applied * 60_000L) { job?.isActive == false }
                        coroutineContext.ensureActive() // a newer load replaced this one
                        var matched = 0
                        var added = 0
                        val tally = GuideCheck.Tally()
                        for ((key, list) in part.programs) {
                            if (list.isEmpty()) continue
                            matched++
                            val earlier = merged[key]
                            if (earlier.isNullOrEmpty()) {
                                merged[key] = list
                                added++
                            } else if (tally.compared < 400) {
                                // Both guides cover this channel: do they list the same shows at the same times?
                                tally.add(GuideCheck.channelOffset(earlier, list, now))
                            }
                        }
                        listed += part.channels
                        stats += GuideSourceStat(
                            url, label, isProvider, matched, added, ok = true,
                            compared = tally.compared, agree = tally.agree,
                            suggestShift = tally.suggestedShift, shiftVotes = tally.shiftVotes,
                            applied = applied, against = reference,
                        )
                        if (reference.isEmpty() && matched > 0) reference = label
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += if (isProvider) "provider guide" else sourceLabel(url)
                    stats += GuideSourceStat(url, label, isProvider, 0, 0, ok = false)
                }
                if (!hadGuide) {
                    // First guide ever: show what we have so far. Otherwise keep showing the saved
                    // guide until the new one is complete (no half-empty guide while refreshing).
                    setRawGuide(HashMap(merged))
                    publishGuide(p.epgShiftMinutes)
                }
            }
            if (hadGuide && merged.isNotEmpty()) {
                setRawGuide(merged)
                publishGuide(p.epgShiftMinutes)
            }
            guideChannels = listed
            _guideStats.value = stats
            writeGuideStats(p.id, stats)
            val result = GuideCache(now, merged, matched = merged.size, total = matcher.keys.size, channels = listed, scope = matcher.keys)
            guideMemo = GuideMemo(p.id, now, matcher.keys, result.matched, result.total)
            try {
                Store.writeGuide(file, result)
            } catch (e: Throwable) {
                file.delete()
            }
            guideStatus(result) + if (failed.isNotEmpty()) " · couldn't load: ${failed.joinToString()}" else ""
        }

    private fun guideStatsFile(playlistId: String) = File(cacheDir, "$playlistId.guide-stats.json")

    private fun readGuideStats(playlistId: String): List<GuideSourceStat> = try {
        val f = guideStatsFile(playlistId)
        if (f.exists()) json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(GuideSourceStat.serializer()), f.readText()) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun writeGuideStats(playlistId: String, stats: List<GuideSourceStat>) {
        try {
            guideStatsFile(playlistId).writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(GuideSourceStat.serializer()), stats))
        } catch (e: Exception) {
            // only a summary for Settings
        }
    }

    /** Channels offered by the guide sources last loaded (for fixing a channel's guide by hand). */
    @Volatile var guideChannels: List<GuideChannel> = emptyList()
        private set

    /** Hand-picks the guide channel for a playlist channel; null = automatic, "" = no guide. */
    fun setGuideOverride(playlistId: String, key: String, guideId: String?) = updateSettings { s ->
        val cur = s.guideOverrides[playlistId].orEmpty()
        val next = if (guideId == null) cur - key else cur + (key to guideId)
        s.copy(guideOverrides = s.guideOverrides + (playlistId to next))
    }

    /** The guide in memory, already moved by [rawShift] ms. Only one copy is kept (guides are big). */
    @Volatile private var rawGuide: Map<String, List<Program>> = emptyMap()
    @Volatile private var rawShift = 0L

    @Synchronized
    private fun setRawGuide(programs: Map<String, List<Program>>) {
        rawGuide = programs
        rawShift = 0L
    }

    /** Screenshot tests: show this guide without downloading anything. */
    @androidx.annotation.VisibleForTesting
    internal fun showGuideForTest(programs: Map<String, List<Program>>) {
        setRawGuide(programs)
        publishGuide(0)
    }

    /** Applies the playlist's guide time correction without downloading again. */
    @Synchronized
    fun publishGuide(shiftMinutes: Int) {
        val delta = shiftMinutes * 60_000L - rawShift
        if (delta != 0L) {
            rawGuide = rawGuide.mapValues { (_, list) -> list.map { it.copy(start = it.start + delta, stop = it.stop + delta) } }
            rawShift += delta
        }
        _epg.value = rawGuide
    }

    private fun guideStatus(c: GuideCache): String {
        val mins = (System.currentTimeMillis() - c.savedAt) / 60_000
        val age = when {
            mins < 2 -> "just now"
            mins < 60 -> "$mins min ago"
            else -> "${mins / 60} h ago"
        }
        val of = if (c.total > 0) " of ${c.total}" else ""
        return "Guide for ${c.matched}$of channels · updated $age"
    }

    // ---- moving playlists between devices (Settings → Your devices) ----

    /** Everything another device needs to show the same playlists, lists and favourites. */
    fun exportBundle(deviceName: String): SyncBundle {
        val s = _state.value
        return SyncBundle(
            from = deviceName,
            createdAt = System.currentTimeMillis(),
            playlists = s.playlists.map { p ->
                val text = if (p.type == SourceType.M3U_FILE) {
                    try {
                        File(p.url).takeIf { it.exists() && it.length() < 40L * 1024 * 1024 }?.readText()
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
                SyncPlaylist(p, text)
            },
            favorites = s.favorites,
            favLists = s.favLists,
            hiddenGroups = s.settings.hiddenGroups,
            guideOverrides = s.settings.guideOverrides,
        )
    }

    /**
     * Adds what [bundle] has and this device doesn't. A playlist already here (same id, or the same
     * link and login) gets the incoming name and logins; favourites, lists and hidden groups are
     * merged, never removed.
     */
    fun importBundle(bundle: SyncBundle): ImportResult {
        val before = _state.value.playlists
        val idMap = HashMap<String, String>()
        val incoming = ArrayList<Playlist>()
        var added = 0
        var updated = 0
        var unchanged = 0
        for (sp in bundle.playlists) {
            val inc = sp.playlist
            val match = before.firstOrNull { it.id == inc.id } ?: before.firstOrNull {
                it.type == inc.type && it.type != SourceType.M3U_FILE &&
                    it.url.trimEnd('/').equals(inc.url.trimEnd('/'), ignoreCase = true) && it.username == inc.username
            }
            if (match != null) {
                idMap[inc.id] = match.id
                val merged = inc.copy(
                    id = match.id,
                    url = if (inc.type == SourceType.M3U_FILE) match.url else inc.url,
                    extraEpg = (match.extraEpg + inc.extraEpg).distinct(),
                )
                if (merged != match) {
                    incoming += merged
                    updated++
                } else {
                    unchanged++
                }
                continue
            }
            var p = inc
            if (inc.type == SourceType.M3U_FILE) {
                val text = sp.m3u ?: continue // the file itself didn't come along
                val f = File(importDir, "${inc.id}.m3u")
                try {
                    f.writeText(text)
                } catch (e: Exception) {
                    continue
                }
                p = p.copy(url = f.absolutePath)
            }
            idMap[inc.id] = p.id
            incoming += p
            added++
        }
        fun <V> remap(m: Map<String, V>): Map<String, V> =
            m.mapNotNull { (k, v) -> idMap[k]?.let { it to v } }.toMap()
        update { s ->
            val byId = s.playlists.associateBy { it.id }.toMutableMap()
            val order = s.playlists.map { it.id }.toMutableList()
            for (p in incoming) {
                if (p.id !in byId) order += p.id
                byId[p.id] = p
            }
            val favs = s.favorites.toMutableMap()
            for ((id, list) in remap(bundle.favorites)) favs[id] = unionIds(favs[id].orEmpty(), list)
            val lists = s.favLists.toMutableMap()
            for ((id, theirs) in remap(bundle.favLists)) {
                val mine = lists[id].orEmpty().toMutableList()
                for (l in theirs) {
                    val i = mine.indexOfFirst { it.id == l.id || it.name.equals(l.name, ignoreCase = true) }
                    if (i < 0) mine += l else mine[i] = mine[i].copy(ids = unionIds(mine[i].ids, l.ids))
                }
                lists[id] = mine
            }
            val hidden = s.settings.hiddenGroups.toMutableMap()
            for ((id, groups) in remap(bundle.hiddenGroups)) hidden[id] = unionIds(hidden[id].orEmpty(), groups)
            val picked = s.settings.guideOverrides.toMutableMap()
            for ((id, m) in remap(bundle.guideOverrides)) picked[id] = m + picked[id].orEmpty()
            s.copy(
                playlists = order.mapNotNull { byId[it] },
                favorites = favs,
                favLists = lists,
                settings = s.settings.copy(hiddenGroups = hidden, guideOverrides = picked),
                lastPlaylistId = s.lastPlaylistId ?: order.firstOrNull(),
            )
        }
        return ImportResult(added, updated, unchanged)
    }

    /** Saves the text of an M3U file sent from another device or a browser; returns its path. */
    fun saveImportedM3u(id: String, text: String): String {
        val f = File(importDir, "$id.m3u")
        f.writeText(text)
        return f.absolutePath
    }

    fun updatePlaylist(p: Playlist) = update { s ->
        s.copy(playlists = s.playlists.map { if (it.id == p.id) p else it })
    }

    fun clearEpg() {
        guideMemo = null
        setRawGuide(emptyMap())
        guideChannels = emptyList()
        _guideStats.value = emptyList()
        _epg.value = emptyMap()
    }

    companion object {
        /** "epgshare01.online/…/epg_ripper_UK1.xml.gz" → "UK1 · epgshare01.online" */
        fun sourceLabel(url: String): String {
            GUIDE_SOURCES.firstOrNull { it.url == url }?.let { return it.name }
            if (url.contains("xmltv.php")) return "Provider guide"
            return "Guide · ${maskHost(url)}" // never show a server's full address
        }


        /** A widely accepted player User-Agent; many providers reject unknown apps. */
        const val CRASH_FILE = "last_crash.txt"
        const val DEFAULT_UA = "VLC/3.0.20 LibVLC/3.0.20"
        private val FALLBACK_UAS = listOf(
            DEFAULT_UA,
            "IPTVSmartersPro",
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
        )
    }
}
