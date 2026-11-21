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
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
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

    /**
     * Playlist downloads: a busy provider can take well over a minute to start sending a big list
     * (100,000 films), so these wait longer before giving up (and are tried again, see [Downloads]).
     */
    private val listHttp: OkHttpClient = http.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val stateFile = File(context.filesDir, "state.json")
    private val cacheDir = File(context.filesDir, "cache").apply { mkdirs() }
    private val importDir = File(context.filesDir, "imports").apply { mkdirs() }

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    // ---- MAC portals (8.33): one session per portal, links made fresh at each play (PortalLinks) ----
    private val portals = java.util.concurrent.ConcurrentHashMap<String, Pair<String, PortalApi>>()

    /** The open session with [p]'s portal (null: not a portal). */
    fun portalFor(p: Playlist): PortalApi? {
        if (!p.isPortal) return null
        val key = p.url.trim() + "|" + p.mac + "|" + p.userAgent
        portals[p.id]?.takeIf { it.first == key }?.let { return it.second }
        return PortalApi(listHttp, p.url, p.mac, p.userAgent).also { portals[p.id] = key to it }
    }

    private fun portalById(id: String): PortalApi? = _state.value.playlists.firstOrNull { it.id == id }?.let { portalFor(it) }

    /** The portal's link server, started once (only when there is a portal to play from). */
    private fun portalLinks() {
        PortalLinks.resolve = { id, module, series, cmd ->
            (portalById(id) ?: throw IOException("That portal isn't in your playlists any more")).playLink(module, cmd, series)
        }
        PortalLinks.keepAlive = { id -> portalById(id)?.stillHere() }
        PortalLinks.ensure(context)
    }

    /** Checks a portal takes this MAC (the Add window). */
    suspend fun portalLogin(url: String, mac: String, ua: String) = withContext(Dispatchers.IO) {
        try {
            PortalApi(listHttp, url, mac, ua).check()
        } catch (e: IOException) {
            throw if (e.message == Portal.REFUSED) e else Downloads.explain(e)
        }
    }

    /** Films' and series' details a portal's list leaves out, kept beside its saved copy. */
    @Volatile private var portalExtras: Pair<String, Map<String, PortalExtra>>? = null
    private val portalExtrasSerializer = kotlinx.serialization.builtins.MapSerializer(String.serializer(), PortalExtra.serializer())

    private fun portalExtrasFile(id: String) = File(cacheDir, "$id.portal.json")

    init {
        // Saved links (Continue watching, recordings) need the link server from the start.
        if (_state.value.playlists.any { it.isPortal }) runCatching { portalLinks() }
    }

    fun portalExtra(playlistId: String, itemId: String): PortalExtra? {
        portalExtras?.takeIf { it.first == playlistId }?.let { return it.second[itemId] }
        val read = try {
            portalExtrasFile(playlistId).takeIf { it.exists() }?.inputStream()?.buffered()?.use {
                json.decodeFromStream(portalExtrasSerializer, it)
            }
        } catch (e: Exception) {
            null
        } ?: emptyMap()
        portalExtras = playlistId to read
        return read[itemId]
    }

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

    /** The Kids profile is watching (the player hides grown-up pages from its Menu). */
    @Volatile var kidsActive = false

    /** The guide is playing a small preview (counts as playing for background work). */
    @Volatile var previewOpen = false

    /** Listen mode is playing the sound in the background (counts as playing for background work). */
    @Volatile var listening = false

    /** A page the player asked to open when it closed ("MOVIES", "SETTINGS"…), for the main screen. */
    @Volatile var pendingNav: String? = null

    /** Writes the Android TV home screen's row (8.35, [TvHomeRow]); set by MainActivity, called as the app goes to the background. */
    @Volatile var tvRow: (() -> Unit)? = null

    /** With [pendingNav]: the item that was playing, to go back to when leaving that page. */
    @Volatile var pendingReturnIndex: Int? = null

    /** The "start with the last channel" check has run in this process (it only runs on a fresh start). */
    @Volatile var autoPlayChecked = false

    /** Remembers the live channel being watched, to start with it next time. */
    fun setLastLive(playlistId: String, channelId: String) {
        val k = _state.value.mine(playlistId)
        if (_state.value.lastLive[k] != channelId) update { it.copy(lastLive = it.lastLive + (it.mine(playlistId) to channelId)) }
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
        // What the app was doing just before (8.37): a native crash leaves no other trace of it.
        val steps = FreezeWatch.previousSteps
        if (unexpected != null && steps.isNotBlank() && "(ANR)" !in unexpected) unexpected += "\nLast steps:\n$steps\n"
        // The app's own record of a freeze has the whole stuck call chain and the last steps.
        val freeze = FreezeWatch.take(context.filesDir, anr = unexpected?.contains("(ANR)") == true)
        if (freeze != null) unexpected = (unexpected ?: header) + "\n\n" + freeze
        val f = File(context.filesDir, CRASH_FILE)
        if (!f.exists()) return unexpected
        return try { f.readText() } catch (e: Exception) { null } finally { f.delete() }
    }

    // ---- saved state (playlists, favorites, recents) ----

    /** A damaged file never empties the app: the last good copy is used (see [SafeFile]). */
    private fun readState(): AppState =
        SafeFile.read(stateFile) { json.decodeFromString(AppState.serializer(), it) } ?: AppState()

    /**
     * Changes apply instantly in memory; the file is written shortly after on a background thread.
     * While linked to other devices, the parts that sync are dated as they change ([stamp] = false
     * for changes that came from another device).
     */
    fun update(stamp: Boolean = true, transform: (AppState) -> AppState) {
        var tell = false
        synchronized(this) {
            val old = _state.value
            var next = transform(old)
            if (stamp && next !== old) {
                // Settings, profiles, reminders and alert words are dated even while not linked, so
                // on linking the device where they were last changed wins.
                next = SyncMerge.stamp(old, next, System.currentTimeMillis())
                tell = next.sync.key.isNotEmpty() && (
                    next.sync.stamps !== old.sync.stamps || next.sync.removed !== old.sync.removed ||
                        next.progress !== old.progress || next.sync.progressGone !== old.sync.progressGone
                    )
            }
            _state.value = next
        }
        scheduleWrite()
        if (tell) onSyncChange?.invoke()
    }

    /** Called after a change other linked devices should hear about. */
    @Volatile var onSyncChange: (() -> Unit)? = null

    // ---- automatic sync between linked devices ----

    /** This device's id for sync (made the first time it's needed). */
    fun syncDeviceId(): String {
        _state.value.sync.deviceId.takeIf { it.isNotEmpty() }?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        update(stamp = false) { s -> if (s.sync.deviceId.isNotEmpty()) s else s.copy(sync = s.sync.copy(deviceId = id)) }
        return _state.value.sync.deviceId
    }

    fun syncSnapshot(device: String): SyncSnapshot {
        syncDeviceId()
        return SyncMerge.snapshot(_state.value, device)
    }

    /** A backup file's text (Settings → Devices → Save a backup file; see Backup). */
    fun backupText(device: String, version: String): String = Backup.make(_state.value, device, version, System.currentTimeMillis())

    /** Restores a backup file; returns what came back ("2 playlists, 14 favorites"). Throws IllegalArgumentException when it isn't one. */
    fun restoreBackup(text: String): String {
        val b = Backup.read(text)
        var gone: List<String> = emptyList()
        // Stamped as merged, so linked devices take it on too.
        update(stamp = false) { s ->
            val (next, removed) = Backup.restore(s, b, System.currentTimeMillis())
            gone = removed
            next
        }
        gone.forEach { deleteCaches(it) }
        return Backup.summary(b)
    }

    /** Takes in what another linked device sent. Returns true when something here changed. */
    fun mergeSync(theirs: SyncSnapshot): Boolean {
        var changed = false
        var gone: List<String> = emptyList()
        update(stamp = false) { s ->
            if (s.sync.key.isEmpty()) return@update s
            val (next, removed) = SyncMerge.merge(s, theirs, System.currentTimeMillis())
            changed = !SyncMerge.sameSynced(s, next)
            gone = removed
            next
        }
        gone.forEach { deleteCaches(it) }
        return changed
    }

    /**
     * This device shows the code and another one links to it: the shared secret (made now if this
     * device isn't linked yet). Sync turns on here too.
     */
    fun syncKeyForLink(peerId: String, peerName: String): String {
        syncDeviceId()
        val key = _state.value.sync.key.ifEmpty { app.falak.player.sync.SyncCrypto.newKey() }
        update(stamp = false) { s ->
            val peers = if (peerId.isEmpty()) s.sync.peers else s.sync.peers + (peerId to SyncPeer(peerName))
            s.copy(sync = s.sync.copy(key = key, peers = peers), settings = s.settings.copy(syncOn = true))
        }
        return key
    }

    /** This device linked to another one: everything here counts as newest, so the first sync shares it all. */
    fun linkSync(key: String, peerId: String, peerName: String) {
        syncDeviceId()
        update(stamp = false) { s ->
            val peers = if (peerId.isEmpty()) emptyMap() else mapOf(peerId to SyncPeer(peerName))
            val linked = s.copy(
                sync = SyncMeta(deviceId = s.sync.deviceId, key = key, peers = peers, stamps = SyncMerge.wholeAppStamps(s.sync.stamps)),
                settings = s.settings.copy(syncOn = true),
            )
            SyncMerge.touchAll(linked, System.currentTimeMillis())
        }
    }

    /** Leaves the linked devices: this one keeps everything it has but stops syncing. */
    fun unlinkSync() = update(stamp = false) { s ->
        s.copy(
            sync = SyncMeta(deviceId = s.sync.deviceId, stamps = SyncMerge.wholeAppStamps(s.sync.stamps)),
            settings = s.settings.copy(syncOn = false),
        )
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
                SafeFile.write(stateFile, json.encodeToString(AppState.serializer(), _state.value))
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

    /** Changes the order chosen for playlist [playlistId] (Arrange); an empty one is forgotten. */
    fun arrange(playlistId: String, change: (Arrangement) -> Arrangement) = update { s ->
        val next = change(s.arranged[playlistId] ?: Arrangement.NONE)
        s.copy(arranged = if (next.isEmpty()) s.arranged - playlistId else s.arranged + (playlistId to next))
    }

    fun removePlaylist(id: String) {
        update { s -> SyncMerge.withoutPlaylist(s, id) }
        deleteCaches(id)
    }

    /** A removed playlist's downloaded copy, guide and imported file. */
    private fun deleteCaches(id: String) {
        if (openLibrary?.playlistId == id) openLibrary = null
        File(cacheDir, "$id.json").delete()
        File(cacheDir, "$id.bin").delete()
        File(cacheDir, "$id.guide.json").delete()
        File(cacheDir, "$id.guide.bin").delete()
        portalExtrasFile(id).delete()
        portals.remove(id)
        File(importDir, "$id.m3u").delete()
        ServiceLogStore.clear(context.filesDir, id)
    }

    fun setLast(id: String) {
        if (_state.value.lastPlaylistId != id) update { it.copy(lastPlaylistId = id) }
    }

    // Favorites, lists, recents, Continue watching and the last channel belong to the profile
    // watching: AppState.mine(playlistId) is the key they're kept under.

    fun toggleFavorite(playlistId: String, channelId: String) = update { s ->
        val k = s.mine(playlistId)
        val cur = s.favorites[k].orEmpty()
        val next = if (channelId in cur) cur - channelId else cur + channelId
        s.copy(favorites = s.favorites + (k to next))
    }

    // ---- the user's own lists ----

    /** Ids in a list; [DEFAULT_LIST] is the built-in Favourites. */
    fun listIds(s: AppState, playlistId: String, listId: String): List<String> {
        val k = s.mine(playlistId)
        return if (listId == DEFAULT_LIST) s.favorites[k].orEmpty()
        else s.favLists[k].orEmpty().firstOrNull { it.id == listId }?.ids.orEmpty()
    }

    private fun editList(playlistId: String, listId: String, edit: (List<String>) -> List<String>) = update { s ->
        val k = s.mine(playlistId)
        if (listId == DEFAULT_LIST) {
            s.copy(favorites = s.favorites + (k to edit(s.favorites[k].orEmpty())))
        } else {
            val lists = s.favLists[k].orEmpty().map { if (it.id == listId) it.copy(ids = edit(it.ids)) else it }
            s.copy(favLists = s.favLists + (k to lists))
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
            val k = s.mine(playlistId)
            val lists = s.favLists[k].orEmpty() + FavList(id, name.trim().ifEmpty { "My list" }, listOfNotNull(firstId))
            s.copy(favLists = s.favLists + (k to lists))
        }
        return id
    }

    fun renameList(playlistId: String, listId: String, name: String) = update { s ->
        val k = s.mine(playlistId)
        val lists = s.favLists[k].orEmpty().map { if (it.id == listId) it.copy(name = name.trim().ifEmpty { it.name }) else it }
        s.copy(favLists = s.favLists + (k to lists))
    }

    fun deleteList(playlistId: String, listId: String) = update { s ->
        val k = s.mine(playlistId)
        s.copy(favLists = s.favLists + (k to s.favLists[k].orEmpty().filterNot { it.id == listId }))
    }

    /** Moves a whole list left/right among the user's lists. */
    fun moveList(playlistId: String, listId: String, delta: Int) = update { s ->
        val k = s.mine(playlistId)
        val lists = s.favLists[k].orEmpty().toMutableList()
        val i = lists.indexOfFirst { it.id == listId }
        if (i < 0) return@update s
        val l = lists.removeAt(i)
        lists.add((i + delta).coerceIn(0, lists.size), l)
        s.copy(favLists = s.favLists + (k to lists))
    }

    fun updateSettings(transform: (Settings) -> Settings) = update { it.copy(settings = transform(it.settings)) }

    /** Actors and directors (8.29): Wikidata, no key (see People). */
    val people = PeopleClient(http, File(context.cacheDir, "people"))

    /** The IMDb rating Cinemeta gives a title ("8.7"), for this session; null when it has none. */
    private val imdbRatings = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun imdbRating(imdb: String, type: String): String? {
        imdbRatings[imdb]?.let { return it.ifEmpty { null } }
        val r = imdbMeta(imdb, type)?.rating ?: return null
        val clean = r.trim().takeIf { it.toDoubleOrNull()?.let { v -> v > 0 } == true }.orEmpty()
        imdbRatings[imdb] = clean
        return clean.ifEmpty { null }
    }

    private val imdbMetas = java.util.concurrent.ConcurrentHashMap<String, AddonMeta>()

    /** Cinemeta's page of a title by its IMDb number (rating, cast…), for this session; null when it can't be had. Network. */
    fun imdbMeta(imdb: String, type: String): AddonMeta? {
        imdbMetas[imdb]?.let { return it }
        if (PeopleClient.testing) return null
        val url = "https://v3-cinemeta.strem.io/meta/" + (if (type == "series") "series" else "movie") + "/$imdb.json"
        val meta = try {
            http.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "ThePlayer").build()).execute().use { r ->
                if (!r.isSuccessful) return null
                Addons.parseMeta(r.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            return null
        } ?: return null
        imdbMetas[imdb] = meta
        return meta
    }

    /** "movie|The Matrix|1999" → its IMDb number, or "" when none is sure (this session; see TitleMatch). */
    private val imdbFound = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The IMDb number of a provider's film or series ([type] "movie" or "series"), found by its name; null if not sure. Network: call off the main thread. */
    /** The playlist's titles among what is popular now ([kind] films or series), most popular first (8.34). */
    fun popularIn(index: LibraryIndex, kind: Kind): List<Channel> {
        val titles = people.popular(if (kind == Kind.SERIES) "series" else "movie") ?: return emptyList()
        val names = people.names(index) { index.movies + index.series }
        return People.inLibrary(titles, names).map { it.second }
    }

    /**
     * "Because you watched [ch]" (8.34): the playlist's titles most like it (Wikidata: the same
     * series of films, director, writers, cast). Empty when it can't be found by its name.
     */
    fun becauseYouWatched(index: LibraryIndex, ch: Channel): List<Channel> {
        val type = if (ch.kind == Kind.SERIES) "series" else "movie"
        val (name, year) = Versions.title(ch.name)
        val imdb = imdbFor(type, name, year ?: providerYears[(if (type == "series") "s" else "m") + ch.id.removePrefix("m").removePrefix("s")]) ?: return emptyList()
        val titles = people.similar(imdb) ?: return emptyList()
        val names = people.names(index) { index.movies + index.series }
        val same = index.copiesOf(ch).mapTo(HashSet()) { it.id } + ch.id
        return People.inLibrary(titles, names).map { it.second }.filter { it.id !in same }.take(24)
    }

    fun imdbFor(type: String, name: String, year: Int?): String? {
        val key = "$type|${TitleMatch.norm(name)}|${year ?: ""}"
        imdbFound[key]?.let { return it.ifEmpty { null } }
        val found = try {
            val request = okhttp3.Request.Builder().url(TitleMatch.searchUrl(type, name)).header("User-Agent", "ThePlayer").build()
            http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return null // (not remembered: may answer next time)
                TitleMatch.pick(TitleMatch.parse(r.body?.string().orEmpty()), name, year, type == "series")
            }
        } catch (e: Exception) {
            return null
        }
        imdbFound[key] = found.orEmpty()
        return found
    }

    /** The subtitles the add-ons have for what is playing: its video id → them (set by the portal as it starts). */
    @Volatile var addonSubs: Pair<String, List<AddonSub>>? = null

    private val deadTracker = DeadChannels.Tracker()

    /** The player couldn't open a live channel, with the internet there (see DeadChannels). */
    fun noteLiveFailed(ch: Channel) = markDead(deadTracker.failed(ch.id, System.currentTimeMillis()))

    /** A live channel plays: it isn't dead, and the ones that failed just before it are. */
    fun noteLivePlayed(ch: Channel) {
        val pl = currentPlaylist ?: return
        val at = System.currentTimeMillis()
        markDead(deadTracker.played(ch.id, at))
        if (state.value.dead[pl.id]?.containsKey(ch.id) == true) {
            update(stamp = false) { s -> s.copy(dead = s.dead + (pl.id to (s.dead[pl.id].orEmpty() - ch.id))) }
        }
    }

    private fun markDead(ids: List<String>) {
        val pl = currentPlaylist ?: return
        if (ids.isEmpty()) return
        update(stamp = false) { s -> s.copy(dead = s.dead + (pl.id to DeadChannels.mark(s.dead[pl.id].orEmpty(), ids, System.currentTimeMillis()))) }
    }

    /** "Show them all again" in Settings. */
    fun forgetDead() = update(stamp = false) { it.copy(dead = emptyMap()) }

    /** The player, on a film or episode of an add-on: how far it is (the portal's Continue watching). */
    fun noteAddonProgress(videoId: String, pos: Long, dur: Long, finished: Boolean) {
        val had = state.value.settings.addonWatch
        val next = Addons.noteProgress(had, videoId, pos, dur, finished, System.currentTimeMillis())
        if (next !== had) updateSettings { it.copy(addonWatch = next) }
    }

    // ---- What's new ----

    /** Records what a refresh added to [p] and returns the entry. */
    fun addNotice(p: Playlist, added: List<Channel>): Notice {
        val groups = added.groupingBy { it.kind to it.group }.eachCount().entries
            .sortedByDescending { it.value }.take(8)
            .map { (k, n) -> NoticeGroup(k.first, k.second, n) }
        // Films and series with pictures first: they make the best preview. Enough of each group
        // that What's new can show them by group without sending you to the lists.
        val items = added.sortedByDescending { (if (it.kind != Kind.LIVE) 2 else 0) + (if (it.logo.isNotBlank()) 1 else 0) }
            .take(60).map { it.id }
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

    /** New episodes of a followed series: a What's new entry that opens the series. */
    fun addEpisodeNotice(p: Playlist, show: Channel, count: Int): Notice {
        val notice = Notice(
            id = System.currentTimeMillis(),
            playlistId = p.id,
            playlistName = p.name,
            live = 0, films = 0, series = 0,
            items = listOf(show.id),
            episodes = count,
            show = show.name,
        )
        update { s -> s.copy(notices = (listOf(notice) + s.notices).take(40)) }
        return notice
    }

    // ---- phone remote ----

    /** What's on the TV for the phone remote: name and a second line (null when nothing plays). */
    @Volatile var nowPlaying: Pair<String, String>? = null

    /** What the player plays now and where (8.42: a linked phone's second screen asks); null when it's closed. */
    @Volatile var handNow: NowState? = null

    fun addRemote(hash: String, label: String) = update(stamp = false) { s ->
        val now = System.currentTimeMillis()
        s.copy(remotes = (s.remotes.filterNot { it.hash == hash } + RemotePhone(hash, label, now, now)).takeLast(10))
    }

    /** A paired phone was used (saved at most every few minutes). */
    fun touchRemote(hash: String) {
        val now = System.currentTimeMillis()
        val r = _state.value.remotes.firstOrNull { it.hash == hash } ?: return
        if (now - r.lastUsed < 5 * 60_000L) return
        update(stamp = false) { s -> s.copy(remotes = s.remotes.map { if (it.hash == hash) it.copy(lastUsed = now) else it }) }
    }

    fun removeRemote(hash: String) = update(stamp = false) { s -> s.copy(remotes = s.remotes.filterNot { it.hash == hash }) }

    // ---- profiles ----

    /** Switches whose favorites, lists and Continue watching are in use ("" = the main profile). */
    fun setActiveProfile(id: String) {
        if (_state.value.activeProfile != id) update(stamp = false) { it.copy(activeProfile = id) }
    }

    fun addProfile(name: String): Profile {
        val p = Profile(
            "p" + java.util.UUID.randomUUID().toString().take(8), name.trim().ifEmpty { "Profile" },
            color = _state.value.profiles.size % PROFILE_COLORS,
            avatar = Avatars.random(Avatars.inUse(_state.value)),
        )
        update { s -> s.copy(profiles = s.profiles + p) }
        return p
    }

    /** A profile's picture ("" = the main profile, [KIDS_PROFILE] = Kids). */
    fun setAvatar(id: String, avatar: String) = update { s ->
        when (id) {
            "" -> s.copy(settings = s.settings.copy(mainAvatar = avatar))
            KIDS_PROFILE -> s.copy(settings = s.settings.copy(kidsAvatar = avatar))
            else -> s.copy(profiles = s.profiles.map { if (it.id == id) it.copy(avatar = avatar) else it })
        }
    }

    fun renameProfile(id: String, name: String) = update { s ->
        s.copy(profiles = s.profiles.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it })
    }

    /** Deletes a profile and everything it kept (its favorites, lists, recents, Continue watching). */
    fun deleteProfile(id: String) = update { s ->
        val tag = "@$id"
        fun <V> drop(m: Map<String, V>) = m.filterKeys { !it.endsWith(tag) }
        s.copy(
            profiles = s.profiles.filterNot { it.id == id },
            activeProfile = if (s.activeProfile == id) "" else s.activeProfile,
            favorites = drop(s.favorites),
            favLists = drop(s.favLists),
            recents = drop(s.recents),
            lastLive = drop(s.lastLive),
            progress = s.progress.filterKeys { !it.substringBefore('|').endsWith(tag) },
        )
    }

    // ---- series: intros and following ----

    fun setIntro(key: String, start: Long, end: Long) = update { s -> s.copy(intros = s.intros + (key to Intro(start, end))) }

    fun isFollowed(playlistId: String, seriesId: String): Boolean =
        _state.value.followed[playlistId].orEmpty().any { it.seriesId == seriesId }

    /** Follows a series (or updates its name, picture and episode count). */
    fun follow(playlistId: String, show: Channel, episodes: Int) = update { s ->
        val list = s.followed[playlistId].orEmpty()
        val old = list.firstOrNull { it.seriesId == show.seriesId }
        val entry = FollowedSeries(
            show.seriesId, show.name, show.logo.ifBlank { old?.logo.orEmpty() },
            episodes = maxOf(episodes, old?.episodes ?: 0),
            checkedAt = maxOf(old?.checkedAt ?: 0, System.currentTimeMillis()),
        )
        if (old == entry) s else s.copy(followed = s.followed + (playlistId to (list.filterNot { it.seriesId == show.seriesId } + entry).takeLast(60)))
    }

    fun unfollow(playlistId: String, seriesId: String) = update { s ->
        s.copy(followed = s.followed + (playlistId to s.followed[playlistId].orEmpty().filterNot { it.seriesId == seriesId }))
    }

    /** After checking a followed series: its episode count now. */
    fun setFollowedCount(playlistId: String, seriesId: String, episodes: Int) = update { s ->
        s.copy(
            followed = s.followed + (
                playlistId to s.followed[playlistId].orEmpty().map {
                    if (it.seriesId == seriesId) it.copy(episodes = episodes, checkedAt = System.currentTimeMillis()) else it
                }
                ),
        )
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
        val now = System.currentTimeMillis()
        val next = LinkedHashMap(s.progress)
        val had = next.remove(key) != null
        if (pos > 0) {
            next[key] = Watch(
                pos = pos, dur = dur,
                title = ch?.name.orEmpty(), image = ch?.logo.orEmpty(), url = ch?.url.orEmpty(),
                kind = ch?.kind ?: Kind.MOVIE, seriesId = seriesId, at = now,
            )
        }
        while (next.size > 300) next.remove(next.keys.first())
        // Finished or removed: tell linked devices, so they drop it too.
        val sync = if (pos <= 0 && had && s.sync.key.isNotEmpty()) {
            s.sync.copy(progressGone = s.sync.progressGone + (SyncMerge.syncProgressKey(s, key) to now))
        } else {
            s.sync
        }
        s.copy(progress = next, sync = sync)
    }

    /** Xtream access for this playlist: Xtream logins and get.php-style M3U links. */
    fun xtreamFor(p: Playlist): XtreamApi? = if (p.isPortal) null else when (p.type) {
        SourceType.XTREAM -> XtreamApi(http, p.url, p.username, p.password, p.userAgent)
        SourceType.M3U_URL -> XtreamApi.fromM3uLink(p.url)?.let { (server, user, pass) ->
            XtreamApi(http, server, user, pass, p.userAgent)
        }
        SourceType.M3U_FILE -> null
    }

    /** The playlist item [id] of [p] really comes from: [p] itself, or for a combined playlist the one it was taken from. */
    fun sourceOf(p: Playlist, id: String): Playlist =
        if (p.isCombined) Combined.sourceFor(p, id, _state.value.playlists) ?: p else p

    /**
     * The year the provider gives for a film ("m" + its stream id) or a series ("s" + its id), as its
     * details are read: the skip times' lookup finds the right title by it (TitleMatch).
     */
    val providerYears = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun noteYear(key: String, year: String) {
        year.take(4).toIntOrNull()?.takeIf { it in 1900..2100 }?.let { providerYears[key] = it }
    }

    suspend fun loadVodInfo(p: Playlist, streamId: String): VodInfo? = loadVodInfoAsSent(p, streamId)?.also { noteYear("m$streamId", it.year) }

    private suspend fun loadVodInfoAsSent(p: Playlist, streamId: String): VodInfo? = withContext(Dispatchers.IO) {
        if (p.isCombined) {
            val src = Combined.sourceFor(p, streamId, _state.value.playlists) ?: return@withContext null
            if (src.isPortal) return@withContext portalVodInfo(src, Combined.untag(streamId))
            return@withContext xtreamFor(src)?.vodInfo(Combined.untag(streamId))
        }
        if (p.isPortal) return@withContext portalVodInfo(p, streamId)
        xtreamFor(p)?.vodInfo(streamId)
    }

    /** A portal film's details, from what its list said (portals have no details call). */
    private fun portalVodInfo(p: Playlist, streamId: String): VodInfo? {
        val e = portalExtra(p.id, "m$streamId") ?: return null
        return VodInfo(e.plot, e.poster, "", e.genre, e.cast, e.director, e.year, e.duration, e.rating, "")
    }

    private fun portalSeries(p: Playlist, seriesId: String): SeriesInfo {
        val api = portalFor(p) ?: throw IOException("Episodes need the portal")
        portalLinks()
        return api.seriesInfo(seriesId, portalExtra(p.id, "s$seriesId")) { module, series, cmd, ext ->
            PortalLinks.link(p.id, module, series, cmd, ext)
        }
    }

    suspend fun loadSeries(p: Playlist, seriesId: String): SeriesInfo = loadSeriesAsSent(p, seriesId).also { noteYear("s$seriesId", it.year) }

    private suspend fun loadSeriesAsSent(p: Playlist, seriesId: String): SeriesInfo = withContext(Dispatchers.IO) {
        if (p.isCombined) {
            // Asked of the playlist it came from; its episodes carry the same mark.
            val t = Combined.tagOf(seriesId)
            val src = Combined.sourcesOf(p, _state.value.playlists).firstOrNull { it.second == t }
                ?: throw IOException("The playlist this series came from isn't in this list any more")
            if (src.first.isPortal) {
                return@withContext Combined.markEpisodes(portalSeries(src.first, Combined.untag(seriesId)), src.first, src.second)
            }
            val api = xtreamFor(src.first) ?: throw IOException("Episodes need an Xtream login for that playlist")
            return@withContext Combined.markEpisodes(api.seriesInfo(Combined.untag(seriesId)), src.first, src.second)
        }
        if (p.isPortal) return@withContext portalSeries(p, seriesId)
        val api = xtreamFor(p) ?: throw IOException("Episodes need an Xtream login for this playlist")
        api.seriesInfo(seriesId)
    }

    fun addRecent(playlistId: String, channelId: String) = update { s ->
        val k = s.mine(playlistId)
        val cur = s.recents[k].orEmpty()
        s.copy(recents = s.recents + (k to (listOf(channelId) + (cur - channelId)).take(40)))
    }

    // ---- loading ----

    suspend fun importFile(uri: Uri, id: String): String = withContext(Dispatchers.IO) {
        val dest = importedM3u(id)
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open that file")
        try {
            input.use { i -> dest.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
        } catch (e: IOException) {
            dest.delete()
            throw if (importDir.usableSpace < 32L * 1024 * 1024) IOException("There isn't enough free space on this device for that file") else e
        }
        dest.absolutePath
    }

    /** Where an M3U file added to this device is kept (see [importFile], and the phone page's upload). */
    fun importedM3u(id: String): File = File(importDir, "$id.m3u")

    suspend fun xtreamLogin(server: String, user: String, pass: String, ua: String) =
        withContext(Dispatchers.IO) {
            try {
                XtreamApi(http, server, user, pass, ua).login()
            } catch (e: IOException) {
                throw Downloads.explain(e)
            }
        }

    /** The saved copy of a playlist, or null if there is none (or it can't be read). Fast. */
    /** A saved copy of [p]'s channels is on the device (opening it is quick; no download). */
    fun hasSavedCopy(p: Playlist): Boolean =
        if (p.isCombined) {
            Combined.sourcesOf(p, _state.value.playlists).any { (src, _) -> hasSavedCopy(src) }
        } else {
            File(cacheDir, "${p.id}.bin").exists() || File(cacheDir, "${p.id}.json").exists()
        }

    suspend fun cachedChannels(p: Playlist): PlaylistCache? = withContext(Dispatchers.IO) {
        if (p.isCombined) {
            // Put together from the saved copies of the playlists it combines (none: load them).
            val parts = Combined.sourcesOf(p, _state.value.playlists).mapNotNull { (src, t) -> cachedChannels(src)?.let { Triple(src, t, it) } }
            return@withContext if (parts.isEmpty()) null else Combined.combine(parts)
        }
        val bin = File(cacheDir, "${p.id}.bin")
        if (bin.exists()) {
            try {
                val read = Store.readPlaylist(bin)
                return@withContext if (p.isPortal) mendPortalLinks(read) else read
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

    /** A portal's saved copy: its links go to this run's link server (its port may have changed). */
    private fun mendPortalLinks(c: PlaylistCache): PlaylistCache {
        portalLinks()
        val sample = c.channels.firstOrNull { it.url.isNotEmpty() }?.url ?: return c
        if (PortalLinks.fix(sample) == sample) return c
        return c.copy(channels = c.channels.map { if (it.url.isEmpty()) it else it.copy(url = PortalLinks.fix(it.url)) })
    }

    /**
     * Returns the cached list unless [refresh] is true or there is no cache yet. [keep] is the
     * list already in memory: unchanged entries are reused instead of being held twice.
     */
    suspend fun loadChannels(
        p: Playlist, refresh: Boolean, quietly: Boolean = false, keep: Map<String, Channel> = emptyMap(),
        progress: ((LoadProgress) -> Unit)? = null,
    ): PlaylistCache =
        withContext(if (quietly) background else Dispatchers.IO) {
        if (p.isCombined) return@withContext loadCombined(p, refresh, quietly, progress)
        if (!refresh) cachedChannels(p)?.let { return@withContext it }
        val reuse = Reuse.from(keep)
        val counted: ((Int) -> Unit)? = progress?.let { report -> { n: Int -> report(LoadProgress(n)) } }
        val loaded = try {
            if (p.isPortal) {
                portalLinks()
                val api = portalFor(p) ?: throw IOException("That portal isn't right")
                val (list, extras) = api.loadAll(reuse, progress) { module, series, cmd, ext ->
                    PortalLinks.link(p.id, module, series, cmd, ext)
                }
                try {
                    portalExtrasFile(p.id).outputStream().buffered().use {
                        json.encodeToStream(portalExtrasSerializer, extras, it)
                    }
                } catch (e: Exception) {
                    portalExtrasFile(p.id).delete() // details only
                }
                portalExtras = p.id to extras
                PlaylistCache(list)
            } else when (p.type) {
                SourceType.M3U_URL -> loadXtreamStyleLink(p, reuse, progress) ?: downloadM3u(p, reuse, progress)
                SourceType.M3U_FILE -> {
                    val parsed = M3uParser.parse(File(p.url).bufferedReader(), reuse, counted)
                    PlaylistCache(parsed.channels, parsed.epgUrl)
                }
                SourceType.XTREAM -> {
                    val api = XtreamApi(listHttp, p.url, p.username, p.password, p.userAgent)
                    PlaylistCache(api.loadAll(reuse, progress), api.epgUrl, api.serverTimezone())
                }
            }
        } catch (e: IOException) {
            throw Downloads.explain(e)
        }
        val result = loaded.copy(savedAt = System.currentTimeMillis())
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
     * A combined playlist: each playlist it puts together, loaded (or refreshed) as on its own,
     * then combined. One that fails is left out (its saved copy is used when there is one).
     */
    private suspend fun loadCombined(p: Playlist, refresh: Boolean, quietly: Boolean, progress: ((LoadProgress) -> Unit)?): PlaylistCache {
        val sources = Combined.sourcesOf(p, _state.value.playlists)
        if (sources.isEmpty()) throw IOException("The playlists this one combines were removed. Edit it to choose others.")
        val parts = ArrayList<Triple<Playlist, String, PlaylistCache>>()
        var failure: Exception? = null
        for ((src, t) in sources) {
            val cache = try {
                loadChannels(src, refresh, quietly, progress = progress)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
                cachedChannels(src)
            } ?: continue
            parts += Triple(src, t, cache)
        }
        if (parts.isEmpty()) throw (failure as? IOException) ?: IOException("None of the combined playlists could be loaded")
        return Combined.combine(parts)
    }

    /**
     * Most provider links look like http://host:port/get.php?username=…&password=….
     * Many providers block get.php downloads (e.g. HTTP 884), so load those through the
     * Xtream API instead. Returns null when the link isn't that kind or the API doesn't answer.
     */
    private fun loadXtreamStyleLink(p: Playlist, reuse: Reuse, progress: ((LoadProgress) -> Unit)?): PlaylistCache? {
        val (server, user, pass) = XtreamApi.fromM3uLink(p.url) ?: return null
        return try {
            val api = XtreamApi(listHttp, server, user, pass, p.userAgent)
            val list = api.loadAll(reuse, progress)
            if (list.isEmpty()) null else PlaylistCache(list, api.epgUrl, api.serverTimezone())
        } catch (e: Exception) {
            null // fall back to downloading the M3U file
        }
    }

    /**
     * Downloads an M3U file, retrying with other common player User-Agents if the provider refuses.
     * A download that fails part-way starts again (see [Downloads]).
     */
    private fun downloadM3u(p: Playlist, reuse: Reuse, progress: ((LoadProgress) -> Unit)?): PlaylistCache {
        val agents = if (p.userAgent.isNotBlank()) listOf(p.userAgent) else FALLBACK_UAS
        val counted: ((Int) -> Unit)? = progress?.let { report -> { n: Int -> report(LoadProgress(n)) } }
        var lastCode = 0
        for (ua in agents) {
            val req = Request.Builder().url(p.url.trim()).header("User-Agent", ua).build()
            val got = Downloads.withRetries(onRetry = { attempt -> progress?.invoke(LoadProgress(0, attempt)) }) {
                listHttp.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val body = res.body ?: throw IOException("Empty playlist")
                        val parsed = M3uParser.parse(body.charStream().buffered(), reuse, counted)
                        PlaylistCache(parsed.channels, parsed.epgUrl)
                    } else {
                        lastCode = res.code
                        null
                    }
                }
            }
            if (got != null) return got
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
            // A combined playlist's providers each have a guide: one link a line.
            val providerUrls = providerUrl.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            val sources = (providerUrls + p.extraEpg).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (sources.isEmpty() && !p.isPortal) return@withContext "No TV guide for this playlist yet"
            if (matcher.keys.isEmpty()) return@withContext ""
            val merged = HashMap<String, List<Program>>()
            val listed = ArrayList<GuideChannel>()
            val failed = ArrayList<String>()
            val stats = ArrayList<GuideSourceStat>()
            // A MAC portal's own guide (the next day), by its channel ids; other guides fill the gaps.
            if (p.isPortal) {
                try {
                    val part = portalFor(p)?.guide(24).orEmpty()
                    var matched = 0
                    for (ch in channels) {
                        val list = part[ch.id] ?: continue
                        if (merged.putIfAbsent(ch.epgKey, list) == null) matched++
                    }
                    stats += GuideSourceStat("portal", "Your portal's guide", true, matched, matched, ok = true)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += "portal guide"
                    stats += GuideSourceStat("portal", "Your portal's guide", true, 0, 0, ok = false)
                }
                if (merged.isEmpty() && sources.isEmpty()) return@withContext "This portal sends no TV guide. Add one in Settings → Playlists & TV guide."
            }
            // The first guide with data is the yardstick the later ones are checked against.
            var reference = ""
            val job = coroutineContext[kotlinx.coroutines.Job]
            for (url in sources) {
                val isProvider = url in providerUrls
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
            if (inc.isCombined) {
                // Its playlists get their ids here once all are in (below).
                idMap[inc.id] = p.id
                incoming += p
                added++
                continue
            }
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
        // Combined playlists: the playlists they put together, by their ids here.
        for (i in incoming.indices) {
            val p = incoming[i]
            if (p.isCombined) {
                val mapped = p.sources.mapNotNull { idMap[it] ?: it.takeIf { id -> before.any { b -> b.id == id } } }
                incoming[i] = p.copy(sources = mapped.ifEmpty { p.sources })
            }
        }
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
        val f = importedM3u(id)
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
