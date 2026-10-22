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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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

/** Public privacy policy (also linked from the Google Play listing). */
const val PRIVACY_URL = "https://github.com/to0olate2-wq/Project/blob/main/PRIVACY.md"

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
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    // Changing parental settings needs the PIN once per session.
    val guarded: (() -> Unit) -> Unit = { action ->
        if (pinSet && !vm.unlocked) {
            pendingAction = action
        } else {
            action()
        }
    }
    val back: () -> Unit = { if (!vm.leaveDetour()) vm.screen = if (p != null) Screen.LIBRARY else Screen.PLAYLISTS }
    val index = vm.index
    LaunchedEffect(index, p?.extraEpg) { vm.refreshGuideSuggestions() }
    val lockedHere = if (p != null) s.lockedGroups[p.id].orEmpty() else emptyList()

    BackHandler { back() }

    val tvLayout = isTv()
    // TV: a centred column of comfortable reading width; phone: the full width.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
        Modifier
            .fillMaxHeight()
            .then(if (tvLayout) Modifier.widthIn(max = 780.dp) else Modifier)
            .fillMaxWidth()
            .padding(horizontal = if (tvLayout) 24.dp else 16.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item { Box(Modifier.padding(horizontal = 4.dp)) { PageHeader("Settings", onBack = back) } }

        item { SectionTitle("Appearance") }
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
                    ThemeOption("Automatic", s.themeMode == "auto", listOf(LightPalette, DarkPalette), Modifier.weight(1f)) {
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
                    if (s.look == "sidebar") "Side menu, big “Continue watching” cards, and each section as a grid with filters."
                    else "Tabs along the top and a slideshow on Home.",
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TagChip("Automatic (${if (detected) "TV" else "phone"})", s.layout == "auto") { vm.updateSettings { it.copy(layout = "auto") } }
                    TagChip("TV", s.layout == "tv") { vm.updateSettings { it.copy(layout = "tv") } }
                    TagChip("Phone & tablet", s.layout == "phone") { vm.updateSettings { it.copy(layout = "phone") } }
                }
            }
        }

        item { SectionTitle("Player") }
        item { PlayerChoice(vm, s.player) }

        item { SectionTitle("Playback") }
        item {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text("Picture size", style = MaterialTheme.typography.bodyLarge, color = Paper)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Fit", "Fill", "Zoom").forEachIndexed { i, label ->
                        TagChip(label, s.aspect == i) { vm.updateSettings { it.copy(aspect = i) } }
                    }
                }
            }
        }
        item {
            SwitchRow("Larger buffer", "Fewer pauses on slow connections; channels start a little slower", s.bigBuffer) {
                vm.updateSettings { it.copy(bigBuffer = !it.bigBuffer) }
            }
        }
        item {
            SwitchRow(
                "Software audio decoding",
                "Turn on if some channels or movies play without sound (Dolby/DTS on TV boxes)",
                s.softwareAudio,
            ) { vm.updateSettings { it.copy(softwareAudio = !it.softwareAudio) } }
        }
        item {
            SwitchRow(
                "Even out volume between channels",
                "Turns quiet channels up and loud ones down to a similar level (not for Dolby sent to a receiver)",
                s.levelVolume,
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("auto" to "Automatic", "on" to "Always", "off" to "Off").forEach { (key, label) ->
                        TagChip(label, s.nightSound == key) { vm.updateSettings { it.copy(nightSound = key) } }
                    }
                }
            }
        }
        item {
            SwitchRow(
                "Play the next episode automatically",
                "Series: the next episode starts 10 seconds into the credits (OK plays it now, Back keeps watching)",
                s.autoNextEpisode,
            ) { vm.updateSettings { it.copy(autoNextEpisode = !it.autoNextEpisode) } }
        }
        item {
            SwitchRow("Prefer HLS for Xtream live TV", "Try this if channels freeze or keep stopping", s.preferHls) {
                vm.updateSettings { it.copy(preferHls = !it.preferHls) }
            }
        }

        item {
            LanguageRow("Preferred audio language", "When a channel or video has several audio tracks", s.audioLang) { code ->
                vm.updateSettings { it.copy(audioLang = code) }
            }
        }
        item {
            SwitchRow("Show subtitles automatically", "When a stream includes subtitles or closed captions", s.subtitlesOn) {
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
                title = "Online subtitles (OpenSubtitles.com)",
                subtitle = if (s.osApiKey.isBlank()) "Find subtitles for movies and episodes that have none · tap to set up"
                else "On" + if (s.osUser.isNotBlank()) " · signed in as ${s.osUser}" else " · not signed in (fewer downloads per day)",
            ) { editSubs = true }
        }

        if (p != null) {
            item { SectionTitle("TV guide") }
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

        if (Updater.enabled) {
            item { SectionTitle("Updates") }
            item {
                ClickRow(
                    title = if (vm.updateChecking) "Checking…" else "Check for updates",
                    subtitle = "The Player ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                ) { vm.checkForUpdates(manual = true) }
            }
        }

        item { SectionTitle("General") }
        item {
            ClickRow("Your devices", "Copy your playlists to other TVs and phones, or manage them from a phone or computer") {
                vm.screen = Screen.SYNC
            }
        }
        item {
            val linked = state.sync.key.isNotEmpty()
            val names = state.sync.peers.values.joinToString(", ") { it.name }
            SwitchRow(
                "Sync with my devices",
                when {
                    !linked -> "Off. Link a TV or phone on the same Wi-Fi in Your devices"
                    !s.syncOn -> "Off. Linked with ${names.ifBlank { "another device" }}"
                    else -> "Playlists, favorites, hidden groups and Continue watching stay the same on " +
                        names.ifBlank { "your linked devices" }
                },
                s.syncOn && linked,
            ) {
                if (!linked) vm.screen = Screen.SYNC else vm.updateSettings { it.copy(syncOn = !it.syncOn) }
            }
        }
        if (p != null) {
            item {
                ClickRow("Show & hide groups", "Hide groups you never watch, or bring them back") { vm.screen = Screen.GROUPS }
            }
        }
        item {
            SwitchRow("Open last playlist on start", null, s.openLast) {
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
        item {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text("Refresh playlist automatically", style = MaterialTheme.typography.bodyLarge, color = Paper)
                Text(
                    "The saved copy opens instantly; a fresh one downloads in the background when it gets older than this" +
                        if (p != null && vm.loadedAt > 0) " · last updated ${ageText(vm.loadedAt)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                ) { guarded { vm.screen = Screen.LOCKS } }
            }
        }

        item { SectionTitle("Kids profile") }
        if (p == null) {
            item { Text("Open a playlist to set up kids mode.", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) }
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
                    ) { guarded { vm.screen = Screen.KIDS } }
                }
            }
        }

        item { SectionTitle("About") }
        item {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text("Made by Ahmed", fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = Paper)
                Text("The Player ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", fontSize = 13.sp, color = Muted)
                Text("Uses ExoPlayer (Apache 2.0), LibVLC and FFmpeg (LGPL).", fontSize = 12.sp, color = Faint)
                Spacer(Modifier.height(10.dp))
                Text(
                    "The Player doesn't include any channels or content: it plays the playlists you add. " +
                        "Your playlists and settings stay on this device; nothing is sent to the developer.",
                    fontSize = 13.sp,
                    color = Muted,
                )
                val uri = androidx.compose.ui.platform.LocalUriHandler.current
                Text(
                    "Privacy policy",
                    fontSize = 13.sp,
                    color = Signal,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .focusRing(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .clickable { runCatching { uri.openUri(PRIVACY_URL) } }
                        .padding(vertical = 6.dp, horizontal = 2.dp),
                )
            }
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
private fun SectionTitle(text: String) {
    Box(Modifier.padding(start = 4.dp, top = 26.dp, bottom = 6.dp)) { Kicker(text) }
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
private fun PlayerChoice(vm: LibraryViewModel, current: String) {
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
        Row(
            Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TagChip("Automatic", selected == null) { vm.updateSettings { it.copy(player = "auto") } }
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
            Text(it.device, fontSize = 11.sp, color = Faint)
        }
        Text(
            "In the player, the ExoPlayer / VLC button switches player for the channel you're watching.",
            fontSize = 11.sp,
            color = Faint,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** "3 h ago", "2 days ago". */
private fun ageText(at: Long): String {
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
                                    fontSize = 12.sp,
                                    color = Signal,
                                )
                                st.compared > 0 -> Text(
                                    "Matches $against on ${st.agree} of ${st.compared} shared channels" +
                                        if (st.applied != 0) " (times moved ${shiftWords(st.applied, earlier = false)})" else "",
                                    fontSize = 12.sp,
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
                    fontSize = 12.sp,
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

@Composable
private fun LanguageRow(title: String, subtitle: String?, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = Paper)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(LANGUAGES, key = { it.first }) { (code, name) ->
                TagChip(name, selected == code) { onSelect(code) }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().focusRing(shape).clip(shape).clickable(onClick = onToggle).padding(vertical = 10.dp, horizontal = 4.dp),
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

@Composable
private fun ClickRow(title: String, subtitle: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().focusRing(shape).clip(shape).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = Paper)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
