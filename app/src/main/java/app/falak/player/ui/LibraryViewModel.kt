package app.falak.player.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.falak.player.CrashReports
import app.falak.player.data.mine
import app.falak.player.data.Account
import app.falak.player.data.AccountStore
import app.falak.player.data.Accounts
import app.falak.player.data.AppState
import app.falak.player.data.KIDS_PROFILE
import app.falak.player.data.Catchup
import app.falak.player.data.Channel
import app.falak.player.data.DEFAULT_LIST
import app.falak.player.data.GuideSuggestion
import app.falak.player.data.suggestGuides
import app.falak.player.data.Kind
import app.falak.player.data.LibraryIndex
import app.falak.player.data.LoadProgress
import kotlinx.coroutines.flow.MutableStateFlow
import app.falak.player.data.SearchText
import app.falak.player.data.Alerts
import app.falak.player.data.Reminder
import app.falak.player.data.ProgramAlert
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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

enum class Screen { PLAYLISTS, LIBRARY, SERIES, DETAILS, GUIDE, SETTINGS, GROUPS, SYNC, NOTICES, KIDS, LOCKS, PROFILES, REMOTE, PRAYER, DOWNLOADS }

/** Settings and the pages it opens (Back from those returns to the Settings page they came from). */
val SETTINGS_SCREENS = setOf(Screen.SETTINGS, Screen.SYNC, Screen.PROFILES, Screen.REMOTE, Screen.PRAYER, Screen.KIDS, Screen.LOCKS, Screen.GROUPS)

/** Pages that need the PIN while kids mode is on. */
val KIDS_LOCKED = setOf(Screen.PLAYLISTS, Screen.SETTINGS, Screen.GROUPS, Screen.SYNC, Screen.NOTICES, Screen.KIDS, Screen.LOCKS, Screen.PROFILES, Screen.REMOTE, Screen.PRAYER, Screen.DOWNLOADS)

enum class Sort(val label: String) { DEFAULT("Default"), NEWEST("Newest"), AZ("A–Z"), RATING("Top rated") }

enum class Section(val label: String, val kind: Kind?) {
    HOME("Home", null),
    LIVE("Live", Kind.LIVE),
    MOVIES("Films", Kind.MOVIE),
    SERIES("Series", Kind.SERIES),
    FAVORITES("Favorites", null),
    RECENT("Recent", null),
}

/** The automatic playlist refresh starts this long after the app opens or comes back. */
private const val REFRESH_DELAY_MS = 3 * 60_000L
/** Followed series are checked for new episodes at most this often. */
private const val FOLLOW_CHECK_MS = 6 * 3_600_000L

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = app.repo
    val state = repo.state
    val epg = repo.epg
    /** What each guide source covered and added on the last download. */
    val guideStats = repo.guideStats

    /** The Settings page open (null: the list of them); kept while a page opened from it is on screen. */
    var settingsPage by mutableStateOf<SettingsPage?>(null)

    /** The Settings row last opened, so the remote's focus comes back to it. */
    var settingsFocus: String? = null

    private val screenState = mutableStateOf(Screen.PLAYLISTS)

    /** The page on show. Leaving Settings (and the pages it opens) starts it at its list next time. */
    var screen: Screen
        get() = screenState.value
        set(value) {
            if (value !in SETTINGS_SCREENS) {
                settingsPage = null
                settingsFocus = null
            }
            screenState.value = value
        }
    var playlist by mutableStateOf<Playlist?>(null)
        private set
    var channels by mutableStateOf<List<Channel>>(emptyList())
        private set
    /** Pre-sorted views of [channels], built off the main thread. */
    var index by mutableStateOf(LibraryIndex.EMPTY)
        private set

    /** Wide backdrops for the Home feature carousel (Xtream movie details), by channel id. */
    val heroArt = androidx.compose.runtime.mutableStateMapOf<String, String>()

    /** The same films' details (plot, year, length), for the Films page's featured picture. */
    val heroInfo = androidx.compose.runtime.mutableStateMapOf<String, app.falak.player.data.VodInfo>()
    private var heroJob: Job? = null

    /** Films waiting for their details, in the order asked (Home, then Films, ...). */
    private val heroQueue = LinkedHashMap<String, Channel>()

    /** Details kept on the device for the open playlist, with when each was fetched (see DetailsCache). */
    private val savedDetails = HashMap<String, Pair<VodInfo, Long>>()
    private var savedDetailsFor: String? = null
    private var savedDetailsRead: kotlinx.coroutines.Deferred<Unit>? = null
    private val detailsDir get() = java.io.File(getApplication<Application>().filesDir, "details")

    /** Reads the details kept for [p] into the pictures and details maps (once per playlist). */
    private fun readSavedDetails(p: Playlist) {
        if (savedDetailsFor == p.id) return
        savedDetailsFor = p.id
        savedDetails.clear()
        savedDetailsRead = viewModelScope.async {
            val saved = withContext(Dispatchers.IO) { app.falak.player.data.DetailsCache.read(detailsDir, p.id) }
            if (playlist?.id == p.id) {
                savedDetails.putAll(saved)
                for ((id, v) in saved) {
                    if (id !in heroInfo) heroInfo[id] = v.first
                    if (id !in heroArt) heroArt[id] = v.first.backdrop
                }
            }
        }
    }

    /** Keeps fetched details on the device (written in the background). */
    private fun keepDetails(p: Playlist, fetched: Map<String, VodInfo>) {
        if (fetched.isEmpty() || savedDetailsFor != p.id) return
        val now = System.currentTimeMillis()
        for ((id, info) in fetched) savedDetails[id] = info to now
        val copy = HashMap(savedDetails)
        viewModelScope.launch(Dispatchers.IO) { runCatching { app.falak.player.data.DetailsCache.write(detailsDir, p.id, copy) } }
    }

    /**
     * Loads the details of these films (and, with [series], these series: a bigger request, so
     * only for the one on show) in the background, one at a time, in the order asked.
     */
    fun loadHeroArt(items: List<Channel>, series: Boolean = false) {
        val p = playlist ?: return
        for (ch in items) {
            val wanted = ch.id.startsWith("m") || (series && ch.isSeriesFolder)
            if (wanted && ch.id !in heroArt) heroQueue[ch.id] = ch
        }
        if (heroQueue.isEmpty() || heroJob?.isActive == true) return
        heroJob = viewModelScope.launch {
            // What's kept on the device first: no need to ask the provider for those.
            savedDetailsRead?.await()
            val fetched = HashMap<String, VodInfo>()
            while (heroQueue.isNotEmpty() && playlist?.id == p.id) {
                val ch = heroQueue.values.first()
                heroQueue.remove(ch.id)
                if (ch.id in heroArt) continue
                val info = try {
                    if (ch.isSeriesFolder) {
                        repo.loadSeries(p, ch.seriesId).let { s ->
                            app.falak.player.data.VodInfo(
                                plot = s.plot, poster = s.cover, backdrop = s.backdrop, genre = s.genre, cast = s.cast,
                                director = "", year = s.year, duration = "", rating = s.rating, trailer = "",
                            )
                        }
                    } else {
                        repo.loadVodInfo(p, ch.id.removePrefix("m"))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (info != null) {
                    heroInfo[ch.id] = info
                    fetched[ch.id] = info
                }
                heroArt[ch.id] = info?.backdrop.orEmpty()
                // Kept as it goes, so a long round isn't lost if the app closes midway.
                if (fetched.size >= 10) {
                    keepDetails(p, fetched)
                    fetched.clear()
                }
            }
            keepDetails(p, fetched)
        }
    }

    /** Which featured title Home, Films and Series show (it changes every few seconds). */
    val marqueeIndex = androidx.compose.runtime.mutableStateMapOf<Section, Int>()

    /** The Films and Series pages' scroll places, kept while the app is open (Home's is [homeState]). */
    private val marqueeStates = HashMap<Section, LazyListState>()
    fun marqueeStateFor(s: Section): LazyListState = if (s == Section.HOME) homeState else marqueeStates.getOrPut(s) { LazyListState() }

    var loading by mutableStateOf(false)
        private set

    /** How far the playlist being loaded has got (written from the download's thread). */
    val loadProgress = MutableStateFlow(LoadProgress())
    var error by mutableStateOf<String?>(null)
        private set
    var section by mutableStateOf(Section.HOME)

    /** Sidebar look: show this section as a grid (else a list). Remembered in Settings. */
    fun setGridView(s: Section, grid: Boolean) = repo.updateSettings { st ->
        st.copy(gridSections = if (grid) (st.gridSections + s.name).distinct() else st.gridSections - s.name)
    }
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
        openedFrom = ch.id
        details = ch
        // Already fetched (this session, or kept on the device): shown at once, nothing to ask.
        vodInfo = heroInfo[ch.id]
        screen = Screen.DETAILS
        detailsJob?.cancel()
        if (!ch.id.startsWith("m") || vodInfo != null) return
        detailsLoading = true
        detailsJob = viewModelScope.launch {
            try {
                val info = repo.loadVodInfo(p, ch.id.removePrefix("m"))
                vodInfo = info
                if (info != null && details?.id == ch.id) {
                    heroInfo[ch.id] = info
                    heroArt[ch.id] = info.backdrop
                    keepDetails(p, mapOf(ch.id to info))
                }
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
        repo.saveProgress(progressKey(state.value.mine(p.id), ch.id), 0, 0)
    }
    var group by mutableStateOf<String?>(null)
    var query by mutableStateOf("")

    private val searchPrefs = app.getSharedPreferences("search", 0)

    /** Searches that led somewhere, newest first (up to 8). Kept on this device only. */
    var recentSearches by mutableStateOf(
        searchPrefs.getString("recent", null).orEmpty().split('\n').filter { it.isNotBlank() }.take(8),
    )
        private set

    /** The sidebar look's search bar has the focus (recent searches show under it). */
    var searchFocused by mutableStateOf(false)

    /** "See all" was picked in Home's search results: Back goes back to them. */
    var searchFromHome = false

    /** Remembers [text] as a recent search (not in kids mode: those stay out of sight there). */
    fun rememberSearch(text: String) {
        val t = text.trim()
        if (t.length < 2 || kidsActive) return
        val key = SearchText.normalize(t)
        val list = (listOf(t) + recentSearches.filter { SearchText.normalize(it) != key }).take(8)
        if (list == recentSearches) return
        recentSearches = list
        searchPrefs.edit().putString("recent", list.joinToString("\n")).apply()
    }

    fun clearRecentSearches() {
        recentSearches = emptyList()
        searchPrefs.edit().remove("recent").apply()
    }

    // ---- voice search ----

    /** Counts what voice search heard (the classic look opens its search row for it). */
    var voiceHeard by mutableIntStateOf(0)
        private set

    /** What voice search heard: searched for on the lists, everything in this playlist from Home. */
    fun heardSearch(text: String) {
        if (playlist == null) return
        screen = Screen.LIBRARY
        group = null
        query = text
        voiceHeard++
    }

    /** The search box's "ع / EN": voice search listens for the other language from now on. */
    fun switchVoiceLanguage() {
        val now = VoiceSearch.language(state.value.settings.voiceLang)
        updateSettings { it.copy(voiceLang = if (now == "ar") "en" else "ar") }
    }

    // ---- Subscription watch ----

    /** The open playlist's Xtream account as last read (null: not read yet, or no Xtream login). */
    var account by mutableStateOf<Account?>(null)
        private set

    /** The open playlist has an Xtream login, so its account can be read. */
    var accountReadable by mutableStateOf(false)
        private set

    /** The last read failed and there's nothing older to show. */
    var accountFailed by mutableStateOf(false)
        private set

    /** The subscription reminder was put away today. */
    var accountNoteHidden by mutableStateOf(false)
        private set
    private var accountJob: Job? = null
    private var accountForTest = false
    private val accountPrefs = app.getSharedPreferences("account_note", 0)

    /** Reads the account from the provider: at most every 6 hours, or every minute with [force]. */
    fun checkAccount(force: Boolean = false) {
        if (accountForTest) return
        val p = playlist ?: return
        val ctx = getApplication<Application>()
        val api = repo.xtreamFor(p)
        accountReadable = api != null
        if (api == null) {
            account = null
            return
        }
        val who = Accounts.owner(p)
        val saved = AccountStore.get(ctx, p.id)?.takeIf { it.who == who }
        account = saved
        accountNoteHidden = accountPrefs.getString(p.id, null) == today()
        val age = System.currentTimeMillis() - (saved?.checkedAt ?: 0L)
        if (age < (if (force) 60_000L else 6 * 3_600_000L)) return
        if (accountJob?.isActive == true) return
        accountJob = viewModelScope.launch {
            val read = withContext(Dispatchers.IO) {
                try {
                    api.account()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            if (playlist?.id != p.id) return@launch
            if (read == null) {
                accountFailed = account == null
                return@launch
            }
            val a = read.copy(who = who)
            AccountStore.put(ctx, p.id, a)
            app.falak.player.SubscriptionAlarm.plan(ctx, p.id, p.name, a)
            account = a
            accountFailed = false
        }
    }

    /** "OK" on the subscription reminder: not again today. */
    fun hideAccountNote() {
        val p = playlist ?: return
        accountPrefs.edit().putString(p.id, today()).apply()
        accountNoteHidden = true
    }

    private fun today(): String = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())

    /** Screenshot tests: a made-up account, and nothing read. */
    @androidx.annotation.VisibleForTesting
    internal fun showAccountForTest(a: Account) {
        accountForTest = true
        accountReadable = true
        account = a
        accountNoteHidden = false
    }

    // ---- programme reminders and alert words ----

    /** What's coming up (reminders and alert-word finds) for the open playlist, soonest first. */
    var upcomingAlerts by mutableStateOf<List<ProgramAlert>>(emptyList())
        private set

    fun isReminded(ch: Channel, prog: Program): Boolean =
        state.value.reminders.any { it.channelId == ch.id && it.start == prog.start }

    /** "Remind me" in the guide (or cancels it). */
    fun toggleReminder(ch: Channel, prog: Program) {
        val p = playlist ?: return
        repo.update { s ->
            val there = s.reminders.any { it.channelId == ch.id && it.start == prog.start }
            s.copy(
                reminders = if (there) {
                    s.reminders.filterNot { it.channelId == ch.id && it.start == prog.start }
                } else {
                    (s.reminders + Reminder(p.id, ch.id, ch.name, prog.title.ifBlank { ch.name }, prog.start, prog.stop)).sortedBy { it.start }
                },
            )
        }
    }

    fun cancelReminder(channelId: String, start: Long) = repo.update { s ->
        s.copy(reminders = s.reminders.filterNot { it.channelId == channelId && it.start == start })
    }

    fun hasAlertWord(word: String): Boolean {
        val key = SearchText.normalize(word)
        return key.isNotEmpty() && state.value.alertWords.any { SearchText.normalize(it) == key }
    }

    /** Alert me whenever a programme with [word] in its name is on (or stop it). */
    fun toggleAlertWord(word: String) {
        val w = word.trim().replace(Regex("\\s+"), " ").take(60)
        val key = SearchText.normalize(w)
        if (key.isEmpty()) return
        repo.update { s ->
            val there = s.alertWords.any { SearchText.normalize(it) == key }
            s.copy(alertWords = if (there) s.alertWords.filterNot { SearchText.normalize(it) == key } else (s.alertWords + w).takeLast(30))
        }
    }

    /** The channel a reminder is for, with its group to zap through. */
    fun channelForAlert(a: ProgramAlert): Pair<List<Channel>, Channel>? {
        if (playlist?.id != a.playlistId) return null
        val ch = index.byId[a.channelId] ?: return null
        if (ch.group in currentExclusions()) return null
        return (index.groupsOf(Kind.LIVE)[ch.group] ?: listOf(ch)) to ch
    }

    /** A reminder's notification was tapped: open its playlist if needed and play the channel. */
    fun playFromAlert(playlistId: String, channelId: String) {
        repo.autoPlayChecked = true
        val p = state.value.playlists.firstOrNull { it.id == playlistId } ?: return
        viewModelScope.launch {
            if (playlist?.id != p.id) open(p)
            withTimeoutOrNull(30_000) {
                while (index.byId[channelId] == null && (loading || index.all.isEmpty())) delay(200)
            }
            val ch = index.byId[channelId] ?: return@launch
            if (ch.group in currentExclusions()) return@launch
            screen = Screen.LIBRARY
            autoPlay = (index.groupsOf(Kind.LIVE)[ch.group] ?: listOf(ch)) to ch
        }
    }

    /**
     * Keeps [upcomingAlerts] (and the reminder cards and notifications, see ProgramAlerts) up to
     * date as reminders, alert words, the guide, the playlist or what may be shown change, and
     * every 10 minutes as the day moves on. Finished reminders are tidied away.
     */
    private fun watchAlerts() {
        viewModelScope.launch {
            var last: Array<Any?>? = null
            var lastExcluded: Set<String>? = null
            var lastAt = 0L
            while (true) {
                val s = state.value
                val p = playlist
                val idx = index
                val guide = epg.value
                val excluded = currentExclusions()
                val refs = arrayOf<Any?>(p?.id, s.reminders, s.alertWords, idx, guide, s.favorites, s.activeProfile)
                val now = System.currentTimeMillis()
                val same = last?.let { old -> old.indices.all { old[it] === refs[it] || old[it] == refs[it] && it == 0 } } == true &&
                    excluded == lastExcluded
                if (!same || now - lastAt > 10 * 60_000L) {
                    last = refs
                    lastExcluded = excluded
                    lastAt = now
                    val list = if (p == null || (s.reminders.isEmpty() && s.alertWords.isEmpty())) {
                        emptyList()
                    } else {
                        withContext(Dispatchers.Default) {
                            val live = if (excluded.isEmpty()) idx.live else idx.live.filter { it.group !in excluded }
                            val favs = s.favorites[s.mine(p.id)].orEmpty().toHashSet()
                            Alerts.upcoming(p.id, s.reminders, s.alertWords, live, guide, now, favs)
                        }
                    }
                    upcomingAlerts = list
                    app.falak.player.ProgramAlerts.update(list)
                    if (s.reminders.any { it.stop < now - 3_600_000L }) {
                        repo.update { st -> st.copy(reminders = st.reminders.filter { it.stop >= now - 3_600_000L }) }
                    }
                }
                delay(3_000)
            }
        }
    }

    /**
     * The app stopped unexpectedly last time. Nothing is shown about it (a report goes to the
     * developer, see CrashReports); this once the last playlist and channel don't reopen by
     * themselves, so a crash can't repeat at every start.
     */
    private var crashedLastTime = false

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
            updateMessage = if (Updater.openInstallPermission(ctx)) {
                "Allow The Player to install apps, then come back and tap Update again."
            } else {
                "Allow installing apps from unknown sources for The Player in your TV's settings (Security / Apps), then tap Update again."
            }
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
        refreshJob?.cancel()
        if (playlist?.id != p.id) {
            channels = emptyList()
            index = LibraryIndex.EMPTY
            heroJob?.cancel()
            heroArt.clear()
            heroInfo.clear()
            heroQueue.clear()
            marqueeStates.clear()
            marqueeIndex.clear()
            rowStates.clear()
            lastCard = null
            returnFocus = null
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
            // The guide in memory stays when it is this playlist's (the app was only in the background).
            if (!repo.hasGuideFor(p.id)) repo.clearEpg()
        }
        playlist = p
        readSavedDetails(p)
        error = null
        loading = true
        loadProgress.value = LoadProgress()
        repo.setLast(p.id)
        val token = Any()
        loadToken = token
        loadJob = viewModelScope.launch {
            try {
                // Still in memory from before (the app only went to the background, or its screen
                // was rebuilt): show it at once, nothing to read.
                val kept = if (refresh) null else repo.openLibrary?.takeIf { it.playlistId == p.id }
                if (kept != null) {
                    index = kept.index
                    channels = kept.index.raw
                    retidyIfChanged()
                    providerEpg = kept.epgUrl
                    serverTz = kept.serverTz
                    loadedAt = kept.savedAt
                    if (loadToken === token) loading = false
                    maybeStartLastChannel(p)
                    checkAccount()
                    scheduleFollowCheck(p, token)
                    loadGuide(force = false)
                    if (isStale(kept.savedAt)) {
                        refreshJob?.cancel()
                        refreshJob = viewModelScope.launch { backgroundRefresh(p, token) }
                    }
                    return@launch
                }
                // The saved copy shows at once; a fresh one is fetched only if it is missing or old.
                val cached = if (refresh) null else repo.cachedChannels(p)
                val data = cached ?: repo.loadChannels(p, refresh = true, keep = index.byId) { step ->
                    if (loadToken === token) loadProgress.value = step
                }
                show(data, announce = refresh)
                if (loadToken === token) loading = false
                maybeStartLastChannel(p)
                checkAccount()
                scheduleFollowCheck(p, token)
                loadGuide(force = refresh)
                if (cached != null && isStale(cached.savedAt)) {
                    refreshJob?.cancel()
                    refreshJob = viewModelScope.launch { backgroundRefresh(p, token) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = app.falak.player.data.hideAddresses(e.message ?: e.toString(), p.url, p.epgUrl)
            } catch (e: OutOfMemoryError) {
                channels = emptyList()
                error = "This playlist is too large for this device's memory."
            } finally {
                if (loadToken === token) loading = false
            }
        }
    }

    /** Shows a loaded playlist. With [announce], what's new since the list on screen goes to What's new. */
    private suspend fun show(data: app.falak.player.data.PlaylistCache, announce: Boolean = false) {
        val list = withContext(Dispatchers.Default) { app.falak.player.data.ensureUniqueIds(data.channels) }
        val old = index
        val added = if (announce && old.all.isNotEmpty()) {
            withContext(Dispatchers.Default) { list.filter { it.id !in old.byId } }
        } else {
            emptyList()
        }
        // Live channels' guide keys get worked out by the guide load that follows (off the main
        // thread), not here: the lists show as soon as the index is ready.
        val tidy = state.value.settings
        index = withContext(Dispatchers.Default) { LibraryIndex.build(list, tidy.cleanNames, tidy.mergeDuplicates) }
        // The player keeps its own reference for its channel list: point it at the new copy so
        // the old one can be freed (it otherwise stayed in memory beside the new one).
        if (repo.currentIndex === old) repo.currentIndex = index
        channels = list
        providerEpg = data.epgUrl
        serverTz = data.serverTz
        loadedAt = data.savedAt
        playlist?.let { pl -> repo.openLibrary = app.falak.player.data.Repository.OpenLibrary(pl.id, index, data.epgUrl, data.serverTz, data.savedAt) }
        if (added.isNotEmpty()) playlist?.let { pl -> announceNew(pl, added, list.size) }
    }

    /** New channels, films and series found by a refresh: a What's new entry and a notification. */
    private fun announceNew(p: Playlist, added: List<Channel>, total: Int) {
        // A changed playlist link can make everything look new: that isn't news.
        if (added.size > maxOf(500, total / 4)) return
        val hidden = currentExclusions()
        val shown = if (hidden.isEmpty()) added else added.filter { it.group !in hidden }
        if (shown.isEmpty()) return
        val notice = repo.addNotice(p, shown)
        if (state.value.settings.notifyNew) app.falak.player.data.Notifier.newContent(getApplication<Application>(), notice)
    }

    /** When the open playlist was downloaded (epoch ms). */
    var loadedAt by mutableStateOf(0L)
        private set
    /** A quiet refresh is running; the lists stay usable. */
    var refreshing by mutableStateOf(false)
        private set

    private fun isStale(savedAt: Long): Boolean {
        val hours = state.value.settings.autoRefreshHours
        return hours > 0 && System.currentTimeMillis() - savedAt > hours * 3_600_000L
    }

    private suspend fun backgroundRefresh(p: Playlist, token: Any) {
        // A few minutes after the app opens, once it has settled (and the guide is done): one
        // heavy job at a time keeps it smooth and avoids memory spikes right at start.
        delay(REFRESH_DELAY_MS)
        guideJob?.join()
        if (loadToken !== token || playlist?.id != p.id) return
        // Never while a film or channel plays (it would compete with the stream for memory and
        // bandwidth): wait until the player closes, then give the app a minute.
        if (repo.playerOpen || repo.previewOpen || repo.listening) {
            while (repo.playerOpen || repo.previewOpen || repo.listening) delay(10_000)
            delay(60_000)
            if (loadToken !== token || playlist?.id != p.id) return
        }
        lastRefreshTry = System.currentTimeMillis()
        // A refresh needs room to work beside the list on screen; with little left (a very big
        // playlist and a film buffering), wait for the next time the app comes to the front.
        if (heapUsed() > 0.6 && withContext(Dispatchers.Default) { Runtime.getRuntime().gc(); heapUsed() } > 0.6) return
        refreshing = true
        try {
            val fresh = repo.loadChannels(p, refresh = true, quietly = true, keep = index.byId)
            if (loadToken !== token || playlist?.id != p.id) return
            show(fresh, announce = true)
            // New channels pick up the guide on its next load; don't restart one that's running.
            if (guideJob?.isActive != true) loadGuide(force = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the saved copy; we'll try again next time.
        } catch (e: OutOfMemoryError) {
            // same
        } finally {
            refreshing = false
        }
    }

    private var followJob: Job? = null

    /** A few minutes after a playlist opens (once the guide and any refresh are done): followed series. */
    private fun scheduleFollowCheck(p: Playlist, token: Any) {
        followJob?.cancel()
        followJob = viewModelScope.launch {
            delay(REFRESH_DELAY_MS + 60_000L)
            guideJob?.join()
            refreshJob?.join()
            if (loadToken !== token || playlist?.id != p.id) return@launch
            checkFollowedSeries(p)
        }
    }

    /** Share of the app's memory limit in use (0–1). */
    private fun heapUsed(): Double {
        val r = Runtime.getRuntime()
        return (r.totalMemory() - r.freeMemory()).toDouble() / r.maxMemory()
    }

    private var refreshJob: Job? = null
    /** When a quiet refresh last ran (even if it failed): a failing provider isn't retried at every turn. */
    private var lastRefreshTry = 0L

    /** Called when the app comes back to the front: refresh quietly if the playlist got old. */
    fun refreshIfStale() {
        val p = playlist ?: return
        if (loading || refreshing || loadedAt == 0L || !isStale(loadedAt)) return
        if (refreshJob?.isActive == true) return // one at a time (it waits a few minutes before starting)
        if (System.currentTimeMillis() - lastRefreshTry < 30 * 60_000L) return
        val token = loadToken
        refreshJob = viewModelScope.launch { backgroundRefresh(p, token) }
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
                Playlist(id, name.trim().ifBlank { nextPlaylistName() }, type, url.trim(), epgUrl = epg, userAgent = ua)
            }
            SourceType.M3U_FILE -> {
                requireNotNull(file) { "Choose a playlist file" }
                val path = repo.importFile(file, id)
                Playlist(id, name.trim().ifBlank { nextPlaylistName() }, type, path, epgUrl = epg, userAgent = ua)
            }
            SourceType.XTREAM -> {
                require(url.isNotBlank() && user.isNotBlank() && pass.isNotBlank()) {
                    "Fill in server, username and password"
                }
                val server = XtreamApi.normalize(url)
                repo.xtreamLogin(server, user.trim(), pass.trim(), ua)
                Playlist(id, name.trim().ifBlank { nextPlaylistName() }, type, server, user.trim(), pass.trim(), epg, ua)
            }
        }
        repo.addPlaylist(p)
        open(p)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        app.falak.player.data.hideAddresses(e.message ?: "Something went wrong", url)
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

    private var guideRun = 0

    /**
     * (Re)loads the guide. [delayMs] lets several quick changes (adding sources one by one)
     * end up as a single download instead of one per tap.
     */
    fun loadGuide(force: Boolean, delayMs: Long = 0) {
        val p0 = playlist ?: return
        if (channels.isEmpty()) return
        guideJob?.cancel()
        guideLoading = true
        val run = ++guideRun
        guideJob = viewModelScope.launch {
            try {
                if (delayMs > 0) delay(delayMs)
                val p = playlist?.takeIf { it.id == p0.id } ?: return@launch
                guideStatus = repo.loadGuide(p, guideTargets(), p.epgUrl.ifBlank { providerEpg }, force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                guideStatus = "Guide failed to load: " + app.falak.player.data.hideAddresses(e.message.orEmpty(), p0.url, p0.epgUrl, providerEpg)
            } catch (e: OutOfMemoryError) {
                repo.clearEpg()
                guideStatus = "The guide is too large for this device. Remove some guide sources."
            } finally {
                if (run == guideRun) guideLoading = false
            }
        }
    }

    /** Saves playlist changes; new guide sources reload the guide, a time correction applies instantly. */
    fun updatePlaylist(p0: Playlist, reloadGuide: Boolean) {
        val p = p0.copy(extraEpg = p0.extraEpg.map { it.trim() }.filter { it.isNotEmpty() }.distinct())
        repo.updatePlaylist(p)
        if (playlist?.id == p.id) {
            playlist = p
            if (reloadGuide) {
                loadGuide(force = true, delayMs = 1_500)
            } else {
                // Shifting thousands of programmes is real work: keep it off the main thread.
                viewModelScope.launch(Dispatchers.Default) { runCatching { repo.publishGuide(p.epgShiftMinutes) } }
            }
        }
    }

    /** Uses the playlist as it is now (not as a screen last saw it), so quick taps never undo each other. */
    fun addGuideSources(urls: List<String>) {
        val p = playlist ?: return
        val next = (p.extraEpg + urls).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (next != p.extraEpg) updatePlaylist(p.copy(extraEpg = next), true)
    }

    fun shiftGuide(minutes: Int) {
        val p = playlist ?: return
        updatePlaylist(p.copy(epgShiftMinutes = (p.epgShiftMinutes + minutes).coerceIn(-720, 720)), false)
    }

    fun removeGuideSource(url: String) {
        val p = playlist ?: return
        updatePlaylist(p.copy(extraEpg = p.extraEpg - url, epgSourceShift = p.epgSourceShift - url), true)
    }

    /** Moves one guide source's times by [minutes] (total), found by comparing it with the others. */
    fun setGuideSourceShift(url: String, minutes: Int) {
        val p = playlist ?: return
        val next = if (minutes == 0) p.epgSourceShift - url else p.epgSourceShift + (url to minutes.coerceIn(-720, 720))
        updatePlaylist(p.copy(epgSourceShift = next), true)
    }

    fun replay(ch: Channel, prog: Program, now: Long): Channel? {
        val url = try {
            Catchup.url(ch, prog, serverTz, now)
        } catch (e: Exception) {
            null
        } ?: return null
        return Catchup.asItem(ch, prog, url)
    }

    fun remove(p: Playlist) {
        repo.removePlaylist(p.id)
        AccountStore.remove(getApplication(), p.id)
        app.falak.player.SubscriptionAlarm.cancel(getApplication(), p.id)
        app.falak.player.data.WatchNext.clear(getApplication(), p.id)
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
        repo.currentIndex = index
        repo.excludedGroups = currentExclusions()
        repo.currentSeries = if (screen == Screen.SERIES) series else null
        // For episodes, remember the series itself in "Recent".
        val recentId = if (screen == Screen.SERIES) series?.id ?: ch.id else ch.id
        if (!recentId.startsWith("cu:")) playlist?.let { repo.addRecent(it.id, recentId) }
    }

    fun openSeries(ch: Channel) {
        val p = playlist ?: return
        openedFrom = ch.id
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

    /** Follow or stop following a series (new episodes show in What's new). */
    fun toggleFollow(show: Channel, episodes: Int) {
        val p = playlist ?: return
        if (repo.isFollowed(p.id, show.seriesId)) repo.unfollow(p.id, show.seriesId) else repo.follow(p.id, show, episodes)
    }

    /**
     * Followed series of [p]: any new episodes since last time? At most every few hours, one series
     * at a time, never while something plays. Each find goes to What's new (and a notification).
     */
    private suspend fun checkFollowedSeries(p: Playlist) {
        if (repo.xtreamFor(p) == null) return
        val now = System.currentTimeMillis()
        val due = state.value.followed[p.id].orEmpty().filter { now - it.checkedAt > FOLLOW_CHECK_MS }.take(25)
        for (f in due) {
            while (repo.playerOpen || repo.previewOpen || repo.listening) delay(30_000)
            if (playlist?.id != p.id) return
            val count = try {
                repo.loadSeries(p, f.seriesId).seasons.sumOf { it.episodes.size }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue // the server didn't answer: try again next time
            }
            val known = f.episodes
            repo.setFollowedCount(p.id, f.seriesId, count)
            if (known > 0 && count > known) {
                val show = index.series.firstOrNull { it.seriesId == f.seriesId }
                    ?: Channel(id = "series:${f.seriesId}", name = f.name, group = "", url = "", logo = f.logo, kind = Kind.SERIES, seriesId = f.seriesId)
                val notice = repo.addEpisodeNotice(p, show, count - known)
                if (state.value.settings.notifyNew) app.falak.player.data.Notifier.newContent(getApplication<Application>(), notice)
            }
            delay(1_500)
        }
    }

    // ---- What's new, and pages asked for from elsewhere (the player, a notification) ----

    /** A channel to start playing as the app opens (MainActivity starts the player). */
    var autoPlay by mutableStateOf<Pair<List<Channel>, Channel>?>(null)

    /**
     * Fresh start (the app was fully closed): play again the live channel watched last, with its
     * group to zap through. Only once per start, never after a crash, and never a hidden or
     * locked channel.
     */
    private fun maybeStartLastChannel(p: Playlist) {
        if (repo.autoPlayChecked) return
        repo.autoPlayChecked = true
        // "Who's watching?" first: then that profile's last channel.
        if (choosingProfile) {
            lastChannelPending = true
            return
        }
        startLastChannel(p)
    }

    /** A fresh start waits for the profile to be picked before playing its last channel. */
    private var lastChannelPending = false

    private fun startLastChannel(p: Playlist) {
        val s = state.value
        if (!s.settings.startLastChannel || crashedLastTime) return
        val ch = s.lastLive[s.mine(p.id)]?.let { index.byId[it] } ?: return
        if (ch.kind != Kind.LIVE || ch.group in currentExclusions()) return
        autoPlay = (index.groupsOf(Kind.LIVE)[ch.group] ?: listOf(ch)) to ch
    }

    /** Set when Settings, the guide or What's new was opened from the player's Menu: Back returns to it. */
    var backToPlayer: Int? = null

    /** The item to play again (index in the play queue); MainActivity starts the player. */
    var resumePlayerAt by mutableStateOf<Int?>(null)

    /**
     * Back from Settings, the guide or What's new. Returns true when that page was a detour from
     * the player: the player comes back with what was playing (films resume where they were).
     */
    fun leaveDetour(): Boolean {
        val i = backToPlayer ?: return false
        backToPlayer = null
        screen = if (playlist != null) Screen.LIBRARY else Screen.PLAYLISTS
        resumePlayerAt = i
        return true
    }

    fun markNoticesRead() = repo.markNoticesRead()

    fun clearNotices() = repo.clearNotices()

    /** Opens the group a What's new entry points at (switching playlist if needed). */
    fun showNoticeGroup(n: app.falak.player.data.Notice, g: app.falak.player.data.NoticeGroup) {
        val p = state.value.playlists.firstOrNull { it.id == n.playlistId } ?: return
        if (playlist?.id != p.id) open(p)
        screen = Screen.LIBRARY
        section = sectionFor(g)
        group = g.group
        query = ""
    }

    /** "HOME", "MOVIES", "SETTINGS", "NOTICES"…: opens that page. */
    fun navigate(where: String) {
        when (where) {
            "SETTINGS" -> screen = Screen.SETTINGS
            "NOTICES" -> screen = Screen.NOTICES
            "DOWNLOADS" -> screen = Screen.DOWNLOADS
            "GUIDE" -> if (playlist != null) screen = Screen.GUIDE
            else -> {
                val s = Section.entries.firstOrNull { it.name == where } ?: return
                if (playlist == null) return
                screen = Screen.LIBRARY
                section = s
                group = null
                query = ""
            }
        }
    }

    // ---- hidden groups ----

    fun toggleHiddenGroup(group: String) {
        val p = playlist ?: return
        val hide = group !in state.value.settings.hiddenGroups[p.id].orEmpty()
        repo.setGroupsHidden(p.id, listOf(group), hide)
        if (hide && this.group == group) this.group = null
    }

    fun setGroupsHidden(groups: Collection<String>, hidden: Boolean) {
        val p = playlist ?: return
        repo.setGroupsHidden(p.id, groups, hidden)
    }

    /** Hidden groups of the open playlist. */
    private fun hiddenNow(): Set<String> =
        playlist?.let { state.value.settings.hiddenGroups[it.id] }.orEmpty().toHashSet()

    /** Kids mode: every group of the open playlist that wasn't chosen for kids. */
    fun kidsHidden(s: Settings = state.value.settings, idx: LibraryIndex = index): Set<String> {
        val p = playlist ?: return emptySet()
        if (!s.kidsMode || !kidsActive) return emptySet()
        val allowed = s.kidsGroups[p.id].orEmpty().toHashSet()
        val out = HashSet<String>()
        for (kind in Kind.entries) idx.groupsOf(kind).keys.filterTo(out) { it !in allowed }
        return out
    }

    /** Kids mode is on and the PIN was given for this visit to Settings (or another locked page). */
    var kidsUnlocked by mutableStateOf(false)

    /** The Kids profile is the one watching (only when kids mode is on in Settings). */
    var kidsActive by mutableStateOf(false)
        private set

    /** "Who's watching?" is on screen: each time the app opens while kids mode is on. */
    var choosingProfile by mutableStateOf(false)

    /** Back on the first page: "Leave The Player?" */
    var confirmExit by mutableStateOf(false)

    /** "Who's watching?" when the app opens: with kids mode on, or with extra profiles. */
    fun profilesWanted(s: AppState = state.value): Boolean =
        s.settings.kidsMode || (s.settings.profilesOn && s.profiles.isNotEmpty())

    /** [id]: "" the main profile, [KIDS_PROFILE], or one of the extra profiles. */
    fun chooseProfile(id: String) {
        // A kids' "that's all for today" page belongs to the profile that saw it.
        app.falak.player.data.KidsClock.dismiss()
        val kids = id == KIDS_PROFILE
        kidsActive = kids
        repo.kidsActive = kids
        repo.setActiveProfile(id)
        repo.excludedGroups = currentExclusions()
        choosingProfile = false
        kidsUnlocked = false
        group = null
        query = ""
        if (kids && screen in KIDS_LOCKED) screen = if (playlist != null) Screen.LIBRARY else Screen.PLAYLISTS
        if (lastChannelPending) {
            lastChannelPending = false
            playlist?.let { startLastChannel(it) }
        }
    }

    /** Profiles on or off (Settings): off goes back to the main profile. */
    fun setProfilesOn(on: Boolean) {
        updateSettings { it.copy(profilesOn = on) }
        if (!on && !kidsActive) repo.setActiveProfile("")
    }

    /** Locks or unlocks groups of the open playlist behind the PIN. */
    fun setLockedGroups(groups: Collection<String>, locked: Boolean) {
        val p = playlist ?: return
        updateSettings { s ->
            val cur = LinkedHashSet(s.lockedGroups[p.id].orEmpty())
            if (locked) cur += groups else cur -= groups.toSet()
            s.copy(lockedGroups = s.lockedGroups + (p.id to cur.toList()))
        }
    }

    /** The guide's preview channel, and its programme search (null = closed). */
    var guidePreview by mutableStateOf<Channel?>(null)
    var guideSearch by mutableStateOf<String?>(null)

    /** Kids mode on also empties the TV home screen's "Play next" row of grown-up things. */
    fun setKidsMode(on: Boolean) {
        updateSettings { it.copy(kidsMode = on) }
        if (on) {
            app.falak.player.data.WatchNext.clear(getApplication())
        } else {
            // No kids profile any more: back to the normal app (no question unless there are profiles).
            if (kidsActive) repo.setActiveProfile("")
            kidsActive = false
            repo.kidsActive = false
            choosingProfile = false
        }
    }

    /**
     * A film or episode picked in the TV home screen's "Play next" row ([key] = "playlistId|itemId"):
     * open its playlist if needed and play it from where it was left.
     */
    fun resumeFromHome(key: String) {
        repo.autoPlayChecked = true
        val s = state.value
        // "playlistId@profile|item" for a profile other than the main one.
        val scope = key.substringBefore('|')
        val pid = scope.substringBefore('@')
        val profile = scope.substringAfter('@', "")
        if (profile != s.activeProfile || choosingProfile) {
            // Grown-up profiles need the PIN while kids mode is on: ask who's watching instead.
            if (s.settings.kidsMode && s.settings.pin.isNotEmpty()) {
                choosingProfile = true
                return
            }
            chooseProfile(profile)
        }
        val id = key.substringAfter('|', "")
        val p = s.playlists.firstOrNull { it.id == pid } ?: return
        val saved = s.progress[key]
        viewModelScope.launch {
            if (playlist?.id != pid) open(p)
            withTimeoutOrNull(30_000) {
                while (index.byId[id] == null && (loading || index.all.isEmpty())) delay(200)
            }
            val ch = index.byId[id]
                ?: saved?.takeIf { it.url.isNotBlank() }?.let { w ->
                    Channel(id = id, name = w.title, group = "", url = w.url, logo = w.image, kind = w.kind)
                }
                ?: return@launch
            if (ch.group.isNotEmpty() && ch.group in currentExclusions()) return@launch
            screen = Screen.LIBRARY
            autoPlay = listOf(ch) to ch
        }
    }

    /** Allow or block groups of the open playlist for kids. */
    fun setKidsGroups(groups: Collection<String>, allowed: Boolean) {
        val p = playlist ?: return
        updateSettings { s ->
            val cur = LinkedHashSet(s.kidsGroups[p.id].orEmpty())
            if (allowed) cur += groups else cur -= groups.toSet()
            s.copy(kidsGroups = s.kidsGroups + (p.id to cur.toList()))
        }
    }

    /** Groups whose names look made for children: a starting point the first time kids mode is set up. */
    fun kidsSuggestions(): List<String> =
        Kind.entries.flatMap { k -> index.groupsOf(k).keys.filter { looksForKids(it) } }

    /** Live channels outside hidden groups: the only ones the guide is matched and kept for. */
    private fun guideTargets(): List<Channel> {
        val hidden = hiddenNow()
        return if (hidden.isEmpty()) index.live else index.live.filter { it.group !in hidden }
    }

    /** Hidden groups plus PIN-locked ones (while locked). */
    fun currentExclusions(): Set<String> {
        val p = playlist ?: return emptySet()
        val s = state.value.settings
        val out = s.hiddenGroups[p.id].orEmpty().toHashSet()
        out.addAll(kidsHidden(s))
        if (s.pin.isNotEmpty() && !unlocked) {
            val explicit = s.lockedGroups[p.id].orEmpty()
            for (kind in Kind.entries) {
                index.groupsOf(kind).keys.filterTo(out) { g -> g in explicit || (s.lockAdult && app.falak.player.data.ADULT_GROUP.containsMatchIn(g)) }
            }
        }
        return out
    }

    // ---- the user's own lists ----

    /** The list shown in Saved: [DEFAULT_LIST] or a list id. */
    var savedList by mutableStateOf(DEFAULT_LIST)
    /** Item whose "save to lists" sheet is open. */
    var listSheetFor by mutableStateOf<Channel?>(null)

    fun setInList(listId: String, ch: Channel, inList: Boolean) {
        val p = playlist ?: return
        repo.setInList(p.id, listId, ch.id, inList)
    }

    fun moveInList(listId: String, ch: Channel, delta: Int, toTop: Boolean = false) {
        val p = playlist ?: return
        repo.moveInList(p.id, listId, ch.id, delta, toTop)
    }

    fun createList(name: String, first: Channel?): String? {
        val p = playlist ?: return null
        return repo.createList(p.id, name, first?.id)
    }

    fun renameList(listId: String, name: String) {
        playlist?.let { repo.renameList(it.id, listId, name) }
    }

    fun deleteList(listId: String) {
        val p = playlist ?: return
        repo.deleteList(p.id, listId)
        if (savedList == listId) savedList = DEFAULT_LIST
    }

    fun moveList(listId: String, delta: Int) {
        playlist?.let { repo.moveList(it.id, listId, delta) }
    }

    /** Star: one tap saves to Favourites; with lists of your own it opens the list sheet. */
    fun onStar(ch: Channel) {
        val p = playlist ?: return
        if (state.value.favLists[state.value.mine(p.id)].isNullOrEmpty()) repo.toggleFavorite(p.id, ch.id) else listSheetFor = ch
    }

    // ---- fixing a channel's guide ----

    /** Channel whose guide is being fixed by hand. */
    var guideFixFor by mutableStateOf<Channel?>(null)

    fun guideChoices(): List<app.falak.player.data.GuideChannel> = repo.guideChannels

    /** guideId: a guide channel id, "" for no guide, null to go back to automatic. */
    fun setGuideFor(ch: Channel, guideId: String?) {
        val p = playlist ?: return
        repo.setGuideOverride(p.id, ch.epgKey, guideId)
        loadGuide(force = true, delayMs = 1_500)
    }

    fun guideOverrideFor(ch: Channel): String? = playlist?.let { state.value.settings.guideOverrides[it.id]?.get(ch.epgKey) }

    /** Free guide sources that fit the countries in this playlist, best first (worked out off the main thread). */
    var guideSuggestions by mutableStateOf<List<GuideSuggestion>>(emptyList())
        private set
    private var suggestionKey: Any? = null

    fun refreshGuideSuggestions() {
        val p = playlist ?: return
        // Suggest guides for the channels you keep, not for groups you hid.
        val key = Triple(index, p.extraEpg, hiddenNow())
        if (key == suggestionKey) return
        suggestionKey = key
        val live = guideTargets()
        val extra = p.extraEpg
        viewModelScope.launch {
            guideSuggestions = try {
                withContext(Dispatchers.Default) { suggestGuides(live, extra) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    // ---- coming back from the player ----

    /** The item to scroll to and focus after returning from the player. */
    var focusId by mutableStateOf<String?>(null)

    /** TV: the card to put the remote back on (Home, Films, Series), see [ReturnFocus]. */
    var returnFocus by mutableStateOf<ReturnFocus?>(null)

    /** The card last picked on Home, Films or Series (its row and id). */
    var lastCard: ReturnFocus? = null

    /** A film's details or a series' episodes were opened from this card. */
    private var openedFrom: String? = null

    /** Home's, Films' and Series' rows keep their sideways place while the page is away. */
    private val rowStates = HashMap<String, LazyListState>()
    fun rowState(section: Section, key: String): LazyListState = rowStates.getOrPut(section.name + "|" + key) { LazyListState() }

    /** Back on the lists from [id]: the remote goes back to it, in the row it was picked from. */
    private fun cameBack(id: String) {
        returnFocus = ReturnFocus(lastCard?.takeIf { it.id == id }?.row, id)
    }

    /** The lists are on screen again (back from details, episodes or another page). */
    fun onLibraryShown() {
        openedFrom?.let { cameBack(it) }
        openedFrom = null
    }

    fun onReturnFromPlayer() {
        val id = repo.lastPlayedId ?: return
        repo.lastPlayedId = null
        focusId = id
        cameBack(id)
        // If the user zapped into another group inside the player, follow them there.
        val ch = index.byId[id] ?: return
        if (screen == Screen.LIBRARY && section.kind == ch.kind && ch.kind != Kind.SERIES && group != null && group != ch.group) {
            group = ch.group
        }
    }

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
        java.net.URI(url.trim()).host ?: ""
    } catch (e: Exception) {
        ""
    }

    /** "Playlist 1", "Playlist 2"…: playlists are never named after their server's address. */
    private fun nextPlaylistName(): String {
        val taken = state.value.playlists.map { it.name }.toSet()
        var n = 1
        while ("Playlist $n" in taken) n++
        return "Playlist $n"
    }

    fun renamePlaylist(p: Playlist, name: String) {
        val clean = name.trim()
        if (clean.isEmpty() || clean == p.name) return
        val renamed = p.copy(name = clean)
        repo.updatePlaylist(renamed)
        if (playlist?.id == p.id) playlist = renamed
    }

    /** Older versions named unnamed playlists after the server: rename those once. */
    private fun renameAddressNamedPlaylists() {
        val s = state.value
        if (s.playlists.none { it.name == hostOf(it.url) || app.falak.player.data.looksLikeAddress(it.name) }) return
        repo.update { st ->
            val taken = HashSet<String>()
            var n = 1
            st.copy(
                playlists = st.playlists.map { p ->
                    if (p.name == hostOf(p.url) || app.falak.player.data.looksLikeAddress(p.name)) {
                        while ("Playlist $n" in taken || st.playlists.any { it.name == "Playlist $n" }) n++
                        taken += "Playlist $n"
                        p.copy(name = "Playlist $n")
                    } else {
                        p
                    }
                },
            )
        }
    }

    /**
     * Playlists can change from another device or a browser (Your devices): follow a rename of the
     * open playlist, and close it if it was removed.
     */
    /** Going on to the library, a series or a film ends a detour from the player. */
    private fun forgetDetourOnLeave() {
        viewModelScope.launch {
            androidx.compose.runtime.snapshotFlow { screen }.collect { s ->
                if (s == Screen.LIBRARY || s == Screen.PLAYLISTS || s == Screen.SERIES || s == Screen.DETAILS) backToPlayer = null
                // Kids mode: the PIN opens the locked pages for one visit only.
                if (s == Screen.LIBRARY || s == Screen.SERIES || s == Screen.DETAILS || s == Screen.GUIDE) kidsUnlocked = false
            }
        }
    }

    private var retidyJob: Job? = null

    /** Tidy channel list switched on or off: the lists are rebuilt from the playlist as it came. */
    private fun retidyIfChanged() {
        val s = state.value.settings
        val want = s.cleanNames to s.mergeDuplicates
        val old = index
        if (old.raw.isEmpty() || old.tidy == want || retidyJob?.isActive == true) return
        val p = playlist ?: return
        retidyJob = viewModelScope.launch {
            val built = withContext(Dispatchers.Default) { LibraryIndex.build(old.raw, want.first, want.second) }
            if (index !== old || playlist?.id != p.id) return@launch
            index = built
            if (repo.currentIndex === old) repo.currentIndex = built
            repo.openLibrary?.takeIf { it.playlistId == p.id }?.let { kept ->
                repo.openLibrary = app.falak.player.data.Repository.OpenLibrary(kept.playlistId, built, kept.epgUrl, kept.serverTz, kept.savedAt)
            }
            // Switched again while building: once more with the latest choice.
            retidyJob = null
            retidyIfChanged()
        }
    }

    private fun followOutsideChanges() {
        viewModelScope.launch {
            state.collect { s ->
                if ((s.settings.cleanNames to s.settings.mergeDuplicates) != index.tidy) retidyIfChanged()
            }
        }
        // Hiding or showing live groups (here or from the phone page) changes which channels get
        // a guide: reload it, from the saved copy when that already covers them.
        viewModelScope.launch {
            var last: Pair<String, Set<String>>? = null
            state.collect { s ->
                val p = playlist ?: return@collect
                val hidden = s.settings.hiddenGroups[p.id].orEmpty().toSet()
                val before = last
                last = p.id to hidden
                if (before == null || before.first != p.id || before.second == hidden || channels.isEmpty()) return@collect
                val changed = (before.second - hidden) + (hidden - before.second)
                val liveGroups = index.groupsOf(Kind.LIVE)
                if (changed.any { it in liveGroups }) loadGuide(force = false, delayMs = 2_500)
            }
        }
        viewModelScope.launch {
            state.collect { s ->
                val p = playlist ?: return@collect
                val now = s.playlists.firstOrNull { it.id == p.id }
                if (now == null) {
                    loadJob?.cancel()
                    playlist = null
                    channels = emptyList()
                    index = LibraryIndex.EMPTY
                    loading = false
                    repo.clearEpg()
                    if (screen != Screen.SYNC && screen != Screen.SETTINGS) screen = Screen.PLAYLISTS
                } else if (now.name != p.name) {
                    playlist = p.copy(name = now.name)
                }
            }
        }
    }

    // Runs last, after every property above exists (opening a playlist touches many of them).
    init {
        followOutsideChanges()
        watchAlerts()
        forgetDetourOnLeave()
        renameAddressNamedPlaylists()
        // Read and cleared: nothing is shown; the report (personal details taken out) goes to
        // the developer's counter (see CrashReports), with anything that couldn't go before.
        val crash = repo.takeCrashReport()
        crashedLastTime = crash != null
        CrashReports.submit(getApplication<Application>(), repo, crash)
        val s = state.value
        // Kids mode or extra profiles: ask who's watching each time the app opens.
        choosingProfile = profilesWanted(s)
        repo.kidsActive = false
        if (!choosingProfile) repo.setActiveProfile("")
        // After a crash, don't reopen the same playlist automatically (avoids a crash loop).
        if (!crashedLastTime && s.settings.openLast) {
            s.playlists.firstOrNull { it.id == s.lastPlaylistId }?.let { open(it) }
        }
    }
}


private val KIDS_WORDS = listOf(
    "kid", "child", "cartoon", "toon", "anime", "animation", "animated", "family", "disney", "pixar",
    "nick", "junior", "jr", "baby", "babies", "enfant", "infantil", "niños", "kinder", "cocuk", "çocuk",
    "spacetoon", "boomerang", "cbeebies", "pbs kids", "majid", "baraem", "jeem",
    "أطفال", "اطفال", "كرتون", "كارتون", "رسوم", "عائل",
)

/** True for a group name that reads like it's for children ("Kids", "Cartoons", "أطفال"…). */
internal fun looksForKids(name: String): Boolean {
    val n = name.lowercase()
    if ("adult" in n || "xxx" in n || "+18" in n || "18+" in n) return false
    return KIDS_WORDS.any { w ->
        if (w.length <= 2) Regex("(^|[^\\p{L}])${Regex.escape(w)}([^\\p{L}]|$)").containsMatchIn(n) else w in n
    }
}
