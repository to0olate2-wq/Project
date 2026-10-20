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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
            FalakTheme {
                AuroraBackground()
                Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Transparent) {
                    val state by vm.state.collectAsStateWithLifecycle()
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        when (vm.screen) {
                            Screen.PLAYLISTS -> PlaylistsScreen(vm, state)
                            Screen.LIBRARY -> LibraryScreen(vm, state, onPlay = ::play)
                            Screen.SERIES -> SeriesScreen(vm, state, onPlay = ::play)
                            Screen.DETAILS -> DetailsScreen(vm, state, onPlay = ::play)
                            Screen.GUIDE -> GuideScreen(vm, state, onPlay = ::play)
                            Screen.SETTINGS -> SettingsScreen(vm, state)
                        }
                    }
                    vm.crashReport?.let { report -> CrashDialog(report) { vm.crashReport = null } }
                    UpdateDialogs(vm)
                    LaunchedEffect(Unit) { vm.checkForUpdates(manual = false) }
                }
            }
        }
    }

    private fun play(list: List<Channel>, channel: Channel) {
        vm.prepareQueue(list, channel)
        val index = list.indexOf(channel).coerceAtLeast(0)
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
        title = { Text("Falak stopped last time") },
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

    BackHandler(enabled = vm.playlist != null) { vm.screen = Screen.LIBRARY }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FalakMark(48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Wordmark(30)
                Text(
                    "Your channels, movies and series in one orbit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Clock(Modifier.padding(horizontal = 8.dp))
            IconButton(onClick = { vm.screen = Screen.SETTINGS }) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your playlists", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            val addShape = RoundedCornerShape(50)
            Row(
                Modifier
                    .focusRing(addShape)
                    .clip(addShape)
                    .background(GoldBrush)
                    .clickable { adding = true }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Night)
                Spacer(Modifier.width(6.dp))
                Text("Add playlist", color = Night, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (state.playlists.isEmpty()) {
            val shape = RoundedCornerShape(20.dp)
            Column(Modifier.fillMaxWidth().glass(shape).padding(22.dp)) {
                Text("Start your orbit", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Add your provider's M3U link, an M3U file, or your Xtream login. Falak doesn't include any channels.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.playlists, key = { it.id }) { p ->
                val shape = RoundedCornerShape(18.dp)
                val current = p.id == vm.playlist?.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .focusRing(shape)
                        .glass(shape, alpha = if (current) 0.10f else 0.06f)
                        .clickable { vm.open(p) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    if (p.type == SourceType.XTREAM) listOf(Violet, Teal) else listOf(Teal, Violet),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            when (p.type) {
                                SourceType.M3U_URL -> "M3U"
                                SourceType.M3U_FILE -> "FILE"
                                SourceType.XTREAM -> "XC"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            when (p.type) {
                                SourceType.M3U_URL -> "M3U link"
                                SourceType.M3U_FILE -> "M3U file"
                                SourceType.XTREAM -> "Xtream · ${p.username}"
                            } + if (current) "  ·  open" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (current) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { toDelete = p }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (adding) AddPlaylistDialog(vm) { adding = false }

    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Remove ${p.name}?") },
            text = { Text("Its favorites and history will be deleted too.") },
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
