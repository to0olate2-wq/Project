@file:OptIn(ExperimentalMaterial3Api::class)

package app.falak.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import app.falak.player.update.Updater
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.falak.player.BuildConfig
import app.falak.player.data.AppState
import app.falak.player.data.guideSourceName
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.runtime.key
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import app.falak.player.R
import kotlinx.coroutines.delay
import app.falak.player.player.FrameRateMatch
import app.falak.player.player.SubtitleStyle
import app.falak.player.player.CaptionLayout
import app.falak.player.player.CaptionText
import androidx.compose.foundation.layout.aspectRatio

/** Public privacy policy (also linked from the Google Play listing). */
const val PRIVACY_URL = "https://github.com/to0olate2-wq/Project/blob/main/PRIVACY.md"

/** TV, Settings: whether the remote has been placed yet, and whether it should go into the page next. */
private class SettingsNav {
    var started = false
    var wantPanel = false
}

/** The rows of the list of pages (TV: coming back to one of these puts the remote on the list). */
private val HUB_KEYS: Set<String> = SettingsPage.entries.filter { it.parent == null }.map { it.name }.toSet() + "PRAYER"

/** The pages of Settings, each opened from the list on its first page. */
enum class SettingsPage(val title: String) {
    APPEARANCE("Appearance"),
    PLAYBACK("Picture & playback"),
    RECORDINGS("Recordings & pause live TV"),
    SOUND("Sound & subtitles"),
    PLAYLISTS("Playlists & TV guide"),
    REMINDERS("Reminders & alerts"),
    FAMILY("Profiles & parental control"),
    DEVICES("Devices & phone remote"),
    ABOUT("About"),
    SUBTITLE_STYLE("Subtitle style"),
    ;

    /** A page opened from another page (Back goes there), not from the list. */
    val parent: SettingsPage?
        get() = if (this == SUBTITLE_STYLE) SOUND else null
}

@Composable
fun SettingsScreen(vm: LibraryViewModel, state: AppState) {
    val s = state.settings
    val p = vm.playlist
    val pinSet = s.pin.isNotEmpty()
    var editPin by remember { mutableStateOf(false) }
    // Kids mode asked for before a PIN existed: switch it on once the PIN is saved.
    var kidsAfterPin by remember { mutableStateOf(false) }
    var guideSources by remember { mutableStateOf(false) }
    var guideShift by remember { mutableStateOf(false) }
    var editSubs by remember { mutableStateOf(false) }
    var removeSpeech by remember { mutableStateOf(false) }
    var serviceReport by remember { mutableStateOf(false) }
    var addAlertWord by remember { mutableStateOf(false) }
    var homeRowsOpen by remember { mutableStateOf(false) }
    var remoteButtonsOpen by remember { mutableStateOf(false) }
    // Coming back from Android's settings (overlay, alarms): what they allow is read again.
    var resumed by remember { mutableStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    // Changing parental settings needs the PIN once per session.
    val guarded: (() -> Unit) -> Unit = { action ->
        if (pinSet && !vm.unlocked) {
            pendingAction = action
        } else {
            action()
        }
    }
    // TV: the list of pages on the left and the page picked on the right, both on screen.
    val tvLayout = isTv()
    val twoPane = tvLayout
    // (TV: a page is always open on the right; the first until another is picked.)
    val page = vm.settingsPage ?: if (twoPane) {
        vm.settingsFocus?.let { k -> SettingsPage.entries.firstOrNull { it.name == k && it.parent == null } } ?: SettingsPage.APPEARANCE
    } else {
        null
    }
    val appContext = androidx.compose.ui.platform.LocalContext.current
    // Checked each time a page opens (the user may have changed it in Android's settings).
    val notificationsOff = remember(page) {
        !androidx.core.app.NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    }
    // TV: where the remote is (the list or the page), and asking for it to go into the page.
    var panelFocused by remember { mutableStateOf(false) }
    var panelTick by remember { mutableIntStateOf(0) }
    val nav = remember { SettingsNav() }
    val hubFocus = remember { LinkedHashMap<String, FocusRequester>() }
    // Back: from a page to the list of pages; from the list, out of Settings.
    // (TV: from the page to its place in the list; from the list, out of Settings.)
    val back: () -> Unit = {
        when {
            twoPane && panelFocused && page != null && page.parent != null -> {
                vm.settingsFocus = page.name
                vm.settingsPage = page.parent
                nav.wantPanel = true
            }
            twoPane && panelFocused && page != null -> runCatching { hubFocus[page.name]?.requestFocus() }
            twoPane -> vm.closePage()
            page != null -> {
                vm.settingsFocus = page.name
                vm.settingsPage = page.parent
            }
            else -> vm.closePage()
        }
    }
    val openPage: (SettingsPage) -> Unit = {
        vm.settingsFocus = it.name
        vm.settingsPage = it
        if (twoPane) {
            nav.wantPanel = true
            panelTick++
        }
    }
    // A page of its own (Profiles, Phone remote…): Back from there comes back to this row.
    val go: (String, () -> Unit) -> Unit = { key, action -> vm.settingsFocus = key; action() }
    val index = vm.index
    LaunchedEffect(index, p?.extraEpg) { vm.refreshGuideSuggestions() }
    val lockedHere = if (p != null) s.lockedGroups[p.id].orEmpty() else emptyList()

    BackHandler { back() }

    // The remote's focus: the row last opened when coming back to a page, otherwise its first row.
    val focus = remember(page) { LinkedHashMap<String, FocusRequester>() }
    fun focusAt(key: String): Modifier = Modifier.focusRequester(focus.getOrPut(key) { FocusRequester() })
    // TV: the remote resting on a page in the list shows that page on the right (a moment later,
    // so running down the list doesn't redraw every page on the way).
    var hovered by remember { mutableStateOf<String?>(null) }
    fun hubAt(key: String): Modifier = if (!twoPane) {
        focusAt(key)
    } else {
        Modifier
            .focusRequester(hubFocus.getOrPut(key) { FocusRequester() })
            .onFocusChanged { if (it.isFocused) hovered = key }
    }
    LaunchedEffect(hovered) {
        val target = SettingsPage.entries.firstOrNull { it.name == hovered && it.parent == null } ?: return@LaunchedEffect
        delay(160)
        if (vm.settingsPage != target && vm.settingsPage?.parent != target) {
            vm.settingsFocus = null
            vm.settingsPage = target
        }
    }
    LaunchedEffect(page, panelTick) {
        val want = vm.settingsFocus
        if (twoPane) {
            // Coming in: the list, at the page last opened, unless coming back to a row of a page
            // (from a page of its own, e.g. Hidden groups). Later: only when asked to go in.
            val first = !nav.started
            val toPanel = if (first) want != null && want !in HUB_KEYS else nav.wantPanel
            nav.started = true
            nav.wantPanel = false
            if (!first && !toPanel) return@LaunchedEffect
            repeat(10) {
                delay(50)
                val order = if (toPanel) {
                    listOfNotNull(want?.let { focus[it] }) + focus.values
                } else {
                    listOfNotNull((want ?: page?.let { (it.parent ?: it).name })?.let { hubFocus[it] }) + hubFocus.values
                }
                if (order.any { runCatching { it.requestFocus() }.isSuccess }) return@LaunchedEffect
            }
            return@LaunchedEffect
        }
        repeat(10) {
            delay(50)
            val order = listOfNotNull(want?.let { focus[it] }) + focus.values
            if (order.any { runCatching { it.requestFocus() }.isSuccess }) return@LaunchedEffect
        }
    }

    // The list of pages: each with what's set now.
    val hubItems: LazyListScope.() -> Unit = {
            item { Spacer(Modifier.height(6.dp)) }
            item {
                val theme = when (s.themeMode) {
                    "light" -> "Light"
                    "dark" -> "Dark"
                    else -> "Automatic"
                }
                HubRow(
                    { ThemeGlyph(Paper) },
                    "Appearance",
                    "$theme theme · ${if (s.look == "sidebar") "Sidebar" else "Classic"} look · Ambient screen ${if (s.ambient) "on" else "off"}",
                    hubAt(SettingsPage.APPEARANCE.name),
                    selected = twoPane && (page == SettingsPage.APPEARANCE || page?.parent == SettingsPage.APPEARANCE),
                ) { openPage(SettingsPage.APPEARANCE) }
            }
            item {
                val engine = app.falak.player.player.Engine.of(s.player)?.short ?: "Automatic player"
                val picture = listOf("Fit", "Fill", "Zoom").getOrElse(s.aspect) { "Fit" }
                HubRow(
                    { HubIcon(painterResource(R.drawable.ic_pl_player)) },
                    "Picture & playback",
                    "$engine · Picture: $picture · ${if (s.autoNextEpisode) "Auto next episode" else "Next episode by hand"}",
                    hubAt(SettingsPage.PLAYBACK.name),
                    selected = twoPane && (page == SettingsPage.PLAYBACK || page?.parent == SettingsPage.PLAYBACK),
                ) { openPage(SettingsPage.PLAYBACK) }
            }
            item {
                val recs = app.falak.player.record.RecordCenter.book.collectAsState().value.recordings
                val saved = recs.count { it.state == app.falak.player.record.RecState.DONE }
                val planned = recs.count { it.state == app.falak.player.record.RecState.SCHEDULED || it.state == app.falak.player.record.RecState.RECORDING }
                HubRow(
                    { HubIcon(painterResource(R.drawable.ic_pl_record)) },
                    "Recordings & pause live TV",
                    listOf(
                        if (s.liveBuffer == "on") "Pause & rewind on" else "Pause & rewind off",
                        when (saved) {
                            0 -> "No recordings"
                            1 -> "1 recording"
                            else -> "$saved recordings"
                        },
                        if (planned > 0) "$planned planned" else null,
                    ).filterNotNull().joinToString(" · "),
                    hubAt(SettingsPage.RECORDINGS.name),
                    selected = twoPane && (page == SettingsPage.RECORDINGS || page?.parent == SettingsPage.RECORDINGS),
                ) { openPage(SettingsPage.RECORDINGS) }
            }
            item {
                val night = when (s.nightSound) {
                    "on" -> "always"
                    "off" -> "off"
                    else -> "automatic"
                }
                val audio = LANGUAGES.firstOrNull { it.first == s.audioLang }?.second ?: "Default"
                HubRow(
                    { HubIcon(painterResource(R.drawable.ic_pl_audio)) },
                    "Sound & subtitles",
                    "Night sound $night · Audio: $audio · " +
                        if (s.subtitlesOn) "Subtitles shown automatically" else "Subtitles when you choose them",
                    hubAt(SettingsPage.SOUND.name),
                    selected = twoPane && (page == SettingsPage.SOUND || page?.parent == SettingsPage.SOUND),
                ) { openPage(SettingsPage.SOUND) }
            }
            item {
                val refresh = when (s.autoRefreshHours) {
                    0 -> "never"
                    24 -> "daily"
                    else -> "every ${s.autoRefreshHours} h"
                }
                HubRow(
                    { HubIcon(painterResource(R.drawable.ic_nav_playlists)) },
                    "Playlists & TV guide",
                    "Refresh $refresh · What's new alerts ${if (s.notifyNew) "on" else "off"}" +
                        if (p != null && p.epgShiftMinutes != 0) " · Guide ${formatShift(p.epgShiftMinutes)}" else "",
                    hubAt(SettingsPage.PLAYLISTS.name),
                    selected = twoPane && (page == SettingsPage.PLAYLISTS || page?.parent == SettingsPage.PLAYLISTS),
                ) { openPage(SettingsPage.PLAYLISTS) }
            }
            item {
                val reminders = state.reminders.count { it.start > System.currentTimeMillis() }
                HubRow(
                    { HubIcon(rememberVectorPainter(Icons.Outlined.Notifications)) },
                    "Reminders & alerts",
                    listOf(
                        when (reminders) {
                            0 -> "No reminders"
                            1 -> "1 reminder"
                            else -> "$reminders reminders"
                        },
                        if (state.alertWords.isEmpty()) "No alert words" else "Alerts: " + state.alertWords.joinToString(", "),
                    ).joinToString(" · "),
                    hubAt(SettingsPage.REMINDERS.name),
                    selected = twoPane && (page == SettingsPage.REMINDERS || page?.parent == SettingsPage.REMINDERS),
                ) { openPage(SettingsPage.REMINDERS) }
            }
            item { HubGap() }
            item {
                HubRow(
                    { HubIcon(rememberVectorPainter(Icons.Outlined.Face)) },
                    "Profiles & parental control",
                    listOf(
                        if (s.profilesOn) "Profiles on" else "Profiles off",
                        if (pinSet) "PIN on" else "No PIN",
                        if (s.kidsMode) "Kids mode on" else "Kids mode off",
                    ).joinToString(" · "),
                    hubAt(SettingsPage.FAMILY.name),
                    selected = twoPane && (page == SettingsPage.FAMILY || page?.parent == SettingsPage.FAMILY),
                ) { openPage(SettingsPage.FAMILY) }
            }
            item {
                val linked = state.sync.key.isNotEmpty()
                HubRow(
                    { PhoneGlyph(Paper) },
                    "Devices & phone remote",
                    (if (linked && s.syncOn) "Sync on" else "Sync off") + " · " + when (state.remotes.size) {
                        0 -> "No phone paired"
                        1 -> "1 phone paired"
                        else -> "${state.remotes.size} phones paired"
                    },
                    hubAt(SettingsPage.DEVICES.name),
                    selected = twoPane && (page == SettingsPage.DEVICES || page?.parent == SettingsPage.DEVICES),
                ) { openPage(SettingsPage.DEVICES) }
            }
            item {
                HubRow(
                    { CrescentIcon(Paper, 18.dp) },
                    "Prayer times",
                    when (s.prayerMode) {
                        "remind" -> "On: a reminder at each prayer"
                        "pause" -> "On: a reminder, and the player pauses"
                        else -> "Off"
                    },
                    hubAt("PRAYER"),
                ) { go("PRAYER") { vm.screen = Screen.PRAYER } }
            }
            item { HubGap() }
            item {
                HubRow(
                    { HubIcon(rememberVectorPainter(Icons.Outlined.Info)) },
                    "About",
                    "The Player ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})" +
                        if (Updater.enabled) " · Check for updates" else "",
                    hubAt(SettingsPage.ABOUT.name),
                    selected = twoPane && page == SettingsPage.ABOUT,
                ) { openPage(SettingsPage.ABOUT) }
            }
    }

    // One page of Settings.
    val pageItems: LazyListScope.(SettingsPage) -> Unit = { shown ->
        when (shown) {
        SettingsPage.APPEARANCE -> {
        item {
            val context = androidx.compose.ui.platform.LocalContext.current
            // Today's sunrise and sunset where this device is (from its time zone).
            val sun = remember(context) {
                val place = app.falak.player.data.SunTimes.place(context)
                place to app.falak.player.data.SunTimes.day(place)
            }
            Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text("Theme", style = MaterialTheme.typography.bodyLarge, color = Paper)
                Text(
                    when (s.themeMode) {
                        "light" -> "Always light."
                        "dark" -> "Always dark."
                        else -> {
                            val (place, day) = sun
                            val rise = day.sunrise
                            val set = day.sunset
                            val where = if (place.guessed) "for your time zone (${place.city})" else "in ${place.city}"
                            if (rise != null && set != null) {
                                "Light from sunrise (${minuteLabel(context, rise)}) and dark from sunset (${minuteLabel(context, set)}) $where. " +
                                    "Follows the sun every day."
                            } else {
                                "Light by day and dark at night $where. Follows the sun every day."
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().widthIn(max = 440.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ThemeOption("Automatic", s.themeMode == "auto", listOf(LightPalette, DarkPalette), Modifier.weight(1f).then(focusAt("first"))) {
                        vm.updateSettings { it.copy(themeMode = "auto") }
                    }
                    ThemeOption("Light", s.themeMode == "light", listOf(LightPalette), Modifier.weight(1f)) {
                        vm.updateSettings { it.copy(themeMode = "light") }
                    }
                    ThemeOption("Dark", s.themeMode == "dark", listOf(DarkPalette), Modifier.weight(1f)) {
                        vm.updateSettings { it.copy(themeMode = "dark") }
                    }
                }
            }
        }

        item {
            Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 4.dp)) {
                Text("Look", style = MaterialTheme.typography.bodyLarge, color = Paper)
                Text(
                    if (s.look == "sidebar") "A side menu, and each section as a grid with filters."
                    else "Tabs along the top.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().widthIn(max = 440.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LookOption("Classic", s.look != "sidebar", sidebar = false, Modifier.weight(1f)) {
                        vm.updateSettings { it.copy(look = "classic") }
                    }
                    LookOption("Sidebar", s.look == "sidebar", sidebar = true, Modifier.weight(1f)) {
                        vm.updateSettings { it.copy(look = "sidebar") }
                    }
                }
            }
        }

        item {
            val context = androidx.compose.ui.platform.LocalContext.current
            val detected = remember(context) { detectTv(context) }
            Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 4.dp)) {
                Text("Screen", style = MaterialTheme.typography.bodyLarge, color = Paper)
                Text(
                    "TV: made for the remote, big screen and slideshow. Phone & tablet: made for touch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                Spacer(Modifier.height(8.dp))
                ChipFlow {
                    TagChip("Automatic (${if (detected) "TV" else "phone"})", s.layout == "auto") { vm.updateSettings { it.copy(layout = "auto") } }
                    TagChip("TV", s.layout == "tv") { vm.updateSettings { it.copy(layout = "tv") } }
                    TagChip("Phone & tablet", s.layout == "phone") { vm.updateSettings { it.copy(layout = "phone") } }
                }
            }
        }

        item {
            ClickRow(
                "Home rows",
                if (s.homeRows.isEmpty() && s.homeHidden.isEmpty()) "Choose which rows Home shows, and their order"
                else "${HomeRows.shown(s).size} of ${HomeRows.ALL.size} rows shown, in your order",
                focusAt("homeRows"),
            ) { homeRowsOpen = true }
        }
        if (tvLayout) {
            item {
                val allowed = remember(page, resumed) { app.falak.player.record.StartWithTv.allowed(appContext) }
                val askOverlay = {
                    val opened = app.falak.player.record.StartWithTv.askIntent(appContext).any { intent ->
                        runCatching { appContext.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                    }
                    if (!opened) {
                        android.widget.Toast.makeText(
                            appContext,
                            "Allow it in the TV's Settings → Apps → Special app access → Display over other apps",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                Column {
                    SwitchRow(
                        "Open when the TV starts",
                        when {
                            !s.startOnBoot -> "Off: The Player opens when you choose it"
                            !allowed -> "Needs “Display over other apps” allowed for The Player"
                            else -> "The Player opens by itself when the TV starts (and when it wakes, if it was left open)"
                        },
                        s.startOnBoot,
                        focusAt("boot"),
                    ) {
                        val on = !s.startOnBoot
                        vm.updateSettings { it.copy(startOnBoot = on) }
                        if (on && !allowed) askOverlay()
                    }
                    if (s.startOnBoot && !allowed) {
                        ClickRow("Allow “Display over other apps”", "Android lets an app open by itself only with this allowed") { askOverlay() }
                    }
                }
            }
        }
        item {
            SwitchRow(
                "Ambient screen",
                "A slideshow with the clock after 5 minutes idle, or 90 seconds with a video paused",
                s.ambient,
            ) { vm.updateSettings { it.copy(ambient = !it.ambient) } }
        }
        if (!tvLayout) {
            item {
                SwitchRow(
                    "Turn with the phone",
                    if (s.turnWithPhone) "Every page turns when you turn the phone, even with rotation locked in the phone's settings"
                    else "Pages follow the phone's own rotation setting (the player turns as set in it)",
                    s.turnWithPhone,
                ) { vm.updateSettings { it.copy(turnWithPhone = !it.turnWithPhone) } }
            }
        }
        }

        SettingsPage.PLAYBACK -> {
        item { SectionTitle("Player", first = true) }
        item { PlayerChoice(vm, s.player, focusAt("first")) }

        item { SectionTitle("Picture") }
        item {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text("Picture size", style = MaterialTheme.typography.bodyLarge, color = Paper)
                Text("How the video fills the screen. The picture button in the player changes it while you watch", style = MaterialTheme.typography.bodySmall, color = Muted)
                Spacer(Modifier.height(8.dp))
                ChipFlow {
                    listOf("Fit", "Fill", "Zoom").forEachIndexed { i, label ->
                        TagChip(label, s.aspect == i) { vm.updateSettings { it.copy(aspect = i) } }
                    }
                }
            }
        }

        item {
            val context = androidx.compose.ui.platform.LocalContext.current
            val view = androidx.compose.ui.platform.LocalView.current
            if (remember(context) { detectTv(context) }) {
                val rates = remember(view) { FrameRateMatch.offeredRates(view.display) }
                Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                    Text("Match frame rate", style = MaterialTheme.typography.bodyLarge, color = Paper)
                    Text(
                        "Switches the TV to the video's own rate (24, 25, 50 or 60 Hz) so motion is smooth, with no judder. " +
                            "The screen goes dark for a moment when it switches. " +
                            if (rates.size > 1) {
                                "This TV offers ${rates.joinToString(", ") { FrameRateMatch.label(it) }} Hz." +
                                    if (android.os.Build.VERSION.SDK_INT >= 31) " If it never switches, set the box's “Match content frame rate” to Always." else ""
                            } else {
                                "This screen runs at one rate only" + (rates.firstOrNull()?.let { " (${FrameRateMatch.label(it)} Hz)" } ?: "") + ", so there's nothing to switch."
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                    // One rate only: nothing to choose.
                    if (rates.size > 1) {
                        Spacer(Modifier.height(8.dp))
                        ChipFlow {
                            listOf("off" to "Off", "vod" to "Films & series", "all" to "Always").forEach { (key, label) ->
                                TagChip(label, s.frameRate == key) { vm.updateSettings { it.copy(frameRate = key) } }
                            }
                        }
                    }
                }
            }
        }

        item { SectionTitle("Playback") }
        item {
            SwitchRow(
                "Play the next episode automatically",
                "Series: the next episode starts 10 seconds into the credits (OK plays it now, Back keeps watching)",
                s.autoNextEpisode,
            ) { vm.updateSettings { it.copy(autoNextEpisode = !it.autoNextEpisode) } }
        }
        item {
            SwitchRow("Larger buffer", "Fewer pauses on slow connections; channels start a little slower", s.bigBuffer) {
                vm.updateSettings { it.copy(bigBuffer = !it.bigBuffer) }
            }
        }
        item {
            SwitchRow("Prefer HLS for Xtream live TV", "Try this if channels freeze or keep stopping", s.preferHls) {
                vm.updateSettings { it.copy(preferHls = !it.preferHls) }
            }
        }
        item {
            SwitchRow(
                "Mini player",
                "Back from a live channel keeps it playing small in a corner while you look around. " +
                    (if (tvLayout) "OK on it goes back to full screen, Back closes it." else "Tap it for full screen; × closes it."),
                s.miniPlayer,
            ) { vm.updateSettings { it.copy(miniPlayer = !it.miniPlayer) } }
        }
        if (tvLayout) {
            item {
                SwitchRow(
                    "Live preview",
                    "In Live TV, the channel the remote rests on plays small beside the list",
                    s.livePreview,
                ) { vm.updateSettings { it.copy(livePreview = !it.livePreview) } }
            }
        }
        // Phones and tablets: sound in the background (a TV keeps the picture on screen anyway).
        item {
            val context = androidx.compose.ui.platform.LocalContext.current
            if (!remember(context) { detectTv(context) }) {
                SwitchRow(
                    "Listen mode",
                    "The sound carries on when the screen turns off or you leave the app (radio, Quran, news, a match), " +
                        "with play and pause on the lock screen. Coming back brings the picture back.",
                    s.listenMode,
                ) { vm.updateSettings { it.copy(listenMode = !it.listenMode) } }
            }
        }
        }

        SettingsPage.RECORDINGS -> {
        item { SectionTitle("Pause & rewind live TV", first = true) }
        item {
            SwitchRow(
                "Pause & rewind live TV",
                if (s.liveBuffer == "on") {
                    "Live channels play from a buffer on this device: pause, rewind up to ${s.liveBufferMinutes} minutes and catch up again. " +
                        "It uses storage while you watch and is cleared when you change channel."
                } else {
                    "Off: live channels play straight from your provider (pausing may jump back to live)."
                },
                s.liveBuffer == "on",
                focusAt("first"),
            ) { vm.updateSettings { it.copy(liveBuffer = if (it.liveBuffer == "on") "off" else "on") } }
        }
        if (s.liveBuffer == "on") {
            item {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                    Text("How far back", style = MaterialTheme.typography.bodyLarge, color = Paper)
                    Text("Since you tuned in, at most. An hour of HD takes about 2 GB.", style = MaterialTheme.typography.bodySmall, color = Muted)
                    Spacer(Modifier.height(8.dp))
                    ChipFlow {
                        listOf(15, 30, 60, 120).forEach { m ->
                            TagChip(if (m < 60) "$m min" else "${m / 60} h", s.liveBufferMinutes == m) { vm.updateSettings { it.copy(liveBufferMinutes = m) } }
                        }
                    }
                }
            }
        }
        item { SectionTitle("Recordings") }
        item {
            val recs = app.falak.player.record.RecordCenter.book.collectAsState().value.recordings
            ClickRow(
                "Recordings",
                recs.size.let { n -> if (n == 0) "Record from the player (Record), a channel's long-press or the TV guide" else "What's recording, planned and saved" },
                focusAt("recordings"),
                arrow = true,
            ) { go("recordings") { vm.screen = Screen.RECORDINGS } }
        }
        item {
            val places = remember(page, resumed) { app.falak.player.record.RecordCenter.places() }
            Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text("Save recordings to", style = MaterialTheme.typography.bodyLarge, color = Paper)
                Text(
                    "A USB drive or memory card shows here while it's plugged in. Recordings stay until you delete them (removing the app removes them too).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                Spacer(Modifier.height(8.dp))
                ChipFlow {
                    val chosen = places.firstOrNull { it.path == s.recordDir }?.path ?: places.firstOrNull()?.path
                    places.forEach { pl ->
                        TagChip("${pl.label} · ${app.falak.player.OfflineCenter.sizeLabel(pl.free)} free", pl.path == chosen) {
                            app.falak.player.record.RecordCenter.setPlace(if (pl == places.first()) "" else pl.path)
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text("Start early, end late", style = MaterialTheme.typography.bodyLarge, color = Paper)
                Text("Programmes recorded from the guide start a little before and end a little after, as shows rarely run to the minute.", style = MaterialTheme.typography.bodySmall, color = Muted)
                Spacer(Modifier.height(8.dp))
                Kicker("Early", color = Muted)
                Spacer(Modifier.height(4.dp))
                ChipFlow {
                    listOf(0, 1, 2, 5, 10).forEach { m -> TagChip("$m min", s.recordEarly == m) { vm.updateSettings { it.copy(recordEarly = m) } } }
                }
                Spacer(Modifier.height(8.dp))
                Kicker("Late", color = Muted)
                Spacer(Modifier.height(4.dp))
                ChipFlow {
                    listOf(0, 2, 5, 10, 15, 30).forEach { m -> TagChip("$m min", s.recordLate == m) { vm.updateSettings { it.copy(recordLate = m) } } }
                }
            }
        }
        item {
            val onTime = remember(page, resumed) { app.falak.player.record.RecordCenter.canWakeOnTime() }
            if (!onTime) {
                ClickRow(
                    "Allow alarms & reminders",
                    "So planned recordings start on time while the app is closed",
                    focusAt("alarms"),
                ) { askForAlarms(appContext) }
            }
        }
        }

        SettingsPage.SOUND -> {
        item { SectionTitle("Sound", first = true) }
        item {
            SwitchRow(
                "Even out volume between channels",
                "Turns quiet channels up and loud ones down to a similar level (not for Dolby sent to a receiver)",
                s.levelVolume,
                focusAt("first"),
            ) { vm.updateSettings { it.copy(levelVolume = !it.levelVolume) } }
        }
        item {
            val context = androidx.compose.ui.platform.LocalContext.current
            val place = remember { app.falak.player.data.SunTimes.place(context) }
            val day = remember(place) { app.falak.player.data.SunTimes.day(place) }
            val fmt = rememberTimeFormat()
            fun at(min: Int?) = min?.let {
                val c = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, it / 60); set(java.util.Calendar.MINUTE, it % 60) }
                fmt.format(c.time)
            }
            Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text("Night sound", style = MaterialTheme.typography.bodyLarge, color = Paper)
                Text(
                    "Clearer voices and softer bangs, for late viewing. " + when (s.nightSound) {
                        "on" -> "On all the time."
                        "off" -> "Off."
                        else -> {
                            val from = at(day.sunset)
                            val to = at(day.sunrise)
                            if (from != null && to != null) "Automatic: from sunset ($from) to sunrise ($to) in ${place.city}."
                            else "Automatic: after dark in ${place.city}."
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                ChipFlow {
                    listOf("auto" to "Automatic", "on" to "Always", "off" to "Off").forEach { (key, label) ->
                        TagChip(label, s.nightSound == key) { vm.updateSettings { it.copy(nightSound = key) } }
                    }
                }
            }
        }
        item {
            LanguageRow("Preferred audio language", "When a channel or video has several audio tracks", s.audioLang) { code ->
                vm.updateSettings { it.copy(audioLang = code) }
            }
        }
        item {
            SwitchRow(
                "Software audio decoding",
                "Turn on if some channels or movies play without sound (Dolby/DTS on TV boxes)",
                s.softwareAudio,
            ) { vm.updateSettings { it.copy(softwareAudio = !it.softwareAudio) } }
        }

        item { SectionTitle("Subtitles") }
        item {
            SwitchRow(
                "Show subtitles automatically",
                if (s.subtitlesOn) "On: whenever a film, episode or channel has subtitles or closed captions"
                else "Off: subtitles are still there, turn them on with the Subtitles button in the player",
                s.subtitlesOn,
            ) {
                vm.updateSettings { it.copy(subtitlesOn = !it.subtitlesOn) }
            }
        }
        if (s.subtitlesOn) {
            item {
                LanguageRow("Subtitle language", null, s.subtitleLang) { code ->
                    vm.updateSettings { it.copy(subtitleLang = code) }
                }
            }
        }
        item {
            ClickRow(
                "Subtitle style",
                listOf(
                    SubtitleStyle.SIZES[s.subSize.coerceIn(0, 3)],
                    if (s.subColor == "yellow") "Yellow" else "White",
                    if (s.subEdge == "outline") "Outline" else "Dark box",
                ).joinToString(" · "),
                focusAt(SettingsPage.SUBTITLE_STYLE.name),
                arrow = true,
            ) { openPage(SettingsPage.SUBTITLE_STYLE) }
        }
        item {
            ClickRow(
                title = "Online subtitles (OpenSubtitles.com)",
                subtitle = if (s.osApiKey.isBlank()) "Find subtitles for movies and episodes that have none · tap to set up"
                else "On" + if (s.osUser.isNotBlank()) " · signed in as ${s.osUser}" else " · not signed in (fewer downloads per day)",
            ) { editSubs = true }
        }
        // Auto captions: the speech model's state; turned on per channel from the player's Subtitles button.
        item {
            val context = androidx.compose.ui.platform.LocalContext.current
            LaunchedEffect(Unit) { app.falak.player.player.SpeechModel.refresh(context) }
            val speech by app.falak.player.player.SpeechModel.state.collectAsState()
            val model = app.falak.player.player.SpeechModel
            ClickRow(
                "Auto captions (English · beta)",
                when (val st = speech) {
                    is app.falak.player.player.SpeechModel.State.Ready ->
                        "Ready on this device (${model.SIZE_LABEL}). Turn them on for a live channel with its Subtitles button; " +
                            "channels with captions of their own keep those. Select to remove the speech model. ${model.CREDIT}"
                    is app.falak.player.player.SpeechModel.State.Downloading ->
                        "Downloading the speech model… ${(st.fraction * 100).toInt()}%"
                    is app.falak.player.player.SpeechModel.State.Failed ->
                        "Couldn't download the speech model: ${st.message}. Select to try again."
                    else ->
                        "Captions for English channels that have none, made on this device: nothing is sent anywhere. " +
                            "Turn them on with the Subtitles button on a live channel. Select to download the speech model now (${model.SIZE_LABEL})."
                },
            ) {
                when (speech) {
                    is app.falak.player.player.SpeechModel.State.Ready -> removeSpeech = true
                    is app.falak.player.player.SpeechModel.State.Downloading -> Unit
                    else -> model.download(context, (context.applicationContext as app.falak.player.FalakApp).repo.http)
                }
            }
        }
        }

        SettingsPage.REMINDERS -> {
            remindersPage(vm, state, focusAt("first"), notificationsOff) { addAlertWord = true }
        }

        SettingsPage.PLAYLISTS -> {
        item { SectionTitle("When the app opens", first = true) }
        item {
            SwitchRow("Open last playlist on start", null, s.openLast, focusAt("first")) {
                vm.updateSettings { it.copy(openLast = !it.openLast) }
            }
        }
        item {
            SwitchRow(
                "Start with the last channel",
                "When the app was fully closed, it opens playing the channel you watched last",
                s.startLastChannel,
            ) {
                vm.updateSettings { it.copy(startLastChannel = !it.startLastChannel) }
            }
        }

        item { SectionTitle("Tidy channel list") }
        item {
            SwitchRow(
                "Clean channel names",
                "“AR: MBC 1 FHD ᴴᴰ” shows as “MBC 1” with a small FHD mark. The guide, favorites and search work as before.",
                s.cleanNames,
            ) { vm.updateSettings { it.copy(cleanNames = !it.cleanNames) } }
        }
        item {
            SwitchRow(
                "Merge duplicate channels",
                "A channel a group lists in several qualities shows once. The best one plays, and a lighter one " +
                    "takes over by itself if it keeps stopping to load. Versions in other languages stay apart.",
                s.mergeDuplicates,
            ) { vm.updateSettings { it.copy(mergeDuplicates = !it.mergeDuplicates) } }
        }

        item { SectionTitle("Your playlists") }
        if (p != null) {
            item {
                ClickRow("Show & hide groups", "Hide groups you never watch, or bring them back", focusAt("groups"), arrow = true) {
                    go("groups") { vm.screen = Screen.GROUPS }
                }
            }
            // Subscription watch (Xtream logins): status, end date and connections, read from the provider.
            if (vm.accountReadable || vm.account != null) {
                item {
                    LaunchedEffect(p.id) { vm.checkAccount(force = true) }
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val format = remember(context) { android.text.format.DateFormat.getMediumDateFormat(context) }
                    val a = vm.account
                    val now = System.currentTimeMillis()
                    val line = when {
                        a != null -> {
                            val ago = (now - a.checkedAt) / 60_000L
                            app.falak.player.data.Accounts.summary(a, now, { format.format(java.util.Date(it)) }) + when {
                                a.checkedAt <= 0 || ago < 30 -> ""
                                ago < 120 -> " · checked $ago min ago"
                                else -> " · checked ${ago / 60} h ago"
                            }
                        }
                        vm.accountFailed -> "Couldn't reach your provider to check. Select to try again."
                        else -> "Checking with your provider…"
                    }
                    ClickRow("Subscription", line) { vm.checkAccount(force = true) }
                }
            }
            // Provider report card: how the service has done on this device this week.
            item {
                val context = androidx.compose.ui.platform.LocalContext.current
                // A small file (two weeks of hourly figures), read when the page opens.
                val report = remember(p.id, serviceReport) {
                    runCatching {
                        app.falak.player.data.ServiceReport.summary(
                            app.falak.player.data.ServiceLogStore.read(context.filesDir, p.id),
                            System.currentTimeMillis(),
                        )
                    }.getOrNull()
                }
                ClickRow(
                    "Service report",
                    report?.let { app.falak.player.data.ServiceReport.line(it) } ?: "How your provider has done this week",
                    focusAt("service"),
                ) { serviceReport = true }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text("Refresh playlist automatically", style = MaterialTheme.typography.bodyLarge, color = Paper)
                Text(
                    "A fresh copy downloads in the background after this long" +
                        if (p != null && vm.loadedAt > 0) " · updated ${ageText(vm.loadedAt)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                ChipFlow {
                    listOf(1 to "1 h", 3 to "3 h", 6 to "6 h", 12 to "12 h", 24 to "Daily", 0 to "Never").forEach { (h, label) ->
                        TagChip(label, s.autoRefreshHours == h) { vm.updateSettings { it.copy(autoRefreshHours = h) } }
                    }
                }
            }
        }
        item {
            val context = androidx.compose.ui.platform.LocalContext.current
            val ask = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
            ) { }
            SwitchRow(
                "Tell me what's new",
                "A notification when a refresh finds new channels, films or series (What's new always lists them)",
                s.notifyNew,
            ) {
                val on = !s.notifyNew
                vm.updateSettings { it.copy(notifyNew = on, askedNotify = true) }
                if (on && android.os.Build.VERSION.SDK_INT >= 33 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        item { SectionTitle("TV guide") }
        if (p == null) {
            item { Note("Open a playlist to choose where its TV guide comes from.") }
        } else {
            item {
                val extra = p.extraEpg.distinct().size
                ClickRow(
                    "Guide sources",
                    when {
                        vm.guideLoading -> "Loading the guide…"
                        vm.guideStatus.isNotEmpty() -> vm.guideStatus
                        else -> "Where the TV guide comes from"
                    } + when (extra) {
                        0 -> ""
                        1 -> " · 1 extra source"
                        else -> " · $extra extra sources"
                    },
                ) { guideSources = true }
            }
            item {
                ClickRow(
                    "Guide time correction",
                    if (p.epgShiftMinutes == 0) "Off · for guides that show programmes too early or too late"
                    else "${formatShift(p.epgShiftMinutes)} · tap to change",
                ) { guideShift = true }
            }
        }
        }

        SettingsPage.FAMILY -> {
        item {
            ClickRow(
                "Profiles",
                if (!s.profilesOn) "Off. Give each person their own favorites and Continue watching"
                else "On · " + (listOf(s.mainProfileName.ifBlank { "Me" }) + state.profiles.map { it.name }).joinToString(", "),
                focusAt("profiles"),
                arrow = true,
            ) { guarded { go("profiles") { vm.screen = Screen.PROFILES } } }
        }

        item { SectionTitle("Parental control") }
        item {
            ClickRow(
                title = if (pinSet) "Change or remove PIN" else "Set a PIN",
                subtitle = if (pinSet) "PIN is on" else "Lock adult or chosen groups behind a PIN",
            ) { guarded { editPin = true } }
        }
        if (pinSet && p != null) {
            // One row: the adult lock and the groups are on their own page.
            item {
                val parts = listOfNotNull(
                    if (s.lockAdult) "Adult groups" else null,
                    when (lockedHere.size) {
                        0 -> null
                        1 -> "1 group"
                        else -> "${lockedHere.size} groups"
                    },
                )
                ClickRow(
                    "Locked groups",
                    if (parts.isEmpty()) "Nothing locked yet" else parts.joinToString(" and ") + " need the PIN",
                    focusAt("locks"),
                    arrow = true,
                ) { guarded { go("locks") { vm.screen = Screen.LOCKS } } }
            }
        }

        item { SectionTitle("Kids profile") }
        if (p == null) {
            item { Note("Open a playlist to set up kids mode.") }
        } else {
            val kidsHere = s.kidsGroups[p.id].orEmpty()
            item {
                SwitchRow(
                    "Kids mode",
                    if (s.kidsMode) {
                        "On: the app asks who's watching when it opens. The Kids profile shows only the groups you choose"
                    } else {
                        "Adds a Kids profile you can pick when the app opens"
                    },
                    s.kidsMode,
                ) {
                    if (s.kidsMode) {
                        guarded { vm.setKidsMode(false) }
                    } else {
                        // First time: start from the groups that look made for children.
                        if (kidsHere.isEmpty()) vm.setKidsGroups(vm.kidsSuggestions(), allowed = true)
                        if (!pinSet) {
                            kidsAfterPin = true
                            editPin = true
                        } else {
                            guarded { vm.setKidsMode(true) }
                        }
                    }
                }
            }
            // Only while it's on: which groups the Kids profile shows (on its own page).
            if (s.kidsMode) {
                item {
                    ClickRow(
                        "Groups for kids",
                        when (kidsHere.size) {
                            0 -> "None chosen yet: the Kids profile shows nothing"
                            1 -> "1 group in ${p.name}"
                            else -> "${kidsHere.size} groups in ${p.name}"
                        },
                        focusAt("kids"),
                        arrow = true,
                    ) { guarded { go("kids") { vm.screen = Screen.KIDS } } }
                }
                // Screen time: a daily limit and a bedtime (changing them needs the PIN).
                item {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val used = remember(page) { (app.falak.player.data.KidsClock.usedMs(context) / 60_000L).toInt() }
                    Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                        Text("Watching time a day", style = MaterialTheme.typography.bodyLarge, color = Paper)
                        Text(
                            (if (s.kidsDailyMinutes > 0) "When it's used up, what's on finishes if it ends soon, then playing stops until tomorrow. " else "") +
                                "Today: ${app.falak.player.data.KidsTime.duration(used)} watched in the Kids profile",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        ChipFlow {
                            listOf(0 to "No limit", 30 to "30 min", 60 to "1 h", 90 to "1 h 30", 120 to "2 h", 180 to "3 h").forEach { (m, label) ->
                                TagChip(label, s.kidsDailyMinutes == m) { guarded { vm.updateSettings { it.copy(kidsDailyMinutes = m) } } }
                            }
                        }
                    }
                }
                item {
                    val fmt = rememberTimeFormat()
                    fun at(minute: Int) = app.falak.player.data.PrayerTimes.label(minute, fmt)
                    Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                        Text("Bedtime", style = MaterialTheme.typography.bodyLarge, color = Paper)
                        Text(
                            if (s.kidsBedtime < 0) "The Kids profile can watch at any time of day"
                            else "Nothing plays in the Kids profile from ${at(s.kidsBedtime)} until ${at(s.kidsWakeUp)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        ChipFlow {
                            TagChip("None", s.kidsBedtime < 0) { guarded { vm.updateSettings { it.copy(kidsBedtime = -1) } } }
                            listOf(19, 20, 21, 22).forEach { h ->
                                TagChip(at(h * 60), s.kidsBedtime == h * 60) { guarded { vm.updateSettings { it.copy(kidsBedtime = h * 60) } } }
                            }
                        }
                        if (s.kidsBedtime >= 0) {
                            Spacer(Modifier.height(10.dp))
                            Text("Until", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            ChipFlow {
                                listOf(6, 7, 8).forEach { h ->
                                    TagChip(at(h * 60), s.kidsWakeUp == h * 60) { guarded { vm.updateSettings { it.copy(kidsWakeUp = h * 60) } } }
                                }
                            }
                        }
                    }
                }
            }
        }
        }

        SettingsPage.DEVICES -> {
        item {
            ClickRow(
                "Your devices",
                "Copy your playlists to other TVs and phones, or manage them from a phone or computer",
                focusAt("devices"),
                arrow = true,
            ) { go("devices") { vm.screen = Screen.SYNC } }
        }
        item {
            val linked = state.sync.key.isNotEmpty()
            val names = state.sync.peers.values.joinToString(", ") { it.name }
            SwitchRow(
                "Sync with my devices",
                when {
                    !linked -> "Off. Link a TV or phone on the same Wi-Fi in Your devices"
                    !s.syncOn -> "Off. Linked with ${names.ifBlank { "another device" }}"
                    else -> "Playlists, favorites, Continue watching, profiles and settings stay the same on " +
                        names.ifBlank { "your linked devices" }
                },
                s.syncOn && linked,
                focusAt("sync"),
            ) {
                if (!linked) go("sync") { vm.screen = Screen.SYNC } else vm.updateSettings { it.copy(syncOn = !it.syncOn) }
            }
        }
        item {
            ClickRow(
                "Phone remote",
                when (state.remotes.size) {
                    0 -> "Use your phone as a remote and keyboard for this device"
                    1 -> "1 phone paired"
                    else -> "${state.remotes.size} phones paired"
                },
                focusAt("remote"),
                arrow = true,
            ) { go("remote") { vm.screen = Screen.REMOTE } }
        }
        // Stremio-style add-ons: GitHub version only, not in the Kids profile.
        if (app.falak.player.data.Addons.enabled && !vm.kidsActive) {
            item {
                ClickRow(
                    "Add-ons",
                    when (s.addons.size) {
                        0 -> "Films and series from Stremio-style add-ons you add"
                        1 -> "1 add-on"
                        else -> "${s.addons.size} add-ons"
                    },
                    focusAt("addons"),
                    arrow = true,
                ) {
                    go("addons") {
                        vm.addons.fromSettings = true
                        vm.screen = Screen.ADDONS
                    }
                }
            }
        }
        if (tvLayout) {
            item {
                ClickRow(
                    "Remote buttons",
                    if (s.keyActions.isEmpty()) "Give your remote's buttons (the colour ones, say) a job while watching"
                    else s.keyActions.entries.joinToString(" · ") { (k, v) -> "${KeyActions.keyName(k.toIntOrNull() ?: 0)}: ${KeyActions.label(v)}" },
                    focusAt("keys"),
                ) { remoteButtonsOpen = true }
            }
        }
        }

        SettingsPage.SUBTITLE_STYLE -> {
        item { SubtitlePreview(s.subSize, s.subColor, s.subEdge) }
        item {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text("Size", style = MaterialTheme.typography.bodyLarge, color = Paper)
                Spacer(Modifier.height(8.dp))
                ChipFlow {
                    SubtitleStyle.SIZES.forEachIndexed { i, label ->
                        TagChip(label, s.subSize == i, modifier = if (s.subSize == i) focusAt("first") else Modifier) {
                            vm.updateSettings { it.copy(subSize = i) }
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text("Text color", style = MaterialTheme.typography.bodyLarge, color = Paper)
                Spacer(Modifier.height(8.dp))
                ChipFlow {
                    listOf("white" to "White", "yellow" to "Yellow").forEach { (key, label) ->
                        TagChip(label, s.subColor == key) { vm.updateSettings { it.copy(subColor = key) } }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text("Background", style = MaterialTheme.typography.bodyLarge, color = Paper)
                Text("A dark box reads best on bright scenes; an outline hides less of the picture", style = MaterialTheme.typography.bodySmall, color = Muted)
                Spacer(Modifier.height(8.dp))
                ChipFlow {
                    listOf("box" to "Dark box", "outline" to "Outline").forEach { (key, label) ->
                        TagChip(label, s.subEdge == key) { vm.updateSettings { it.copy(subEdge = key) } }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text("Auto captions", style = MaterialTheme.typography.titleMedium, color = Paper)
                Text(
                    "Where captions made on this device sit, and how many lines show at once. They take the same space as a channel's own captions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }
        item {
            val position = CaptionLayout.position(s.autoCapsPosition)
            val lines = CaptionLayout.lines(s.autoCapsLines)
            SubtitlePreview(
                s.subSize,
                s.subColor,
                s.subEdge,
                lines = CaptionText.rollUp(CaptionLayout.SAMPLE, CaptionLayout.MAX_LINE_CHARS, lines).orEmpty().split('\n'),
                position = position,
            )
        }
        item {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text("Position", style = MaterialTheme.typography.bodyLarge, color = Paper)
                Text("Raised keeps them clear of news tickers along the bottom", style = MaterialTheme.typography.bodySmall, color = Muted)
                Spacer(Modifier.height(8.dp))
                ChipFlow {
                    val position = CaptionLayout.position(s.autoCapsPosition)
                    CaptionLayout.POSITIONS.forEach { (key, label) ->
                        TagChip(label, position == key) { vm.updateSettings { it.copy(autoCapsPosition = key) } }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text("Lines", style = MaterialTheme.typography.bodyLarge, color = Paper)
                Spacer(Modifier.height(8.dp))
                ChipFlow {
                    val lines = CaptionLayout.lines(s.autoCapsLines)
                    CaptionLayout.LINES.forEach { n ->
                        TagChip("$n", lines == n) { vm.updateSettings { it.copy(autoCapsLines = n) } }
                    }
                }
            }
        }
        item {
            SwitchRow(
                "In time with the speech",
                "Each word shows as it's said, instead of about a second later: the captions hear the sound " +
                    "2 seconds before you do. From the next channel you open.",
                s.syncedCaptions,
            ) { vm.updateSettings { it.copy(syncedCaptions = !it.syncedCaptions) } }
        }
        item {
            Note(
                "Used by both video players from the next video you open. While watching, Subtitles → Caption style " +
                    "changes it on the picture as you pick.",
            )
        }
        }

        SettingsPage.ABOUT -> {
        item {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text("Made by Ahmed", fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = Paper)
                Text("The Player ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", fontSize = 13.sp, color = Muted)
                Text(
                    "Uses ExoPlayer (Apache 2.0), LibVLC and FFmpeg (LGPL). Auto captions use sherpa-onnx (Apache 2.0) " +
                        "and, once downloaded, the Kroko-ASR speech model by Banafo (CC BY-SA 4.0).",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = Faint,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "The Player doesn't include any channels or content: it plays the playlists you add. " +
                        "Your playlists and settings stay on your devices.",
                    fontSize = 13.sp, lineHeight = 19.sp,
                    color = Muted,
                )
            }
        }
        if (Updater.enabled) {
            item {
                ClickRow(
                    title = if (vm.updateChecking) "Checking…" else "Check for updates",
                    subtitle = "Looks for a newer version of The Player",
                    modifier = focusAt("first"),
                ) { vm.checkForUpdates(manual = true) }
            }
        }
        item {
            val uri = androidx.compose.ui.platform.LocalUriHandler.current
            ClickRow("Privacy policy", "What the app keeps, and what it never sends", focusAt("privacy")) {
                runCatching { uri.openUri(PRIVACY_URL) }
            }
        }
        }
        }
    }

    val shell = LocalShell.current
    if (twoPane) {
        // TV: the list on the left, the page on the right.
        Column(Modifier.fillMaxSize().padding(horizontal = if (shell == ShellMode.RAIL) 28.dp else pageMargin())) {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Settings",
                    fontFamily = Serif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 24.sp,
                    color = Paper,
                    modifier = Modifier.weight(1f),
                )
                Clock()
            }
            Row(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.width(330.dp).fillMaxHeight(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) { hubItems() }
                Spacer(Modifier.width(18.dp))
                Box(Modifier.width(1.dp).fillMaxHeight().background(Hairline))
                Spacer(Modifier.width(26.dp))
                Box(Modifier.weight(1f).fillMaxHeight().onFocusChanged { panelFocused = it.hasFocus }) {
                    key(page) {
                        val shown = page ?: SettingsPage.APPEARANCE
                        LazyColumn(
                            Modifier.fillMaxHeight().widthIn(max = 760.dp).fillMaxWidth(),
                            contentPadding = PaddingValues(bottom = 32.dp),
                        ) {
                            item {
                                if (shown.parent != null) {
                                    Box(Modifier.padding(horizontal = 4.dp)) { PageHeader(shown.title, onBack = back) }
                                } else {
                                    Text(
                                        shown.title,
                                        fontFamily = Serif,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 20.sp,
                                        color = Paper,
                                        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
                                    )
                                }
                            }
                            pageItems(shown)
                        }
                    }
                }
            }
        }
    } else {
        key(page) {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                item { Box(Modifier.padding(horizontal = 4.dp)) { PageHeader(page?.title ?: "Settings", onBack = back) } }
                if (page == null) hubItems() else pageItems(page)
            }
        }
    }

    pendingAction?.let { action ->
        PinDialog(
            title = "Enter PIN",
            onDismiss = { pendingAction = null },
            onSubmit = { pin ->
                val ok = vm.tryUnlock(pin)
                if (ok) {
                    pendingAction = null
                    action()
                }
                ok
            },
        )
    }

    if (addAlertWord) {
        AlertWordDialog(onDismiss = { addAlertWord = false }) { w -> if (!vm.hasAlertWord(w)) vm.toggleAlertWord(w) }
    }
    if (serviceReport && p != null) {
        ServiceReportDialog(p) { serviceReport = false }
    }
    if (removeSpeech) {
        val context = androidx.compose.ui.platform.LocalContext.current
        AlertDialog(
            onDismissRequest = { removeSpeech = false },
            title = { Text("Remove the speech model?") },
            text = { Text("Auto captions stop until it's downloaded again (${app.falak.player.player.SpeechModel.SIZE_LABEL}).") },
            confirmButton = {
                TextButton(onClick = {
                    removeSpeech = false
                    app.falak.player.player.SpeechModel.remove(context)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removeSpeech = false }) { Text("Cancel") } },
        )
    }
    if (editSubs) {
        var key by remember { mutableStateOf(s.osApiKey) }
        var user by remember { mutableStateOf(s.osUser) }
        var pass by remember { mutableStateOf(s.osPass) }
        AlertDialog(
            onDismissRequest = { editSubs = false },
            title = { Text("Online subtitles") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Create a free account at opensubtitles.com, then open your profile → API consumers → " +
                            "create a consumer to get an API key. The Player searches it when a movie or episode has no subtitles " +
                            "(player → CC → Find subtitles online).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(key, { key = it.trim() }, label = { Text("API key") }, singleLine = true)
                    OutlinedTextField(user, { user = it.trim() }, label = { Text("Username (optional)") }, singleLine = true)
                    OutlinedTextField(
                        pass, { pass = it }, label = { Text("Password (optional)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateSettings { it.copy(osApiKey = key, osUser = user, osPass = pass) }
                    editSubs = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editSubs = false }) { Text("Cancel") } },
        )
    }

    if (guideSources && p != null) {
        GuideSourcesDialog(vm, p, s.guideOverrides[p.id].orEmpty().size) { guideSources = false }
    }

    if (guideShift && p != null) {
        AlertDialog(
            onDismissRequest = { guideShift = false },
            title = { Text("Guide time correction") },
            text = {
                Column {
                    Text(
                        "If every programme in the guide starts too early or too late, move the whole guide.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { vm.shiftGuide(-30) }) { Text("− 30 min") }
                        Text(
                            formatShift(p.epgShiftMinutes),
                            style = MaterialTheme.typography.titleMedium,
                            color = Paper,
                            modifier = Modifier.widthIn(min = 72.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        TextButton(onClick = { vm.shiftGuide(30) }) { Text("+ 30 min") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { guideShift = false }) { Text("Done") } },
            dismissButton = if (p.epgShiftMinutes != 0) {
                { TextButton(onClick = { vm.shiftGuide(-p.epgShiftMinutes) }) { Text("Reset") } }
            } else {
                null
            },
        )
    }

    if (homeRowsOpen) HomeRowsDialog(vm, state) { homeRowsOpen = false }
    if (remoteButtonsOpen) RemoteButtonsDialog(vm, state) { remoteButtonsOpen = false }

    if (editPin) {
        EditPinDialog(
            hasPin = pinSet,
            onDismiss = { editPin = false; kidsAfterPin = false },
            onSave = { newPin ->
                vm.updateSettings { it.copy(pin = newPin) }
                vm.unlocked = newPin.isNotEmpty()
                editPin = false
                if (newPin.isEmpty()) {
                    // No PIN, no lock: kids mode can't stay on without one.
                    vm.setKidsMode(false)
                } else if (kidsAfterPin) {
                    vm.setKidsMode(true)
                }
                kidsAfterPin = false
            },
        )
    }
}

@Composable
internal fun SectionTitle(text: String, first: Boolean = false) {
    Box(Modifier.padding(start = 4.dp, top = if (first) 10.dp else 26.dp, bottom = 6.dp)) { Kicker(text) }
}

/** A short line in place of settings that need a playlist open. */
@Composable
internal fun Note(text: String) {
    Text(text, fontSize = 13.sp, lineHeight = 19.sp, color = Muted, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
}

/**
 * A still "video" with subtitles in the chosen look, sized as the player would: [lines] of them,
 * at [position] (auto captions' places; the stream's own subtitles are at the bottom).
 */
@Composable
private fun SubtitlePreview(
    step: Int,
    color: String,
    edge: String,
    lines: List<String> = listOf(CaptionLayout.STREAM_SAMPLE),
    position: String = "bottom",
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(
                        androidx.compose.ui.graphics.Color(0xFF1D3B5C),
                        androidx.compose.ui.graphics.Color(0xFF7C5A6E),
                        androidx.compose.ui.graphics.Color(0xFFE2A66B),
                    ),
                ),
            ),
    ) {
        // A low sun and a horizon, so the text is seen against light and dark.
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawCircle(androidx.compose.ui.graphics.Color(0xFFFFE3B0), radius = size.minDimension * 0.16f, center = androidx.compose.ui.geometry.Offset(size.width * 0.68f, size.height * 0.62f))
            drawRect(
                androidx.compose.ui.graphics.Color(0xFF241A22),
                topLeft = androidx.compose.ui.geometry.Offset(0f, size.height * 0.7f),
                size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.3f),
            )
        }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            // The players draw subtitles at about 1/19 of the picture height.
            val textSize = (maxHeight.value * 0.0533f * SubtitleStyle.scale(step)).coerceAtLeast(9f).sp
            val fg = androidx.compose.ui.graphics.Color(SubtitleStyle.textColor(color))
            val base = androidx.compose.ui.text.TextStyle(fontSize = textSize, lineHeight = textSize * 1.25f, fontWeight = FontWeight.Medium)
            val place = when (CaptionLayout.position(position)) {
                "top" -> Modifier.align(Alignment.TopCenter).padding(top = maxHeight * CaptionLayout.TOP_LINE)
                "raised" -> Modifier.align(Alignment.BottomCenter).padding(bottom = maxHeight * (1f - CaptionLayout.RAISED_LINE))
                else -> Modifier.align(Alignment.BottomCenter).padding(bottom = maxHeight * 0.08f)
            }
            Column(place, horizontalAlignment = Alignment.CenterHorizontally) {
                for (line in lines) {
                    Box {
                        if (edge == "outline") {
                            Text(
                                line,
                                style = base.copy(
                                    color = androidx.compose.ui.graphics.Color.Black,
                                    drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = textSize.value * 0.35f, join = androidx.compose.ui.graphics.StrokeJoin.Round),
                                ),
                            )
                            Text(line, style = base.copy(color = fg))
                        } else {
                            Text(
                                line,
                                style = base.copy(color = fg),
                                // As it comes, the player's own solid band; a chosen look, the 70 % band.
                                modifier = Modifier
                                    .background(if (SubtitleStyle.isDefault(step, color, edge)) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color(SubtitleStyle.BOX))
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Space and a hairline between the groups of the Settings list. */
@Composable
private fun HubGap() {
    Column {
        Spacer(Modifier.height(8.dp))
        Rule(Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(8.dp))
    }
}

/** One page of Settings in its list: an icon, the name, what's set now, and an arrow. */
@Composable
private fun HubRow(
    icon: @Composable () -> Unit,
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    /** TV: the page shown beside the list. */
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .focusRing(shape)
            .clip(shape)
            .background(if (selected) Ink3.copy(alpha = 0.7f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp, horizontal = if (selected) 8.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Ink3), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Faint)
    }
}

@Composable
private fun HubIcon(painter: Painter) {
    Icon(painter, contentDescription = null, tint = Paper, modifier = Modifier.size(21.dp))
}

/** Appearance: a circle half light, half dark. */
@Composable
private fun ThemeGlyph(color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val stroke = 1.6.dp.toPx()
        val r = size.minDimension / 2 - stroke / 2
        drawCircle(color, r, center, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawArc(
            color,
            startAngle = 90f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = androidx.compose.ui.geometry.Offset(center.x - r, center.y - r),
            size = androidx.compose.ui.geometry.Size(2 * r, 2 * r),
        )
    }
}

/** Devices: the outline of a phone. */
@Composable
private fun PhoneGlyph(color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val stroke = 1.6.dp.toPx()
        val w = size.width * 0.58f
        val h = size.height * 0.94f
        val left = (size.width - w) / 2
        val top = (size.height - h) / 2
        drawRoundRect(
            color,
            topLeft = androidx.compose.ui.geometry.Offset(left + stroke / 2, top + stroke / 2),
            size = androidx.compose.ui.geometry.Size(w - stroke, h - stroke),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
        )
        val y = top + h - 3.6.dp.toPx()
        drawLine(
            color,
            androidx.compose.ui.geometry.Offset(center.x - 2.dp.toPx(), y),
            androidx.compose.ui.geometry.Offset(center.x + 2.dp.toPx(), y),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

/** A theme to pick: a small preview (both halves for Automatic) and its name. */
@Composable
private fun ThemeOption(label: String, selected: Boolean, palettes: List<Palette>, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .focusRing(shape)
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) Signal else Hairline, shape)
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(8.dp))) {
            palettes.forEach { p ->
                Box(Modifier.weight(1f).fillMaxHeight().background(p.ink).padding(8.dp)) {
                    Text("Aa", fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = p.paper)
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(8.dp)
                            .background(p.signal, androidx.compose.foundation.shape.CircleShape),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            fontFamily = Sans,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) Paper else Muted,
            maxLines = 1,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

/** A look to pick, drawn as a tiny sketch of the screen. */
@Composable
private fun LookOption(label: String, selected: Boolean, sidebar: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val line = Paper.copy(alpha = 0.22f)
    val block = Paper.copy(alpha = 0.12f)
    Column(
        modifier
            .focusRing(shape)
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) Signal else Hairline, shape)
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(78.dp).clip(RoundedCornerShape(8.dp)).background(Ink).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (sidebar) {
                Column(Modifier.width(22.dp).fillMaxHeight().background(Ink3, RoundedCornerShape(3.dp)).padding(3.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.fillMaxWidth().height(3.dp).background(Signal, RoundedCornerShape(2.dp)))
                    repeat(4) { Box(Modifier.fillMaxWidth().height(3.dp).background(line, RoundedCornerShape(2.dp))) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.fillMaxWidth(0.7f).height(6.dp).background(block, RoundedCornerShape(3.dp)))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(2) { Box(Modifier.weight(1f).height(18.dp).background(line, RoundedCornerShape(3.dp))) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(4) { Box(Modifier.width(9.dp).height(4.dp).background(block, RoundedCornerShape(2.dp))) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(5) { Box(Modifier.weight(1f).height(22.dp).background(block, RoundedCornerShape(2.dp))) }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.width(22.dp).height(5.dp).background(line, RoundedCornerShape(2.dp)))
                        Box(Modifier.width(14.dp).height(5.dp).background(block, RoundedCornerShape(2.dp)))
                        Box(Modifier.width(14.dp).height(5.dp).background(block, RoundedCornerShape(2.dp)))
                    }
                    Box(Modifier.fillMaxWidth().height(30.dp).background(line, RoundedCornerShape(3.dp))) {
                        Box(Modifier.align(Alignment.BottomStart).padding(4.dp).width(20.dp).height(4.dp).background(Signal, RoundedCornerShape(2.dp)))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(4) { Box(Modifier.weight(1f).height(18.dp).background(block, RoundedCornerShape(2.dp))) }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            fontFamily = Sans,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) Paper else Muted,
            maxLines = 1,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

/**
 * A guide time difference in words. [earlier] phrases a needed correction (+60 → "1 h earlier",
 * the source shows shows an hour early); otherwise an applied one (+60 → "1 h later").
 */
private fun shiftWords(minutes: Int, earlier: Boolean): String {
    val a = kotlin.math.abs(minutes)
    val amount = if (a % 60 == 0) "${a / 60} h" else if (a > 60) "${a / 60} h ${a % 60} min" else "$a min"
    return when {
        earlier -> amount + if (minutes > 0) " earlier" else " later"
        else -> amount + if (minutes > 0) " later" else " earlier"
    }
}

/** "17:46" or "5:46 PM" for minutes after midnight, following the device's clock setting. */
private fun minuteLabel(context: android.content.Context, minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (android.text.format.DateFormat.is24HourFormat(context)) String.format(java.util.Locale.US, "%02d:%02d", h, m)
    else String.format(java.util.Locale.US, "%d:%02d %s", (h + 11) % 12 + 1, m, if (h < 12) "AM" else "PM")
}


/** Which video player to use; Automatic shows what it picked for this TV and why. */
@Composable
private fun PlayerChoice(vm: LibraryViewModel, current: String, firstChip: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val advice by androidx.compose.runtime.produceState<app.falak.player.player.EngineAdvice?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            app.falak.player.player.EngineAdvisor.advise(context)
        }
    }
    val selected = app.falak.player.player.Engine.of(current)
    Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
        Text("Video player", style = MaterialTheme.typography.bodyLarge, color = Paper)
        Text(
            when {
                selected == null -> advice?.let { "Automatic: ${it.engine.label}. ${it.reason}" } ?: "Automatic: checking this TV…"
                selected == app.falak.player.player.Engine.EXTERNAL -> "Channels and films open in another player app you choose."
                selected == app.falak.player.player.Engine.VLC -> "VLC plays almost any stream and shows teletext subtitles."
                selected == app.falak.player.player.Engine.EXO_SOFT -> "Software decoding: use it if the picture freezes or shows green blocks."
                else -> "Hardware decoding: smooth and light on the TV."
            },
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
        Spacer(Modifier.height(8.dp))
        ChipFlow {
            TagChip("Automatic", selected == null, modifier = firstChip) { vm.updateSettings { it.copy(player = "auto") } }
            listOf(
                app.falak.player.player.Engine.EXO,
                app.falak.player.player.Engine.EXO_SOFT,
                app.falak.player.player.Engine.VLC,
                app.falak.player.player.Engine.EXTERNAL,
            ).forEach { e ->
                TagChip(e.short, selected == e) { vm.updateSettings { it.copy(player = e.key) } }
            }
        }
        advice?.let {
            Spacer(Modifier.height(6.dp))
            Text(it.device, fontSize = 11.sp, lineHeight = 15.sp, color = Faint)
        }
        Text(
            "In the player, the ExoPlayer / VLC button switches player for the channel you're watching.",
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = Faint,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** "3 h ago", "2 days ago". */
internal fun ageText(at: Long): String {
    val mins = (System.currentTimeMillis() - at) / 60_000
    return when {
        mins < 2 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 48 * 60 -> "${mins / 60} h ago"
        else -> "${mins / (24 * 60)} days ago"
    }
}

private fun formatShift(minutes: Int): String {
    if (minutes == 0) return "Off"
    val sign = if (minutes > 0) "+" else "−"
    val m = kotlin.math.abs(minutes)
    return if (m % 60 == 0) "$sign${m / 60}h" else "$sign${m / 60}h${m % 60}"
}

/**
 * Where the guide comes from, and what each source really adds: how many of your channels it
 * covers and how many of those no earlier source had. Sources that add nothing can go.
 */
@Composable
private fun GuideSourcesDialog(vm: LibraryViewModel, p: app.falak.player.data.Playlist, handPicked: Int, onDismiss: () -> Unit) {
    val stats by vm.guideStats.collectAsState()
    var custom by remember { mutableStateOf("") }
    val extras = p.extraEpg.distinct()
    val suggestions = vm.guideSuggestions.filter { it.source.url !in extras }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Guide sources") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                if (vm.guideStatus.isNotEmpty()) {
                    Text(vm.guideStatus, style = MaterialTheme.typography.bodySmall, color = Muted)
                    Spacer(Modifier.height(10.dp))
                }
                // The provider's own guide first, then the extra ones in the order they are used.
                val rows = if (stats.isNotEmpty()) {
                    stats
                } else {
                    listOf(app.falak.player.data.GuideSourceStat("", "Your provider's guide", true, -1, -1, true)) +
                        extras.map { app.falak.player.data.GuideSourceStat(it, guideSourceName(it), false, -1, -1, true) }
                }
                rows.forEach { st ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(st.label, fontSize = 15.sp, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val (detail, weak) = when {
                                !st.ok -> "Couldn't be downloaded last time" to true
                                st.matched < 0 -> "Not loaded yet" to false
                                st.provider -> "Covers ${st.matched} of your channels" to (st.matched == 0)
                                st.added == 0 -> "Covers ${st.matched}, adds nothing new" to true
                                else -> "Covers ${st.matched} · adds ${st.added} the others miss" to false
                            }
                            Text(detail, fontSize = 12.sp, color = if (weak) Signal else Faint)
                            // Checked against the guide before it, channel by channel (see GuideCheck).
                            val against = st.against.ifBlank { "the other guides" }
                            when {
                                st.suggestShift != 0 -> Text(
                                    "Its times are ${shiftWords(st.suggestShift, earlier = true)} than $against on " +
                                        "${st.shiftVotes} of ${st.compared} shared channels.",
                                    fontSize = 12.sp, lineHeight = 17.sp,
                                    color = Signal,
                                )
                                st.compared > 0 -> Text(
                                    "Matches $against on ${st.agree} of ${st.compared} shared channels" +
                                        if (st.applied != 0) " (times moved ${shiftWords(st.applied, earlier = false)})" else "",
                                    fontSize = 12.sp, lineHeight = 17.sp,
                                    color = Faint,
                                )
                                st.applied != 0 -> Text("Times moved ${shiftWords(st.applied, earlier = false)}", fontSize = 12.sp, color = Faint)
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            if (st.suggestShift != 0 && st.url.isNotEmpty()) {
                                TextButton(onClick = { vm.setGuideSourceShift(st.url, st.applied + st.suggestShift) }) { Text("Fix times") }
                            } else if (st.applied != 0 && st.url.isNotEmpty()) {
                                TextButton(onClick = { vm.setGuideSourceShift(st.url, 0) }) { Text("Undo") }
                            }
                            if (!st.provider && st.url.isNotEmpty()) {
                                TextButton(onClick = { vm.removeGuideSource(st.url) }) { Text("Remove") }
                            }
                        }
                    }
                }
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Free guides that match your channels", style = MaterialTheme.typography.bodySmall, color = Muted)
                    suggestions.forEach { sug ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(sug.source.name, fontSize = 15.sp, color = Paper)
                                Text("about ${sug.channels} of your channels", fontSize = 12.sp, color = Faint)
                            }
                            TextButton(onClick = { vm.addGuideSources(listOf(sug.source.url)) }) { Text("Add") }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text("Or paste a guide link (XMLTV)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        TextButton(enabled = custom.trim().startsWith("http"), onClick = {
                            vm.addGuideSources(listOf(custom.trim()))
                            custom = ""
                        }) { Text("Add") }
                    },
                )
                if (handPicked > 0) {
                    TextButton(onClick = {
                        vm.updateSettings { it.copy(guideOverrides = it.guideOverrides - p.id) }
                        vm.loadGuide(force = true)
                    }) { Text("Undo $handPicked hand-picked guide" + if (handPicked == 1) "" else "s") }
                }
                Text(
                    "Wrong guide on one channel? Long-press it in the list and choose Fix TV guide.",
                    fontSize = 12.sp, lineHeight = 17.sp,
                    color = Faint,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !vm.guideLoading, onClick = { vm.loadGuide(force = true) }) {
                Text(if (vm.guideLoading) "Loading…" else "Reload guide")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private val LANGUAGES = listOf(
    "" to "Default", "ar" to "Arabic", "en" to "English", "fr" to "French", "hi" to "Hindi",
    "ur" to "Urdu", "es" to "Spanish", "tr" to "Turkish", "de" to "German", "it" to "Italian",
    "pt" to "Portuguese", "ru" to "Russian", "fa" to "Persian", "ml" to "Malayalam", "ta" to "Tamil",
)

/** A language setting: one row with the language chosen; the list opens in a picker. */
@Composable
private fun LanguageRow(title: String, subtitle: String?, selected: String, onSelect: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val name = LANGUAGES.firstOrNull { it.first == selected }?.second ?: "Default"
    ClickRow(title, listOfNotNull(name, subtitle).joinToString(" · ")) { picking = true }
    if (picking) {
        val current = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            repeat(8) {
                delay(50)
                if (runCatching { current.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(title) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(LANGUAGES) { (code, label) ->
                        val chosen = code == selected
                        val shape = RoundedCornerShape(10.dp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .then(if (chosen) Modifier.focusRequester(current) else Modifier)
                                .focusRing(shape)
                                .clip(shape)
                                .clickable {
                                    onSelect(code)
                                    picking = false
                                }
                                .padding(horizontal = 10.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (chosen) Signal else Paper,
                                fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                            )
                            if (chosen) Icon(Icons.Filled.Check, contentDescription = "Chosen", tint = Signal, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.fillMaxWidth().focusRing(shape).clip(shape).clickable(onClick = onToggle).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Paper)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                checkedTrackColor = Signal,
                checkedBorderColor = Signal,
                uncheckedThumbColor = Muted,
                uncheckedTrackColor = Ink3,
                uncheckedBorderColor = Faint,
            ),
        )
    }
}

/** The provider report card in full: the week's figures, the slow times, the channels that didn't play. */
@Composable
internal fun ServiceReportDialog(p: app.falak.player.data.Playlist, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var round by remember { mutableStateOf(0) }
    val s = remember(p.id, round) {
        app.falak.player.data.ServiceReport.summary(app.falak.player.data.ServiceLogStore.read(context.filesDir, p.id), System.currentTimeMillis())
    }
    val is24 = remember(context) { android.text.format.DateFormat.is24HourFormat(context) }
    val hour: (Int) -> String = { h ->
        if (is24) String.format(java.util.Locale.US, "%02d:00", h) else "${if (h % 12 == 0) 12 else h % 12} ${if (h < 12) "AM" else "PM"}"
    }
    val r = app.falak.player.data.ServiceReport
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Service report") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${p.name} · live TV on this device over the last ${r.DAYS} days. Worked out here from what you watched; nothing is sent anywhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                s.verdict?.let { v ->
                    Text(
                        v.label,
                        fontFamily = Serif,
                        fontSize = 24.sp,
                        color = when (v) {
                            app.falak.player.data.ServiceReport.Verdict.GOOD -> Color(0xFF5FB97A)
                            app.falak.player.data.ServiceReport.Verdict.FAIR -> Amber
                            app.falak.player.data.ServiceReport.Verdict.POOR -> Signal
                        },
                    )
                }
                ReportLine("Watched", if (s.watchedMs > 0) r.duration(s.watchedMs) else "Nothing yet")
                ReportLine(
                    "Channels start in",
                    s.averageStartMs?.let { "${r.seconds(it)} on average (${s.starts} starts)" } ?: "Not enough channels started yet",
                )
                ReportLine(
                    "Buffering",
                    s.stallsPerHour?.let { "${r.rate(it)} stops an hour watched" } ?: "Not enough watched yet",
                )
                ReportLine(
                    "Slow times",
                    if (s.slowHours.isEmpty()) {
                        if (s.stallsPerHour == null) "Not enough watched yet" else "None stand out"
                    } else {
                        s.slowHours.joinToString(", ") { r.hoursLabel(it, hour) } +
                            (s.slowFactor?.let { f -> " (${r.rate(f)}× more buffering)" } ?: "")
                    },
                )
                ReportLine(
                    "Didn't play",
                    when (s.failedChannels.size) {
                        0 -> "Every channel played"
                        else -> s.failedChannels.take(5).joinToString(", ") { (name, n) -> if (n > 1) "$name ($n×)" else name } +
                            if (s.failedChannels.size > 5) " and ${s.failedChannels.size - 5} more" else ""
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            TextButton(onClick = {
                app.falak.player.data.ServiceLogStore.clear(context.filesDir, p.id)
                round++
            }) { Text("Start afresh") }
        },
    )
}

@Composable
private fun ReportLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Paper)
    }
}

@Composable
private fun ClickRow(title: String, subtitle: String, modifier: Modifier = Modifier, arrow: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.fillMaxWidth().focusRing(shape).clip(shape).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Paper)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Opens a page of its own.
        if (arrow) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Faint)
        }
    }
}

/** Asks for the PIN; [onSubmit] returns false to show "Wrong PIN". */
@Composable
fun PinDialog(title: String, onDismiss: () -> Unit, onSubmit: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(8); wrong = false },
                label = { Text("PIN") },
                singleLine = true,
                isError = wrong,
                supportingText = if (wrong) ({ Text("Wrong PIN") }) else null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
        },
        confirmButton = { TextButton(onClick = { wrong = !onSubmit(pin) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditPinDialog(hasPin: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hasPin) "Change PIN" else "Set a PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(8); error = null },
                    label = { Text("New PIN (4–8 digits)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it.filter(Char::isDigit).take(8); error = null },
                    label = { Text("Repeat PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (hasPin) {
                    TextButton(onClick = { onSave("") }) { Text("Remove PIN (turn lock off)") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = when {
                    pin.length < 4 -> "Use at least 4 digits"
                    pin != confirm -> "The two PINs don't match"
                    else -> null
                }
                if (error == null) onSave(pin)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
