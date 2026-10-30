@file:OptIn(ExperimentalMaterial3Api::class)

package app.falak.player.ui

import app.falak.player.FreezeWatch
import app.falak.player.sync.deviceName
import app.falak.player.sync.SyncServer
import app.falak.player.repo
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.foundation.focusable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.falak.player.data.AppState
import app.falak.player.data.Channel
import app.falak.player.data.Playlist
import app.falak.player.data.SourceType
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: LibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Back on the first page leaves the app but keeps it as it is (Android 12+ does this for
        // phones already): opening it again shows the playlist at once instead of reloading it.
        // Registered first, so every page's own Back handling comes before it.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                vm.confirmExit = true
            }
        })
        handleOpen(intent)
        askForNotificationsOnce()
        app.falak.player.sync.RemoteBridge.handler = remoteHandler
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            val s = state.settings
            val dark = rememberDarkTheme(s.themeMode)
            FalakTheme(dark, s.layout) {
                // Status-bar icons follow the theme (dark icons on the light theme).
                LaunchedEffect(dark) {
                    val transparent = android.graphics.Color.TRANSPARENT
                    enableEdgeToEdge(
                        statusBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                        navigationBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                    )
                }
                Backdrop()
                // A first reminder or alert word on a phone: may notifications come when the app is closed?
                val wantsAlerts = state.reminders.isNotEmpty() || state.alertWords.isNotEmpty()
                LaunchedEffect(wantsAlerts) { if (wantsAlerts) askForNotificationsOnce(forReminders = true) }
                val tvNow = isTv()
                // Phones and tablets turn with the device on every page, as the player does (even
                // with rotation locked in the system), unless turned off in Settings; a TV stays as it is.
                LaunchedEffect(tvNow, s.turnWithPhone) {
                    requestedOrientation = if (tvNow || !s.turnWithPhone) {
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    } else {
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
                    }
                }
                // Where the user is, for problem reports ("Last steps").
                LaunchedEffect(vm.screen, vm.section, vm.group != null, s.look, tvNow) {
                    FreezeWatch.note(
                        "screen ${vm.screen.name.lowercase()} · ${vm.section.name.lowercase()}" +
                            (if (vm.group != null) " · one group" else "") + " · ${s.look} look · " + if (tvNow) "TV" else "phone",
                    )
                }
                androidx.compose.runtime.CompositionLocalProvider(LocalVoiceSearch provides voiceSearch) {
                Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Transparent) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        // Pages slide and fade: forward from the right, back from the left.
                        val tv = isTv()
                        // Kids mode on: who's watching, each time the app opens (nothing else on screen meanwhile).
                        if (vm.choosingProfile && vm.profilesWanted(state)) {
                            ProfilePicker(vm, state, onBack = { vm.confirmExit = true })
                        } else AnimatedContent(
                            targetState = vm.screen,
                            transitionSpec = {
                                if (tv) {
                                    // TV boxes: a short fade only; sliding two full pages at once stutters there.
                                    fadeIn(tween(160)).togetherWith(ExitTransition.None)
                                } else {
                                    val forward = depth(targetState) >= depth(initialState)
                                    val dir = if (forward) 1 else -1
                                    (
                                        fadeIn(tween(280, delayMillis = 40)) +
                                            slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { dir * it / 10 }
                                        ).togetherWith(
                                        fadeOut(tween(180)) +
                                            slideOutHorizontally(tween(340, easing = FastOutSlowInEasing)) { -dir * it / 14 },
                                    )
                                }
                            },
                            label = "screens",
                        ) { screen ->
                            // Kids mode: Settings, playlists, groups and What's new need the PIN.
                            if (vm.kidsActive && screen in KIDS_LOCKED && !vm.kidsUnlocked) {
                                KidsLock(vm, state)
                                return@AnimatedContent
                            }
                            when (screen) {
                                Screen.PLAYLISTS -> PlaylistsScreen(vm, state)
                                Screen.LIBRARY -> LibraryScreen(vm, state, onPlay = ::play)
                                Screen.SERIES -> SeriesScreen(vm, state, onPlay = ::play)
                                Screen.DETAILS -> DetailsScreen(vm, state, onPlay = ::play)
                                Screen.GUIDE -> GuideScreen(vm, state, onPlay = ::play)
                                Screen.SETTINGS -> SettingsScreen(vm, state)
                                Screen.GROUPS -> GroupsScreen(vm, state)
                                Screen.SYNC -> SyncScreen(vm, state)
                                Screen.NOTICES -> NoticesScreen(vm, state, onPlay = ::play)
                                Screen.KIDS -> KidsScreen(vm, state)
                                Screen.LOCKS -> LocksScreen(vm, state)
                                Screen.PROFILES -> ProfilesScreen(vm, state)
                                Screen.REMOTE -> RemoteScreen(vm, state)
                                Screen.PRAYER -> PrayerScreen(vm, state)
                                Screen.DOWNLOADS -> DownloadsScreen(vm, state, onPlay = ::play)
                                Screen.ARRANGE -> ArrangeScreen(vm, state)
                            }
                        }
                        // Prayer time reminder (Settings → Prayer times), over whatever is on screen.
                        val prayer by app.falak.player.PrayerAlerts.current.collectAsStateWithLifecycle()
                        PrayerBanner(prayer, paused = false) { app.falak.player.PrayerAlerts.dismiss() }
                        // A programme reminder or alert word: "Watch now" plays the channel.
                        val alert by app.falak.player.ProgramAlerts.current.collectAsStateWithLifecycle()
                        ProgramAlertCard(
                            alert,
                            keysHint = false,
                            onWatch = { a ->
                                app.falak.player.ProgramAlerts.dismiss()
                                vm.channelForAlert(a)?.let { (list, ch) -> play(list, ch) }
                            },
                            onDismiss = { app.falak.player.ProgramAlerts.dismiss() },
                        )
                        // Subscription watch: ending within a week, once a day on the lists (a reminder comes first).
                        val subscriptionNote = vm.account
                            ?.takeIf { vm.screen == Screen.LIBRARY && !vm.accountNoteHidden && alert == null && !vm.kidsActive && !vm.choosingProfile }
                            ?.let { a ->
                                val format = android.text.format.DateFormat.getMediumDateFormat(this@MainActivity)
                                app.falak.player.data.Accounts.warning(a, System.currentTimeMillis(), { format.format(java.util.Date(it)) })
                                    ?.let { w -> if (state.playlists.size > 1) "${vm.playlist?.name.orEmpty()}: $w" else w }
                            }
                        SubscriptionCard(subscriptionNote) { vm.hideAccountNote() }
                        // Back from a live channel: it carries on small in a corner (Settings → Mini player).
                        val mini by MiniPlayerState.request.collectAsStateWithLifecycle()
                        mini?.let { m ->
                            if (m.playlistId != vm.playlist?.id) {
                                // Another playlist opened meanwhile: its channel isn't this one's.
                                LaunchedEffect(m) { MiniPlayerState.close() }
                            } else {
                                androidx.compose.runtime.key(m) {
                                    MiniPlayer(
                                        m,
                                        onOpen = { openMini(m) },
                                        // TVs: the remote goes back to the channel in the list.
                                        onClose = {
                                            MiniPlayerState.close()
                                            if (tv) {
                                                vm.focusId = m.channel.id
                                                vm.returnFocus = ReturnFocus(null, m.channel.id)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        // One small picture at a time: the guide's preview takes over from the mini player.
                        LaunchedEffect(vm.guidePreview) { if (vm.guidePreview != null) MiniPlayerState.close() }
                        // Kids screen time up, or bedtime: its page, over everything (nothing plays meanwhile).
                        val kidsBlock by app.falak.player.data.KidsClock.blocked.collectAsStateWithLifecycle()
                        kidsBlock?.takeIf { vm.kidsActive }?.let { b ->
                            LaunchedEffect(b) {
                                MiniPlayerState.close()
                                vm.guidePreview = null
                            }
                            KidsTimePage(b, state) { app.falak.player.data.KidsClock.dismiss() }
                        }
                    }
                    // Left alone for a while on the lists: the ambient screen.
                    LaunchedEffect(Unit) {
                        while (true) {
                            kotlinx.coroutines.delay(15_000)
                            val idle = android.os.SystemClock.elapsedRealtime() - lastInput
                            val calm = vm.screen in AMBIENT_SCREENS && !vm.choosingProfile && vm.guidePreview == null &&
                                vm.listSheetFor == null && !vm.confirmExit && MiniPlayerState.request.value == null
                            if (!ambient && vm.state.value.settings.ambient && idle >= AMBIENT_IDLE_MS && calm &&
                                lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                            ) {
                                ambient = true
                            }
                        }
                    }
                    if (ambient) {
                        val slides = remember {
                            ambientSlides(vm.index.movies, vm.index.series, vm.heroArt, vm.currentExclusions())
                        }
                        AmbientScreen(slides, paused = null) { leaveAmbient() }
                    }
                    vm.listSheetFor?.let { ch ->
                        val inSaved = vm.screen == Screen.LIBRARY && vm.section == Section.FAVORITES && vm.query.isBlank()
                        ItemSheet(vm, state, ch, orderInList = if (inSaved) vm.savedList else null) { vm.listSheetFor = null }
                    }
                    vm.guideFixFor?.let { ch -> GuideFixDialog(vm, ch) { vm.guideFixFor = null } }
                    // A fresh start: the channel watched last plays again.
                    // (Once the profile is chosen; the Kids profile only gets its own groups.)
                    vm.autoPlay?.takeIf { !(vm.choosingProfile && vm.profilesWanted(state)) }?.let { (list, ch) ->
                        LaunchedEffect(ch.id) {
                            vm.autoPlay = null
                            if (ch.group.isEmpty() || ch.group !in vm.currentExclusions()) play(list, ch)
                        }
                    }
                    if (vm.confirmExit) {
                        ExitDialog(
                            onStay = { vm.confirmExit = false },
                            onExit = {
                                vm.confirmExit = false
                                // Closed on purpose: next time it opens like a fresh start (last channel, profiles).
                                repo.autoPlayChecked = false
                                finish()
                            },
                        )
                    }
                    // Back from a page opened in the player's Menu: play again what was playing.
                    vm.resumePlayerAt?.let { i ->
                        LaunchedEffect(i) {
                            vm.resumePlayerAt = null
                            if (repo.playQueue.isNotEmpty()) {
                                MiniPlayerState.close()
                                fromPlayer = true
                                startActivity(
                                    Intent(this@MainActivity, PlayerActivity::class.java)
                                        .putExtra(PlayerActivity.EXTRA_INDEX, i.coerceIn(0, repo.playQueue.lastIndex)),
                                )
                            }
                        }
                    }
                    UpdateDialogs(vm)
                    LaunchedEffect(Unit) { vm.checkForUpdates(manual = false) }
                }
                }
            }
        }
    }

    /** Set when we open the player, so coming back from it isn't taken for a return to the app. */
    private var fromPlayer = false

    /** Opens What's new when started from a "New in …" notification, or resumes a film picked on the TV home screen. */
    private fun handleOpen(intent: Intent?) {
        intent?.getStringExtra(app.falak.player.data.WatchNext.EXTRA_RESUME)?.let { key ->
            intent.removeExtra(app.falak.player.data.WatchNext.EXTRA_RESUME)
            vm.resumeFromHome(key)
            return
        }
        // A reminder's notification: play its channel.
        val alertChannel = intent?.getStringExtra(app.falak.player.AlertAlarm.EXTRA_CHANNEL)
        val alertPlaylist = intent?.getStringExtra(app.falak.player.AlertAlarm.EXTRA_PLAYLIST)
        if (intent != null && alertChannel != null && alertPlaylist != null) {
            intent.removeExtra(app.falak.player.AlertAlarm.EXTRA_CHANNEL)
            intent.removeExtra(app.falak.player.AlertAlarm.EXTRA_PLAYLIST)
            app.falak.player.ProgramAlerts.dismiss()
            vm.playFromAlert(alertPlaylist, alertChannel)
            return
        }
        intent?.getStringExtra(app.falak.player.data.Notifier.EXTRA_OPEN)?.let { where ->
            repo.autoPlayChecked = true // opened for What's new: don't start a channel over it
            intent.removeExtra(app.falak.player.data.Notifier.EXTRA_OPEN)
            vm.navigate(where)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpen(intent)
    }

    // ---- voice search ----

    private val voiceLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val heard = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!heard.isNullOrEmpty()) vm.heardSearch(heard)
    }

    /** Starts voice search, or null on devices without a speech recognizer (some TV boxes). */
    private val voiceSearch: (() -> Unit)? by lazy {
        val probe = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        val there = forceVoiceForTest || packageManager.queryIntentActivities(probe, 0).isNotEmpty()
        if (there) ::startVoiceSearch else null
    }

    private fun startVoiceSearch() {
        if (vm.playlist == null) return
        val lang = VoiceSearch.language(vm.state.value.settings.voiceLang)
        try {
            voiceLauncher.launch(VoiceSearch.intent(lang))
        } catch (e: android.content.ActivityNotFoundException) {
            // The recognizer went away (disabled since): nothing to do.
        }
    }

    /** Android 13+: ask once whether "New in your playlist" notifications may be shown. */
    private val notifyPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    private fun askForNotificationsOnce(forReminders: Boolean = false) {
        val s = vm.state.value.settings
        if (android.os.Build.VERSION.SDK_INT < 33 || !(s.notifyNew || forReminders) || s.askedNotify) return
        // On a TV the reminder shows in the app; its notifications aren't seen there.
        if (forReminders && detectTv(this)) return
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        vm.updateSettings { it.copy(askedNotify = true) }
        if (!granted) notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        // Downloads left waiting (the app was closed) carry on.
        app.falak.player.OfflineCenter.resumeWaiting()
        // A page asked for from inside the player (Menu: Films, Series, Settings…).
        repo.pendingNav?.let { where ->
            repo.pendingNav = null
            val back = repo.pendingReturnIndex
            repo.pendingReturnIndex = null
            vm.navigate(where)
            // Settings, the guide or What's new from the player: Back there returns to the player.
            vm.backToPlayer = back
        }
        FreezeWatch.note(if (fromPlayer) "back from the player" else "app in front")
        // Ramadan's Suhoor reminder (phones): planned again whenever the app is opened.
        app.falak.player.SuhoorAlarm.plan(this, repo)
        vm.onReturnFromPlayer()
        // Leaving a film or channel must not start a playlist reload: only a return to the app
        // from elsewhere (it may have sat in the background for hours) checks for a stale playlist.
        if (fromPlayer) fromPlayer = false else vm.refreshIfStale()
    }

    /** How deep a page sits, so transitions know which way to slide. */
    private fun depth(s: Screen): Int = when (s) {
        Screen.PLAYLISTS -> 0
        Screen.LIBRARY -> 1
        Screen.SERIES, Screen.DETAILS, Screen.GUIDE, Screen.NOTICES, Screen.DOWNLOADS -> 2
        Screen.SETTINGS -> 3
        Screen.GROUPS, Screen.SYNC, Screen.KIDS, Screen.LOCKS, Screen.PROFILES, Screen.REMOTE, Screen.PRAYER -> 4
        Screen.ARRANGE -> 5
    }

    // ---- ambient screen ----

    private var ambient by mutableStateOf(false)
    private var lastInput = android.os.SystemClock.elapsedRealtime()

    private fun leaveAmbient() {
        ambient = false
        lastInput = android.os.SystemClock.elapsedRealtime()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastInput = android.os.SystemClock.elapsedRealtime()
    }

    /** While the ambient screen shows, the first key only brings the lists back. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (ambient) {
            if (event.action == android.view.KeyEvent.ACTION_UP) leaveAmbient()
            return true
        }
        // The remote's search (microphone) key, when Android passes it to the app: voice search.
        val speak = voiceSearch
        if (event.keyCode == android.view.KeyEvent.KEYCODE_SEARCH && speak != null && vm.playlist != null) {
            if (event.action == android.view.KeyEvent.ACTION_UP) speak()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (app.falak.player.sync.RemoteBridge.handler === remoteHandler) app.falak.player.sync.RemoteBridge.handler = null
        super.onDestroy()
    }

    /** The phone remote's requests (on the main thread). */
    private val remoteHandler = object : app.falak.player.sync.RemoteHandler {
        override fun library(): Pair<app.falak.player.data.LibraryIndex, Set<String>>? {
            if (vm.playlist == null || vm.choosingProfile || vm.index.all.isEmpty()) return null
            return vm.index to vm.currentExclusions()
        }

        override fun play(list: List<Channel>, ch: Channel) = this@MainActivity.play(list, ch)

        override fun openSeries(ch: Channel) {
            toFront()
            vm.openSeries(ch)
        }

        override fun search(text: String) {
            toFront()
            vm.screen = Screen.LIBRARY
            vm.group = null
            vm.query = text
        }

        override fun home() {
            toFront()
            vm.screen = Screen.LIBRARY
            vm.section = Section.HOME
            vm.group = null
            vm.query = ""
        }
    }

    /** Closes the player if it's on top, so this screen shows. */
    private fun toFront() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    /** The mini player back to full screen, with the list it was zapping through. */
    private fun openMini(m: MiniPlayerState.Request) {
        MiniPlayerState.close()
        vm.backToPlayer = null
        repo.playQueue = m.list
        fromPlayer = true
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_INDEX, m.index))
    }

    private fun play(list: List<Channel>, channel: Channel) {
        // Kids screen time used up, or bedtime: its page instead of the player.
        if (vm.kidsActive) {
            app.falak.player.data.KidsClock.state(this, vm.state.value.settings).block?.let {
                app.falak.player.data.KidsClock.stop(it)
                return
            }
        }
        MiniPlayerState.close()
        vm.backToPlayer = null // something new is playing: no detour to go back to
        vm.prepareQueue(list, channel)
        val index = list.indexOf(channel).coerceAtLeast(0)
        if (vm.state.value.settings.player == app.falak.player.player.Engine.EXTERNAL.key) {
            // "Another app" chosen in Settings: hand the stream straight to it (a downloaded copy
            // as the file itself, with permission to read it).
            val saved = app.falak.player.OfflineCenter.fileFor(channel.id)
            val uri = saved?.let { androidx.core.content.FileProvider.getUriForFile(this, "$packageName.offline", it) }
                ?: android.net.Uri.parse(channel.url)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "video/*")
                .putExtra("title", channel.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                startActivity(Intent.createChooser(intent, "Play with"))
                return
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "No other video player is installed; using the built-in one.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        fromPlayer = true
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_INDEX, index))
    }
}

@Composable
private fun UpdateDialogs(vm: LibraryViewModel) {
    vm.update?.let { info ->
        val progress = vm.updateProgress
        AlertDialog(
            onDismissRequest = { vm.dismissUpdate() },
            title = { Text("Update available") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(info.title, style = MaterialTheme.typography.titleMedium)
                    if (info.notes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(info.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (progress != null) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text("Downloading… ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = progress == null, onClick = { vm.installUpdate() }) { Text("Update now") }
            },
            dismissButton = {
                TextButton(enabled = progress == null, onClick = { vm.dismissUpdate() }) { Text("Later") }
            },
        )
    }
    vm.updateMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.updateMessage = null },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { vm.updateMessage = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun PlaylistsScreen(vm: LibraryViewModel, state: AppState) {
    var adding by remember { mutableStateOf(state.playlists.isEmpty()) }
    var toDelete by remember { mutableStateOf<Playlist?>(null) }
    var toRename by remember { mutableStateOf<Playlist?>(null) }
    // Combining playlists: a new combined one (true), or one being edited.
    var combiningNew by remember { mutableStateOf(false) }
    var combineEdit by remember { mutableStateOf<Playlist?>(null) }
    val tv = isTv()

    BackHandler(enabled = vm.playlist != null) { vm.screen = Screen.LIBRARY }

    Column(Modifier.fillMaxSize().padding(horizontal = if (tv) 56.dp else 22.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(if (tv) 44.dp else 38.dp)
            Spacer(Modifier.weight(1f))
            Row(Modifier.offset(x = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (tv) Clock(Modifier.padding(horizontal = 8.dp))
                IconButton(onClick = { vm.screen = Screen.SETTINGS }) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = Paper)
                }
            }
        }
        Spacer(Modifier.height(if (tv) 40.dp else 28.dp))
        Wordmark(if (tv) 46 else 38)
        Spacer(Modifier.height(8.dp))
        Text(
            "Live TV, films and series from your own playlists.",
            fontFamily = Serif,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Medium,
            fontSize = 17.sp,
            color = Muted,
        )
        Spacer(Modifier.height(30.dp))
        if (tv) {
            Row(verticalAlignment = Alignment.Bottom) {
                Kicker("Your playlists", color = Muted)
                Spacer(Modifier.weight(1f))
                GhostButton("Devices", Icons.Filled.Share) { vm.screen = Screen.SYNC }
                Spacer(Modifier.width(10.dp))
                PaperButton("Add playlist", Icons.Filled.Add) { adding = true }
            }
        } else {
            // Phone: the two buttons share a row of their own, equal width, above the list's title.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PaperButton("Add playlist", Icons.Filled.Add, Modifier.weight(1f)) { adding = true }
                GhostButton("Devices", Icons.Filled.Share, Modifier.weight(1f)) { vm.screen = Screen.SYNC }
            }
            Spacer(Modifier.height(24.dp))
            Kicker("Your playlists", color = Muted)
        }
        Spacer(Modifier.height(10.dp))
        Rule()
        if (state.playlists.isEmpty()) {
            Text(
                "Add your provider's M3U link, an M3U file, or your Xtream login to begin. The Player doesn't come with any channels.",
                fontFamily = Sans,
                fontSize = 14.sp, lineHeight = 20.sp,
                color = Muted,
                modifier = Modifier.padding(vertical = 18.dp),
            )
        }
        LazyColumn {
            items(state.playlists, key = { it.id }) { p ->
                val current = p.id == vm.playlist?.id
                val shape = RoundedCornerShape(8.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .animateItem()
                        .focusRing(shape)
                        .clip(shape)
                        .clickable { vm.open(p) }
                        .padding(vertical = 16.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (p.isCombined) {
                            "COMBINED"
                        } else {
                            when (p.type) {
                                SourceType.M3U_URL -> "M3U"
                                SourceType.M3U_FILE -> "FILE"
                                SourceType.XTREAM -> "XTREAM"
                            }
                        },
                        style = Tabular.copy(fontSize = 11.sp, letterSpacing = 1.2.sp),
                        color = Faint,
                        maxLines = 1,
                        modifier = Modifier.width(84.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = Paper, maxLines = 1)
                        if (p.isCombined) {
                            // What it puts together.
                            val names = p.sources.mapNotNull { id -> state.playlists.firstOrNull { it.id == id }?.name }
                            Text(
                                if (names.isEmpty()) "Its playlists were removed" else names.joinToString(" + "),
                                fontFamily = Sans,
                                fontSize = 12.sp,
                                color = Muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (current) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                                LiveDot(6.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("Open now", fontFamily = Sans, fontSize = 12.sp, color = Muted)
                            }
                        }
                    }
                    IconButton(onClick = { if (p.isCombined) combineEdit = p else toRename = p }) {
                        Icon(Icons.Outlined.Edit, contentDescription = if (p.isCombined) "Edit" else "Rename", tint = Faint)
                    }
                    IconButton(onClick = { toDelete = p }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Remove", tint = Faint)
                    }
                }
                Rule()
            }
            item(key = "__credit") {
                Text(
                    "Made by Ahmed",
                    fontFamily = Serif,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = Faint,
                    modifier = Modifier.padding(top = 28.dp, bottom = 12.dp),
                )
            }
        }
    }

    // Combining needs two playlists of your own (not combined ones).
    val canCombine = state.playlists.count { !it.isCombined } >= 2
    if (adding) {
        AddPlaylistDialog(vm, onCombine = if (canCombine) ({ adding = false; combiningNew = true }) else null) { adding = false }
    }
    if (combiningNew) CombineDialog(vm, state, null) { combiningNew = false }
    combineEdit?.let { p -> CombineDialog(vm, state, p) { combineEdit = null } }

    toRename?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { toRename = null },
            title = { Text("Rename playlist") },
            text = { Field(name, { name = it }, "Name") },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { vm.renamePlaylist(p, name); toRename = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { toRename = null }) { Text("Cancel") } },
        )
    }

    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Remove ${p.name}?") },
            text = {
                // Combined playlists it is part of carry on without it.
                val partOf = state.playlists.filter { it.isCombined && p.id in it.sources }.map { it.name }
                Text(
                    "Its saved items and history will be deleted too." +
                        if (partOf.isEmpty()) "" else " ${partOf.joinToString(", ")} won't include it any more.",
                )
            },
            confirmButton = { TextButton(onClick = { vm.remove(p); toDelete = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

/** A combined playlist: new ([existing] null) or one being changed. */
@Composable
private fun CombineDialog(vm: LibraryViewModel, state: AppState, existing: Playlist?, onClose: () -> Unit) {
    val choices = state.playlists.filter { !it.isCombined }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    val picked = remember { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(existing?.sources.orEmpty()) } }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (existing == null) "Combine playlists" else "Edit ${existing.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "One list with the channels, films and series of the playlists you tick. Groups with the same name " +
                        "show as one; the same channel, film or series twice shows once.",
                    fontFamily = Sans,
                    fontSize = 13.sp,
                    color = Muted,
                )
                Spacer(Modifier.height(4.dp))
                Field(name, { name = it }, if (existing == null) "Name (optional)" else "Name")
                Spacer(Modifier.height(4.dp))
                choices.forEach { p ->
                    CheckLine(p.name, p.id in picked) { on ->
                        if (on) picked.add(p.id) else picked.remove(p.id)
                        error = null
                    }
                }
                if (picked.size >= 2) {
                    Text(
                        "In this order: " + picked.mapNotNull { id -> choices.firstOrNull { it.id == id }?.name }.joinToString(" → ") +
                            ". When two have the same film, the first one's shows.",
                        fontFamily = Sans,
                        fontSize = 12.sp,
                        color = Faint,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val err = if (existing == null) vm.addCombined(name, picked.toList()) else vm.editCombined(existing, name, picked.toList())
                if (err == null) onClose() else error = err
            }) { Text(if (existing == null) "Combine" else "Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun AddPlaylistDialog(vm: LibraryViewModel, onCombine: (() -> Unit)? = null, onClose: () -> Unit) {
    var type by remember { mutableStateOf(SourceType.M3U_URL) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    var ua by remember { mutableStateOf("") }
    var file by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) file = picked
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Add playlist") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        SourceType.M3U_URL to "M3U link",
                        SourceType.M3U_FILE to "M3U file",
                        SourceType.XTREAM to "Xtream",
                    ).forEach { (t, label) ->
                        FilterChip(
                            selected = type == t,
                            onClick = { type = t; error = null },
                            label = { Text(label) },
                        )
                    }
                }
                // Two or more playlists already: put them together in one list.
                if (onCombine != null) {
                    FilterChip(selected = false, onClick = onCombine, label = { Text("Combine my playlists…") })
                }
                Field(name, { name = it }, "Name (optional)")
                when (type) {
                    SourceType.M3U_URL ->
                        Field(url, { url = it }, "Playlist link (http://…)", KeyboardType.Uri)
                    SourceType.M3U_FILE ->
                        OutlinedButton(
                            onClick = {
                                try {
                                    picker.launch(arrayOf("*/*"))
                                } catch (e: ActivityNotFoundException) {
                                    error = "This device has no file picker. Use an M3U link instead."
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(file?.lastPathSegment?.substringAfterLast('/') ?: "Choose .m3u file")
                        }
                    SourceType.XTREAM -> {
                        Field(url, { url = it }, "Server (http://host:port)", KeyboardType.Uri)
                        Field(user, { user = it }, "Username")
                        Field(pass, { pass = it }, "Password", KeyboardType.Password, password = true)
                    }
                }
                Field(epg, { epg = it }, "TV guide (EPG) link — optional", KeyboardType.Uri)
                Field(ua, { ua = it }, "User-Agent — optional")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        val err = vm.addPlaylist(type, name, url, user, pass, epg, ua, file)
                        busy = false
                        if (err == null) onClose() else error = err
                    }
                },
            ) { Text(if (busy) "Checking…" else "Add") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Pages the ambient screen may come up over (not Settings, the guide's preview or pages being typed in). */
private val AMBIENT_SCREENS = setOf(Screen.LIBRARY, Screen.SERIES, Screen.DETAILS, Screen.NOTICES, Screen.PLAYLISTS)

/** Screenshot tests: show voice search as if the device had a speech recognizer. */
@androidx.annotation.VisibleForTesting
internal var forceVoiceForTest = false
