package com.novatv.player.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novatv.player.data.Channel
import com.novatv.player.data.Kind
import com.novatv.player.data.Playlist
import com.novatv.player.data.SeriesInfo
import com.novatv.player.data.Settings
import com.novatv.player.data.SourceType
import com.novatv.player.data.XtreamApi
import com.novatv.player.repo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

enum class Screen { PLAYLISTS, LIBRARY, SERIES, GUIDE, SETTINGS }

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

    /** Crash details from the previous run, shown once so the user can copy them. */
    var crashReport by mutableStateOf<String?>(null)

    /** Kept here so the list keeps its position when coming back from a series or the guide. */
    var listState by mutableStateOf(LazyListState())
        private set
    var lastListKey: Any? = null

    /** True once the parental PIN was entered in this session. */
    var unlocked by mutableStateOf(false)

    var series by mutableStateOf<Channel?>(null)
        private set
    var seriesInfo by mutableStateOf<SeriesInfo?>(null)
        private set
    var seriesError by mutableStateOf<String?>(null)
        private set

    private var loadJob: Job? = null
    private var seriesJob: Job? = null
    private var loadToken = Any()

    init {
        crashReport = repo.takeCrashReport()
        val s = state.value
        // After a crash, don't reopen the same playlist automatically (avoids a crash loop).
        if (crashReport == null && s.settings.openLast) {
            s.playlists.firstOrNull { it.id == s.lastPlaylistId }?.let { open(it) }
        }
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
            listState = LazyListState()
            lastListKey = null
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
            } catch (e: OutOfMemoryError) {
                channels = emptyList()
                error = "This playlist is too large for this device's memory."
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
        // For episodes, remember the series itself in "Recent".
        val recentId = if (screen == Screen.SERIES) series?.id ?: ch.id else ch.id
        playlist?.let { repo.addRecent(it.id, recentId) }
    }

    fun openSeries(ch: Channel) {
        val p = playlist ?: return
        series = ch
        seriesInfo = null
        seriesError = null
        screen = Screen.SERIES
        seriesJob?.cancel()
        seriesJob = viewModelScope.launch {
            try {
                seriesInfo = repo.loadSeries(p, ch.seriesId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                seriesError = e.message ?: e.toString()
            }
        }
    }

    fun updateSettings(transform: (Settings) -> Settings) = repo.updateSettings(transform)

    fun toggleLockedGroup(group: String) {
        val p = playlist ?: return
        repo.updateSettings { s ->
            val cur = s.lockedGroups[p.id].orEmpty()
            s.copy(lockedGroups = s.lockedGroups + (p.id to if (group in cur) cur - group else cur + group))
        }
    }

    /** Returns true and unlocks for this session if [pin] is right. */
    fun tryUnlock(pin: String): Boolean {
        val ok = pin.isNotEmpty() && pin == state.value.settings.pin
        if (ok) unlocked = true
        return ok
    }

    private fun hostOf(url: String): String = try {
        java.net.URI(url.trim()).host ?: "Playlist"
    } catch (e: Exception) {
        "Playlist"
    }
}
