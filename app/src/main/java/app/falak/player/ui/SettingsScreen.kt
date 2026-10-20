@file:OptIn(ExperimentalMaterial3Api::class)

package app.falak.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import app.falak.player.data.GUIDE_SOURCES
import app.falak.player.data.guideSourceName

@Composable
fun SettingsScreen(vm: LibraryViewModel, state: AppState) {
    val s = state.settings
    val p = vm.playlist
    val pinSet = s.pin.isNotEmpty()
    var editPin by remember { mutableStateOf(false) }
    var addGuide by remember { mutableStateOf(false) }
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
    val back: () -> Unit = { vm.screen = if (p != null) Screen.LIBRARY else Screen.PLAYLISTS }
    // Group names come from the pre-built index (walking every channel here made Settings slow to open).
    val index = vm.index
    val groups = remember(index) {
        LinkedHashSet<String>().apply { app.falak.player.data.Kind.entries.forEach { addAll(index.groupsOf(it).keys) } }.toList()
    }
    LaunchedEffect(index, p?.extraEpg) { vm.refreshGuideSuggestions() }
    val lockedHere = if (p != null) s.lockedGroups[p.id].orEmpty() else emptyList()

    BackHandler { back() }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Paper) }
                Text("Settings", style = MaterialTheme.typography.titleLarge, color = Paper)
            }
        }

        item { SectionTitle("Appearance") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                items(Palettes.indices.toList(), key = { it }) { i ->
                    ThemeCard(Palettes[i], selected = s.theme == i) { vm.updateSettings { it.copy(theme = i) } }
                }
            }
        }

        item { SectionTitle("Playback") }
        item {
            Column(Modifier.padding(vertical = 8.dp)) {
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
            item { SectionTitle("TV guide · ${p.name}") }
            if (vm.guideStatus.isNotEmpty()) {
                item {
                    Text(vm.guideStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Guide time correction", style = MaterialTheme.typography.bodyLarge, color = Paper)
                        Text(
                            "If shows appear earlier or later than they air",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val shift = p.epgShiftMinutes
                    TextButton(onClick = { vm.shiftGuide(-30) }) { Text("−30m") }
                    Text(formatShift(shift), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { vm.shiftGuide(30) }) { Text("+30m") }
                }
            }
            val suggestions = vm.guideSuggestions.filter { it.source.url !in p.extraEpg }
            if (suggestions.isNotEmpty()) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .panel(RoundedCornerShape(10.dp))
                            .padding(14.dp),
                    ) {
                        Text("Better guide for your channels", fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Paper)
                        Text(
                            "These free guides match where your channels come from. Add them and the guide fills in channels your provider's guide misses.",
                            fontSize = 13.sp,
                            color = Muted,
                            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                        )
                        suggestions.forEach { sug ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(sug.source.name, fontSize = 15.sp, color = Paper)
                                    Text("about ${sug.channels} of your channels", fontSize = 12.sp, color = Faint)
                                }
                                TextButton(onClick = { vm.addGuideSources(listOf(sug.source.url)) }) { Text("Add") }
                            }
                        }
                        if (suggestions.size > 1) {
                            Spacer(Modifier.height(6.dp))
                            PaperButton("Add all ${suggestions.size}") {
                                vm.addGuideSources(suggestions.map { it.source.url })
                            }
                        }
                    }
                }
            }
            items(p.extraEpg.distinct(), key = { "epg:$it" }) { url ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        guideSourceName(url),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.removeGuideSource(url) }) { Text("Remove") }
                }
            }
            item {
                ClickRow("Add a guide source", "Free regional guides fill in channels your provider's guide misses") {
                    addGuide = true
                }
            }
            item {
                ClickRow(if (vm.guideLoading) "Loading guide…" else "Reload guide now", "Downloads all guide sources again") {
                    vm.loadGuide(force = true)
                }
            }
            val fixed = s.guideOverrides[p.id].orEmpty()
            if (fixed.isNotEmpty()) {
                item {
                    ClickRow("Undo ${fixed.size} hand-picked guide" + if (fixed.size == 1) "" else "s", "Go back to automatic matching for every channel") {
                        vm.updateSettings { it.copy(guideOverrides = it.guideOverrides - p.id) }
                        vm.loadGuide(force = true)
                    }
                }
            }
            item {
                Text(
                    "Wrong or missing guide on a channel? Long-press it in the list and choose Fix TV guide.",
                    fontSize = 12.sp,
                    color = Faint,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
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
            Column(Modifier.padding(vertical = 8.dp)) {
                Text("Refresh playlist automatically", style = MaterialTheme.typography.bodyLarge, color = Paper)
                Text(
                    "The saved copy opens instantly; a fresh one downloads in the background when it gets older than this" +
                        if (p != null && vm.loadedAt > 0) " · last updated ${ageText(vm.loadedAt)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(6 to "6 h", 12 to "12 h", 24 to "Daily", 0 to "Never").forEach { (h, label) ->
                        TagChip(label, s.autoRefreshHours == h) { vm.updateSettings { it.copy(autoRefreshHours = h) } }
                    }
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
        if (pinSet) {
            item {
                SwitchRow("Lock adult groups automatically", "Groups named XXX, Adult or 18+", s.lockAdult) {
                    guarded { vm.updateSettings { it.copy(lockAdult = !it.lockAdult) } }
                }
            }
            if (p != null && groups.isNotEmpty()) {
                item {
                    Text(
                        "Lock groups in ${p.name}",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
                items(groups, key = { "lock:$it" }) { g ->
                    CheckRow(g, g in lockedHere) { guarded { vm.toggleLockedGroup(g) } }
                }
            }
        }

        item { SectionTitle("About") }
        item {
            Text(
                "The Player ${BuildConfig.VERSION_NAME}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
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

    if (addGuide && p != null) {
        AddGuideDialog(
            existing = p.extraEpg,
            onDismiss = { addGuide = false },
            onAdd = { url ->
                addGuide = false
                vm.addGuideSources(listOf(url))
            },
        )
    }

    if (editPin) {
        EditPinDialog(
            hasPin = pinSet,
            onDismiss = { editPin = false },
            onSave = { newPin ->
                vm.updateSettings { it.copy(pin = newPin) }
                vm.unlocked = newPin.isNotEmpty()
                editPin = false
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Box(Modifier.padding(top = 26.dp, bottom = 6.dp)) { Kicker(text) }
}

/** A small preview of a theme: its background, text and accent. */
@Composable
private fun ThemeCard(p: Palette, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .width(128.dp)
            .focusRing(shape)
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) Signal else Hairline, shape)
            .clickable(onClick = onClick)
            .background(p.ink)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Aa", fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = p.paper)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(10.dp).background(p.signal, androidx.compose.foundation.shape.CircleShape))
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.ink3))
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth(0.6f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.ink3))
        Spacer(Modifier.height(10.dp))
        Text(p.name, fontFamily = Sans, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = p.muted, maxLines = 1)
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

@Composable
private fun AddGuideDialog(existing: List<String>, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var custom by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a guide source") },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text("Your XMLTV link (.xml or .xml.gz)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Or pick a free regional guide:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                LazyColumn {
                    items(GUIDE_SOURCES.filter { it.url !in existing }, key = { it.url }) { src ->
                        val name = src.name
                        val url = src.url
                        val shape = RoundedCornerShape(8.dp)
                        Text(
                            name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRing(shape)
                                .clip(shape)
                                .clickable { onAdd(url) }
                                .padding(vertical = 10.dp, horizontal = 6.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = custom.trim().startsWith("http"), onClick = { onAdd(custom.trim()) }) { Text("Add link") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val LANGUAGES = listOf(
    "" to "Default", "ar" to "Arabic", "en" to "English", "fr" to "French", "hi" to "Hindi",
    "ur" to "Urdu", "es" to "Spanish", "tr" to "Turkish", "de" to "German", "it" to "Italian",
    "pt" to "Portuguese", "ru" to "Russian", "fa" to "Persian", "ml" to "Malayalam", "ta" to "Tamil",
)

@Composable
private fun LanguageRow(title: String, subtitle: String?, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
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

@Composable
private fun CheckRow(title: String, checked: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().focusRing(shape).clip(shape).clickable(onClick = onToggle).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = androidx.compose.material3.CheckboxDefaults.colors(
                checkedColor = Signal,
                uncheckedColor = Faint,
                checkmarkColor = androidx.compose.ui.graphics.Color.White,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Text(title, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 12.dp))
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
