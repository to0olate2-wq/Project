@file:OptIn(ExperimentalMaterial3Api::class)

package app.falak.player.ui

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
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            val s = state.settings
            val dark = rememberDarkTheme(s.themeMode, s.darkFrom, s.lightFrom)
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
                Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Transparent) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        // Pages slide and fade: forward from the right, back from the left.
                        val tv = isTv()
                        AnimatedContent(
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
                            when (screen) {
                                Screen.PLAYLISTS -> PlaylistsScreen(vm, state)
                                Screen.LIBRARY -> LibraryScreen(vm, state, onPlay = ::play)
                                Screen.SERIES -> SeriesScreen(vm, state, onPlay = ::play)
                                Screen.DETAILS -> DetailsScreen(vm, state, onPlay = ::play)
                                Screen.GUIDE -> GuideScreen(vm, state, onPlay = ::play)
                                Screen.SETTINGS -> SettingsScreen(vm, state)
                                Screen.GROUPS -> GroupsScreen(vm, state)
                                Screen.SYNC -> SyncScreen(vm, state)
                            }
                        }
                    }
                    vm.listSheetFor?.let { ch ->
                        val inSaved = vm.screen == Screen.LIBRARY && vm.section == Section.FAVORITES && vm.query.isBlank()
                        ItemSheet(vm, state, ch, orderInList = if (inSaved) vm.savedList else null) { vm.listSheetFor = null }
                    }
                    vm.guideFixFor?.let { ch -> GuideFixDialog(vm, ch) { vm.guideFixFor = null } }
                    vm.crashReport?.let { report -> CrashDialog(report) { vm.crashReport = null } }
                    UpdateDialogs(vm)
                    LaunchedEffect(Unit) { vm.checkForUpdates(manual = false) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onReturnFromPlayer()
        vm.refreshIfStale()
    }

    /** How deep a page sits, so transitions know which way to slide. */
    private fun depth(s: Screen): Int = when (s) {
        Screen.PLAYLISTS -> 0
        Screen.LIBRARY -> 1
        Screen.SERIES, Screen.DETAILS, Screen.GUIDE -> 2
        Screen.SETTINGS -> 3
        Screen.GROUPS, Screen.SYNC -> 4
    }

    private fun play(list: List<Channel>, channel: Channel) {
        vm.prepareQueue(list, channel)
        val index = list.indexOf(channel).coerceAtLeast(0)
        if (vm.state.value.settings.player == app.falak.player.player.Engine.EXTERNAL.key) {
            // "Another app" chosen in Settings: hand the stream straight to it.
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(android.net.Uri.parse(channel.url), "video/*")
                .putExtra("title", channel.name)
            try {
                startActivity(Intent.createChooser(intent, "Play with"))
                return
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "No other video player is installed; using the built-in one.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
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
private fun CrashDialog(report: String, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("The Player stopped last time") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Tap Copy and paste this to Claude so it can be fixed.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(report, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(report)); onClose() }) { Text("Copy") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun PlaylistsScreen(vm: LibraryViewModel, state: AppState) {
    var adding by remember { mutableStateOf(state.playlists.isEmpty()) }
    var toDelete by remember { mutableStateOf<Playlist?>(null) }
    var toRename by remember { mutableStateOf<Playlist?>(null) }
    val tv = isTv()

    BackHandler(enabled = vm.playlist != null) { vm.screen = Screen.LIBRARY }

    Column(Modifier.fillMaxSize().padding(horizontal = if (tv) 56.dp else 22.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(if (tv) 44.dp else 38.dp)
            Spacer(Modifier.weight(1f))
            if (tv) Clock(Modifier.padding(horizontal = 8.dp))
            IconButton(onClick = { vm.screen = Screen.SETTINGS }) {
                Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = Paper)
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
        Row(verticalAlignment = Alignment.Bottom) {
            Kicker("Your playlists", color = Muted)
            Spacer(Modifier.weight(1f))
            GhostButton("Devices", Icons.Filled.Share) { vm.screen = Screen.SYNC }
            Spacer(Modifier.width(10.dp))
            PaperButton("Add playlist", Icons.Filled.Add) { adding = true }
        }
        Spacer(Modifier.height(10.dp))
        Rule()
        if (state.playlists.isEmpty()) {
            Text(
                "Add your provider's M3U link, an M3U file, or your Xtream login to begin. The Player doesn't come with any channels.",
                fontFamily = Sans,
                fontSize = 14.sp,
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
                        when (p.type) {
                            SourceType.M3U_URL -> "M3U"
                            SourceType.M3U_FILE -> "FILE"
                            SourceType.XTREAM -> "XTREAM"
                        },
                        style = Tabular.copy(fontSize = 11.sp, letterSpacing = 1.2.sp),
                        color = Faint,
                        modifier = Modifier.width(64.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = Paper, maxLines = 1)
                        if (current) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                                LiveDot(6.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("Open now", fontFamily = Sans, fontSize = 12.sp, color = Muted)
                            }
                        }
                    }
                    IconButton(onClick = { toRename = p }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Rename", tint = Faint)
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

    if (adding) AddPlaylistDialog(vm) { adding = false }

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
            text = { Text("Its saved items and history will be deleted too.") },
            confirmButton = { TextButton(onClick = { vm.remove(p); toDelete = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AddPlaylistDialog(vm: LibraryViewModel, onClose: () -> Unit) {
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
