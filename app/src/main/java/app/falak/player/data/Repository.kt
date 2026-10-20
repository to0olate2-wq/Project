package app.falak.player.data

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

    /** Returns (and clears) the crash saved by FalakApp on the previous run, if any. */
    fun takeCrashReport(): String? {
        val f = File(context.filesDir, CRASH_FILE)
        if (!f.exists()) return null
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
            )
        }
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

    /** Returns the cached list unless [refresh] is true or there is no cache yet. */
    suspend fun loadChannels(p: Playlist, refresh: Boolean, quietly: Boolean = false): PlaylistCache =
        withContext(if (quietly) background else Dispatchers.IO) {
        if (!refresh) cachedChannels(p)?.let { return@withContext it }
        val result = when (p.type) {
            SourceType.M3U_URL -> loadXtreamStyleLink(p) ?: downloadM3u(p)
            SourceType.M3U_FILE -> {
                val parsed = M3uParser.parse(File(p.url).bufferedReader())
                PlaylistCache(parsed.channels, parsed.epgUrl)
            }
            SourceType.XTREAM -> {
                val api = XtreamApi(http, p.url, p.username, p.password, p.userAgent)
                PlaylistCache(api.loadAll(), api.epgUrl, api.serverTimezone())
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
    private fun loadXtreamStyleLink(p: Playlist): PlaylistCache? {
        val (server, user, pass) = XtreamApi.fromM3uLink(p.url) ?: return null
        return try {
            val api = XtreamApi(http, server, user, pass, p.userAgent)
            val list = api.loadAll()
            if (list.isEmpty()) null else PlaylistCache(list, api.epgUrl, api.serverTimezone())
        } catch (e: Exception) {
            null // fall back to downloading the M3U file
        }
    }

    /** Downloads an M3U file, retrying with other common player User-Agents if the provider refuses. */
    private fun downloadM3u(p: Playlist): PlaylistCache {
        val agents = if (p.userAgent.isNotBlank()) listOf(p.userAgent) else FALLBACK_UAS
        var lastCode = 0
        for (ua in agents) {
            val req = Request.Builder().url(p.url.trim()).header("User-Agent", ua).build()
            http.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body ?: throw IOException("Empty playlist")
                    val parsed = M3uParser.parse(body.charStream().buffered())
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
            var hadGuide = false
            if (file.exists()) {
                val cached = try {
                    Store.readGuide(file)
                } catch (e: Exception) {
                    null
                }
                if (cached != null) {
                    hadGuide = cached.programs.isNotEmpty()
                    setRawGuide(cached.programs)
                    guideChannels = cached.channels
                    publishGuide(p.epgShiftMinutes)
                    if (!force && now - cached.savedAt < 8 * 3_600_000L) return@withContext guideStatus(cached)
                }
            }
            val sources = (listOf(providerUrl) + p.extraEpg).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (sources.isEmpty()) return@withContext "No TV guide link for this playlist. Add a guide source in Settings."
            val overrides = _state.value.settings.guideOverrides[p.id].orEmpty()
            val matcher = GuideMatcher(channels, overrides)
            if (matcher.keys.isEmpty()) return@withContext ""
            val merged = HashMap<String, List<Program>>()
            val listed = ArrayList<GuideChannel>()
            val failed = ArrayList<String>()
            val job = coroutineContext[kotlinx.coroutines.Job]
            for (url in sources) {
                val host = url.substringAfter("://").substringBefore('/')
                coroutineContext.ensureActive()
                try {
                    val req = Request.Builder().url(url).header("User-Agent", p.userAgent.ifBlank { DEFAULT_UA }).build()
                    http.newCall(req).execute().use { res ->
                        val body = res.body
                        if (!res.isSuccessful || body == null) throw IOException("HTTP ${res.code}")
                        val part = Epg.parse(body.byteStream(), matcher, now, source = sourceLabel(url)) { job?.isActive == false }
                        coroutineContext.ensureActive() // a newer load replaced this one
                        for ((key, list) in part.programs) if (merged[key].isNullOrEmpty()) merged[key] = list
                        listed += part.channels
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += if (url == providerUrl.trim()) "provider guide" else sourceLabel(url)
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
            val result = GuideCache(now, merged, matched = merged.size, total = matcher.keys.size, channels = listed)
            try {
                Store.writeGuide(file, result)
            } catch (e: Throwable) {
                file.delete()
            }
            guideStatus(result) + if (failed.isNotEmpty()) " · couldn't load: ${failed.joinToString()}" else ""
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

    fun updatePlaylist(p: Playlist) = update { s ->
        s.copy(playlists = s.playlists.map { if (it.id == p.id) p else it })
    }

    fun clearEpg() {
        setRawGuide(emptyMap())
        guideChannels = emptyList()
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
