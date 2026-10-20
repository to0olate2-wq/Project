package com.novatv.player.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novatv.player.data.Channel
import com.novatv.player.data.Kind
import com.novatv.player.data.Playlist
import com.novatv.player.data.SourceType
import com.novatv.player.data.XtreamApi
import com.novatv.player.repo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

enum class Screen { PLAYLISTS, LIBRARY }

enum class Section(val label: String, val kind: Kind?) {
    LIVE("Live TV", Kind.LIVE),
    MOVIES("Movies", Kind.MOVIE),
    SERIES("Series", Kind.SERIES),
    FAVORITES("Favorites", null),
    RECENT("Recent", null),
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = app.repo
    val state = repo.state
    val epg = repo.epg

    var screen by mutableStateOf(Screen.PLAYLISTS)
    var playlist by mutableStateOf<Playlist?>(null)
        private set
    var channels by mutableStateOf<List<Channel>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var section by mutableStateOf(Section.LIVE)
    var group by mutableStateOf<String?>(null)
    var query by mutableStateOf("")

    private var loadJob: Job? = null
    private var loadToken = Any()

    init {
        val s = state.value
        s.playlists.firstOrNull { it.id == s.lastPlaylistId }?.let { open(it) }
    }

    fun open(p: Playlist, refresh: Boolean = false) {
        screen = Screen.LIBRARY
        if (!refresh && playlist?.id == p.id && channels.isNotEmpty()) return
        loadJob?.cancel()
        if (playlist?.id != p.id) {
            channels = emptyList()
            group = null
            query = ""
            section = Section.LIVE
            repo.clearEpg()
        }
        playlist = p
        error = null
        loading = true
        repo.setLast(p.id)
        val token = Any()
        loadToken = token
        loadJob = viewModelScope.launch {
            try {
                val data = repo.loadChannels(p, refresh)
                channels = data.channels
                val epgUrl = p.epgUrl.ifBlank { data.epgUrl }
                launch {
                    try {
                        repo.loadEpg(epgUrl, data.channels, p.userAgent)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The guide is optional; channels still work without it.
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                if (loadToken === token) loading = false
            }
        }
    }

    /** Returns an error message, or null on success. */
    suspend fun addPlaylist(
        type: SourceType, name: String, url: String, user: String, pass: String,
        epgUrl: String, userAgent: String, file: Uri?,
    ): String? = try {
        val id = UUID.randomUUID().toString()
        val epg = epgUrl.trim()
        val ua = userAgent.trim()
        val p = when (type) {
            SourceType.M3U_URL -> {
                require(url.isNotBlank()) { "Enter the playlist link" }
                Playlist(id, name.ifBlank { hostOf(url) }, type, url.trim(), epgUrl = epg, userAgent = ua)
            }
            SourceType.M3U_FILE -> {
                requireNotNull(file) { "Choose a playlist file" }
                val path = repo.importFile(file, id)
                Playlist(id, name.ifBlank { "Local playlist" }, type, path, epgUrl = epg, userAgent = ua)
            }
            SourceType.XTREAM -> {
                require(url.isNotBlank() && user.isNotBlank() && pass.isNotBlank()) {
                    "Fill in server, username and password"
                }
                val server = XtreamApi.normalize(url)
                repo.xtreamLogin(server, user.trim(), pass.trim(), ua)
                Playlist(id, name.ifBlank { hostOf(server) }, type, server, user.trim(), pass.trim(), epg, ua)
            }
        }
        repo.addPlaylist(p)
        open(p)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.message ?: "Something went wrong"
    }

    fun remove(p: Playlist) {
        repo.removePlaylist(p.id)
        if (playlist?.id == p.id) {
            loadJob?.cancel()
            playlist = null
            channels = emptyList()
            loading = false
            repo.clearEpg()
        }
    }

    fun toggleFavorite(ch: Channel) {
        playlist?.let { repo.toggleFavorite(it.id, ch.id) }
    }

    fun prepareQueue(list: List<Channel>, ch: Channel) {
        repo.playQueue = list
        repo.currentPlaylist = playlist
        playlist?.let { repo.addRecent(it.id, ch.id) }
    }

    private fun hostOf(url: String): String = try {
        java.net.URI(url.trim()).host ?: "Playlist"
    } catch (e: Exception) {
        "Playlist"
    }
}
