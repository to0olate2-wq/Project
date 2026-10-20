package app.falak.player.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

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

    @Synchronized
    fun update(transform: (AppState) -> AppState) {
        val s = transform(_state.value)
        _state.value = s
        val tmp = File(stateFile.path + ".tmp")
        tmp.writeText(json.encodeToString(AppState.serializer(), s))
        tmp.renameTo(stateFile)
    }

    fun addPlaylist(p: Playlist) = update { it.copy(playlists = it.playlists + p, lastPlaylistId = p.id) }

    fun removePlaylist(id: String) {
        update { s ->
            s.copy(
                playlists = s.playlists.filterNot { it.id == id },
                favorites = s.favorites - id,
                recents = s.recents - id,
                lastPlaylistId = s.lastPlaylistId.takeIf { it != id },
            )
        }
        File(cacheDir, "$id.json").delete()
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

    /** Returns the cached list unless [refresh] is true or there is no cache yet. */
    suspend fun loadChannels(p: Playlist, refresh: Boolean): PlaylistCache = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "${p.id}.json")
        if (!refresh && file.exists()) {
            try {
                return@withContext file.inputStream().buffered().use {
                    json.decodeFromStream(PlaylistCache.serializer(), it)
                }
            } catch (e: Exception) {
                // fall through and download again
            }
        }
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
        }
        if (result.channels.isEmpty()) throw IOException("No channels found in this playlist")
        // Stream to disk: building one huge string for big providers can run out of memory.
        try {
            file.outputStream().buffered().use { json.encodeToStream(PlaylistCache.serializer(), result, it) }
        } catch (e: Throwable) {
            file.delete() // the cache is only a speed-up
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
     * channel name and merged (first source with data for a channel wins). Uses the disk cache
     * unless [force] or it is older than 8 hours. Returns a short status line.
     */
    suspend fun loadGuide(p: Playlist, channels: List<Channel>, providerUrl: String, force: Boolean): String =
        withContext(Dispatchers.IO) {
            val file = File(cacheDir, "${p.id}.guide.json")
            val now = System.currentTimeMillis()
            if (file.exists()) {
                val cached = try {
                    file.inputStream().buffered().use { json.decodeFromStream(GuideCache.serializer(), it) }
                } catch (e: Exception) {
                    null
                }
                if (cached != null) {
                    rawGuide = cached.programs
                    publishGuide(p.epgShiftMinutes)
                    if (!force && now - cached.savedAt < 8 * 3_600_000L) return@withContext guideStatus(cached)
                }
            }
            val sources = (listOf(providerUrl) + p.extraEpg).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (sources.isEmpty()) return@withContext "No TV guide link for this playlist. Add a guide source in Settings."
            val matcher = GuideMatcher(channels)
            if (matcher.keys.isEmpty()) return@withContext ""
            val merged = HashMap<String, List<Program>>()
            val failed = ArrayList<String>()
            for (url in sources) {
                try {
                    val req = Request.Builder().url(url).header("User-Agent", p.userAgent.ifBlank { DEFAULT_UA }).build()
                    http.newCall(req).execute().use { res ->
                        val body = res.body
                        if (!res.isSuccessful || body == null) throw IOException("HTTP ${res.code}")
                        val part = Epg.parse(body.byteStream(), matcher, now)
                        for ((key, list) in part) if (merged[key].isNullOrEmpty()) merged[key] = list
                    }
                } catch (e: Exception) {
                    failed += url.substringAfter("://").substringBefore('/')
                }
                rawGuide = HashMap(merged) // show what we have so far
                publishGuide(p.epgShiftMinutes)
            }
            val result = GuideCache(now, merged, matched = merged.size)
            try {
                file.outputStream().buffered().use { json.encodeToStream(GuideCache.serializer(), result, it) }
            } catch (e: Throwable) {
                file.delete()
            }
            guideStatus(result) + if (failed.isNotEmpty()) " · couldn't load: ${failed.joinToString()}" else ""
        }

    @Volatile private var rawGuide: Map<String, List<Program>> = emptyMap()

    /** Applies the playlist's guide time correction without downloading again. */
    fun publishGuide(shiftMinutes: Int) {
        val raw = rawGuide
        val shift = shiftMinutes * 60_000L
        _epg.value = if (shift == 0L) raw else raw.mapValues { (_, list) ->
            list.map { it.copy(start = it.start + shift, stop = it.stop + shift) }
        }
    }

    private fun guideStatus(c: GuideCache): String {
        val mins = (System.currentTimeMillis() - c.savedAt) / 60_000
        val age = when {
            mins < 2 -> "just now"
            mins < 60 -> "$mins min ago"
            else -> "${mins / 60} h ago"
        }
        return "Guide for ${c.matched} channels · updated $age"
    }

    fun updatePlaylist(p: Playlist) = update { s ->
        s.copy(playlists = s.playlists.map { if (it.id == p.id) p else it })
    }

    fun clearEpg() {
        rawGuide = emptyMap()
        _epg.value = emptyMap()
    }

    companion object {
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
