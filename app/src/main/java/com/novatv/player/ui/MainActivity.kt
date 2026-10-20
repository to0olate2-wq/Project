@file:OptIn(ExperimentalMaterial3Api::class)

package com.novatv.player.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.novatv.player.data.AppState
import com.novatv.player.data.Channel
import com.novatv.player.data.Playlist
import com.novatv.player.data.SourceType
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: LibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NovaTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val state by vm.state.collectAsStateWithLifecycle()
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        when (vm.screen) {
                            Screen.PLAYLISTS -> PlaylistsScreen(vm, state)
                            Screen.LIBRARY -> LibraryScreen(vm, state, onPlay = ::play)
                            Screen.SERIES -> SeriesScreen(vm, state, onPlay = ::play)
                            Screen.GUIDE -> GuideScreen(vm, state, onPlay = ::play)
                            Screen.SETTINGS -> SettingsScreen(vm, state)
                        }
                    }
                    vm.crashReport?.let { report -> CrashDialog(report) { vm.crashReport = null } }
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
private fun CrashDialog(report: String, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Nova TV stopped last time") },
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

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Nova TV",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.screen = Screen.SETTINGS }) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
            Spacer(Modifier.width(4.dp))
            Button(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Add playlist")
            }
        }
        Spacer(Modifier.height(16.dp))
        if (state.playlists.isEmpty()) {
            Text(
                "Add your provider's M3U link, an M3U file, or your Xtream login to get started.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.playlists, key = { it.id }) { p ->
                val shape = RoundedCornerShape(14.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .focusRing(shape)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { vm.open(p) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (p.type) {
                                SourceType.M3U_URL -> "M3U link"
                                SourceType.M3U_FILE -> "M3U file"
                                SourceType.XTREAM -> "Xtream · ${p.username}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { toDelete = p }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove")
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
