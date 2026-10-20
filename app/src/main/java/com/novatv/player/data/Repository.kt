package com.novatv.player.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

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
                return@withContext json.decodeFromString(PlaylistCache.serializer(), file.readText())
            } catch (e: Exception) {
                // fall through and download again
            }
        }
        val result = when (p.type) {
            SourceType.M3U_URL -> {
                val req = Request.Builder().url(p.url.trim())
                    .header("User-Agent", p.userAgent.ifBlank { DEFAULT_UA })
                    .build()
                http.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) throw IOException("Playlist download failed (HTTP ${res.code})")
                    val body = res.body ?: throw IOException("Empty playlist")
                    val parsed = M3uParser.parse(body.charStream().buffered())
                    PlaylistCache(parsed.channels, parsed.epgUrl)
                }
            }
            SourceType.M3U_FILE -> {
                val parsed = M3uParser.parse(File(p.url).bufferedReader())
                PlaylistCache(parsed.channels, parsed.epgUrl)
            }
            SourceType.XTREAM -> {
                val api = XtreamApi(http, p.url, p.username, p.password, p.userAgent)
                PlaylistCache(api.loadAll(), api.epgUrl)
            }
        }
        if (result.channels.isEmpty()) throw IOException("No channels found in this playlist")
        file.writeText(json.encodeToString(PlaylistCache.serializer(), result))
        result
    }

    suspend fun loadEpg(url: String, channels: List<Channel>, userAgent: String) = withContext(Dispatchers.IO) {
        val wanted = channels.asSequence()
            .filter { it.kind == Kind.LIVE && it.tvgId.isNotBlank() }
            .map { it.epgKey }
            .toHashSet()
        if (wanted.isEmpty() || url.isBlank()) return@withContext
        val req = Request.Builder().url(url.trim())
            .header("User-Agent", userAgent.ifBlank { DEFAULT_UA })
            .build()
        http.newCall(req).execute().use { res ->
            val body = res.body
            if (res.isSuccessful && body != null) {
                _epg.value = Epg.parse(body.byteStream(), wanted, System.currentTimeMillis())
            }
        }
    }

    fun clearEpg() {
        _epg.value = emptyMap()
    }

    companion object {
        const val DEFAULT_UA = "NovaTV/1.0 (Linux; Android)"
    }
}
