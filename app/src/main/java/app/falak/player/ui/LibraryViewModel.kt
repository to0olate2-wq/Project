package app.falak.player.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.falak.player.data.Catchup
import app.falak.player.data.Channel
import app.falak.player.data.Kind
import app.falak.player.data.LibraryIndex
import app.falak.player.data.Playlist
import app.falak.player.data.Program
import app.falak.player.data.SeriesInfo
import app.falak.player.data.Settings
import app.falak.player.data.VodInfo
import app.falak.player.data.progressKey
import app.falak.player.data.SourceType
import app.falak.player.data.XtreamApi
import app.falak.player.repo
import app.falak.player.update.UpdateInfo
import app.falak.player.update.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class Screen { PLAYLISTS, LIBRARY, SERIES, DETAILS, GUIDE, SETTINGS }

enum class Sort(val label: String) { DEFAULT("Default"), NEWEST("Newest"), AZ("A–Z"), RATING("Top rated") }

enum class Section(val label: String, val kind: Kind?) {
    HOME("Home", null),
    LIVE("Live", Kind.LIVE),
    MOVIES("Films", Kind.MOVIE),
    SERIES("Series", Kind.SERIES),
    FAVORITES("Saved", null),
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
    /** Pre-sorted views of [channels], built off the main thread. */
    var index by mutableStateOf(LibraryIndex.EMPTY)
        private set

    /** Wide backdrops for the Home feature carousel (Xtream movie details), by channel id. */
    val heroArt = androidx.compose.runtime.mutableStateMapOf<String, String>()
    private var heroJob: Job? = null

    fun loadHeroArt(items: List<Channel>) {
        val p = playlist ?: return
        val todo = items.filter { it.id.startsWith("m") && it.id !in heroArt }
        if (todo.isEmpty() || heroJob?.isActive == true) return
        heroJob = viewModelScope.launch {
            for (ch in todo) {
                val art = try {
                    repo.loadVodInfo(p, ch.id.removePrefix("m"))?.backdrop.orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ""
                }
                heroArt[ch.id] = art
            }
        }
    }

    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var section by mutableStateOf(Section.HOME)
    var sort by mutableStateOf(Sort.DEFAULT)

    var details by mutableStateOf<Channel?>(null)
        private set
    var vodInfo by mutableStateOf<VodInfo?>(null)
        private set
    var detailsLoading by mutableStateOf(false)
        private set
    private var detailsJob: Job? = null

    /** Movie details page (Xtream fills in plot, cast, backdrop…). */
    fun openDetails(ch: Channel) {
        val p = playlist ?: return
        details = ch
        vodInfo = null
        screen = Screen.DETAILS
        detailsJob?.cancel()
        if (!ch.id.startsWith("m")) return
        detailsLoading = true
        detailsJob = viewModelScope.launch {
            try {
                vodInfo = repo.loadVodInfo(p, ch.id.removePrefix("m"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // details are optional; the page still works with the basics
            } finally {
                detailsLoading = false
            }
        }
    }

    fun clearProgress(ch: Channel) {
        val p = playlist ?: return
        repo.saveProgress(progressKey(p.id, ch.id), 0, 0)
    }
    var group by mutableStateOf<String?>(null)
    var query by mutableStateOf("")

    /** Crash details from the previous run, shown once so the user can copy them. */
    var crashReport by mutableStateOf<String?>(null)

    /** Kept here so the list keeps its position when coming back from a series or the guide. */
    var listState by mutableStateOf(LazyListState())
        private set
    var gridState by mutableStateOf(LazyGridState())
        private set

    // One scroll position per section, so sections can cross-fade and each remembers its place.
    private val listStates = HashMap<Section, LazyListState>()
    private val gridStates = HashMap<Section, LazyGridState>()
    fun listStateFor(s: Section): LazyListState = listStates.getOrPut(s) { LazyListState() }
    fun gridStateFor(s: Section): LazyGridState = gridStates.getOrPut(s) { LazyGridState() }
    var homeState by mutableStateOf(LazyListState())
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

    // ---- one-tap updates (GitHub build only) ----
    var update by mutableStateOf<UpdateInfo?>(null)
        private set
    var updateProgress by mutableStateOf<Float?>(null)
        private set
    var updateMessage by mutableStateOf<String?>(null)
    var updateChecking by mutableStateOf(false)
        private set

    fun checkForUpdates(manual: Boolean) {
        if (!Updater.enabled || updateChecking) return
        val prefs = getApplication<Application>().getSharedPreferences("updates", 0)
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.getLong("lastCheck", 0) < 6 * 3_600_000L) return
        updateChecking = true
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { Updater.check(repo.http) }
                prefs.edit().putLong("lastCheck", now).apply()
                update = info
                if (manual && info == null) updateMessage = "You have the latest version."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (manual) updateMessage = "Couldn't check for updates: ${e.message}"
            } finally {
                updateChecking = false
            }
        }
    }

    fun dismissUpdate() {
        if (updateProgress == null) update = null
    }

    fun installUpdate() {
        val info = update ?: return
        val ctx = getApplication<Application>()
        if (!Updater.canInstall(ctx)) {
            Updater.openInstallPermission(ctx)
            updateMessage = "Allow The Player to install apps, then come back and tap Update again."
            return
        }
        if (updateProgress != null) return
        updateProgress = 0f
        viewModelScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    Updater.download(ctx, repo.http, info) { p -> updateProgress = p }
                }
                Updater.install(ctx, apk)
                update = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateMessage = "Update failed: ${e.message}"
            } finally {
                updateProgress = null
            }
        }
    }

    private var loadJob: Job? = null
    private var seriesJob: Job? = null
    private var loadToken = Any()


    fun open(p: Playlist, refresh: Boolean = false) {
        screen = Screen.LIBRARY
        if (!refresh && playlist?.id == p.id && channels.isNotEmpty()) return
        loadJob?.cancel()
        if (playlist?.id != p.id) {
            channels = emptyList()
            index = LibraryIndex.EMPTY
            heroArt.clear()
            group = null
            query = ""
            section = Section.HOME
            sort = Sort.DEFAULT
            listState = LazyListState()
            guideStatus = ""
            gridState = LazyGridState()
            listStates.clear()
            gridStates.clear()
            homeState = LazyListState()
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
                index = withContext(Dispatchers.Default) { LibraryIndex.build(data.channels) }
                channels = data.channels
                providerEpg = data.epgUrl
                serverTz = data.serverTz
                loadGuide(force = refresh)
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

    // ---- TV guide ----
    private var providerEpg = ""
    /** Xtream server time zone, used for replay URLs. */
    var serverTz = ""
        private set
    var guideStatus by mutableStateOf("")
        private set
    var guideLoading by mutableStateOf(false)
        private set
    private var guideJob: Job? = null

    fun loadGuide(force: Boolean) {
        val p = playlist ?: return
        val list = channels
        if (list.isEmpty()) return
        guideJob?.cancel()
        guideLoading = true
        guideJob = viewModelScope.launch {
            try {
                guideStatus = repo.loadGuide(p, list, p.epgUrl.ifBlank { providerEpg }, force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                guideStatus = "Guide failed to load: ${e.message}"
            } catch (e: OutOfMemoryError) {
                guideStatus = "The guide is too large for this device."
            } finally {
                guideLoading = false
            }
        }
    }

    /** Saves playlist changes; new guide sources reload the guide, a time correction applies instantly. */
    fun updatePlaylist(p: Playlist, reloadGuide: Boolean) {
        repo.updatePlaylist(p)
        if (playlist?.id == p.id) {
            playlist = p
            if (reloadGuide) loadGuide(force = true) else repo.publishGuide(p.epgShiftMinutes)
        }
    }

    fun replay(ch: Channel, prog: Program, now: Long): Channel? {
        val url = Catchup.url(ch, prog, serverTz, now) ?: return null
        return Catchup.asItem(ch, prog, url)
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
        repo.currentSeries = if (screen == Screen.SERIES) series else null
        // For episodes, remember the series itself in "Recent".
        val recentId = if (screen == Screen.SERIES) series?.id ?: ch.id else ch.id
        if (!recentId.startsWith("cu:")) playlist?.let { repo.addRecent(it.id, recentId) }
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

    // Runs last, after every property above exists (opening a playlist touches many of them).
    init {
        crashReport = repo.takeCrashReport()
        val s = state.value
        // After a crash, don't reopen the same playlist automatically (avoids a crash loop).
        if (crashReport == null && s.settings.openLast) {
            s.playlists.firstOrNull { it.id == s.lastPlaylistId }?.let { open(it) }
        }
    }
}
