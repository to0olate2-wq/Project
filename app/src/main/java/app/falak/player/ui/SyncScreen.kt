package app.falak.player.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.KeyboardOptions
import app.falak.player.data.AppState
import app.falak.player.data.SyncBundle
import app.falak.player.autoSync
import app.falak.player.repo
import app.falak.player.sync.Announcer
import app.falak.player.sync.DeviceFinder
import app.falak.player.sync.FoundDevice
import app.falak.player.sync.SyncClient
import app.falak.player.sync.SyncServer
import app.falak.player.sync.deviceName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * "Your devices": set playlists up once and use them everywhere.
 * 1. Manage from a phone or computer: this device serves a page on the home network (address +
 *    QR code + 6-digit code) to add, rename or remove playlists and to back up or restore.
 * 2. Copy between devices running The Player: one shares, the other picks it and copies.
 * 3. Backup file (phones and tablets): save or load everything as one file.
 */
@Composable
fun SyncScreen(vm: LibraryViewModel, state: AppState) {
    val context = LocalContext.current
    val repo = context.repo
    val scope = rememberCoroutineScope()
    val tv = isTv()
    val me = remember { deviceName(context) }
    val back: () -> Unit = { vm.screen = if (vm.playlist != null || vm.settingsPage != null) Screen.SETTINGS else Screen.PLAYLISTS }
    BackHandler { back() }

    // ---- sharing (this device serves the page) ----
    var server by remember { mutableStateOf<SyncServer?>(null) }
    val events = remember { mutableStateListOf<String>() }
    val announcer = remember { Announcer(context) }
    fun stopSharing() {
        server?.stop()
        announcer.stop()
        server = null
    }
    fun startSharing() {
        if (server != null) return
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val s = SyncServer(repo, me) { msg -> main.post { events.add(0, msg); if (events.size > 6) events.removeAt(events.lastIndex) } }
        val port = try {
            s.start()
        } catch (e: Exception) {
            events.add(0, "Couldn't start sharing: ${e.message}")
            return
        }
        announcer.start(me, port)
        server = s
    }
    DisposableEffect(Unit) { onDispose { stopSharing() } }

    // ---- other devices that are sharing ----
    val found = remember { mutableStateListOf<FoundDevice>() }
    DisposableEffect(Unit) {
        val finder = DeviceFinder(context) { list -> found.clear(); found.addAll(list) }
        finder.start(skipName = me)
        onDispose { finder.stop() }
    }
    var copyFrom by remember { mutableStateOf<Pair<FoundDevice?, CopyMode>?>(null) } // device (null = typed address)
    var busy by remember { mutableStateOf(false) }
    var confirmUnlink by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    fun runCopy(address: String, code: String, get: Boolean) {
        busy = true
        result = null
        scope.launch {
            result = try {
                if (get) {
                    val bundle = SyncClient.fetch(repo.http, address, code)
                    repo.importBundle(bundle).describe() + " from ${bundle.from.ifBlank { "the other device" }}"
                } else {
                    SyncClient.send(repo.http, address, code, repo.exportBundle(me)) + " on the other device"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't copy: " + (e.message ?: "no answer from that device")
            }
            busy = false
        }
    }

    fun runLink(address: String, code: String) {
        busy = true
        result = null
        scope.launch {
            result = try {
                val myId = withContext(Dispatchers.IO) { repo.syncDeviceId() }
                val (key, peerId, peerName) = SyncClient.link(repo.http, address, code, myId, me)
                // Start from what both have; the first sync then gives the other device the lot.
                val bundle = SyncClient.fetch(repo.http, address, code)
                repo.importBundle(bundle)
                val name = peerName.ifBlank { bundle.from.ifBlank { "the other device" } }
                repo.linkSync(key, peerId, name)
                context.autoSync.syncNow()
                "Linked with $name. Playlists, favorites, hidden groups and Continue watching now stay the same " +
                    "on both while The Player is open on the same Wi-Fi."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't link: " + (e.message ?: "no answer from that device")
            }
            busy = false
        }
    }

    // ---- backup file ----
    val json = remember { Json { ignoreUnknownKeys = true; encodeDefaults = true } }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            result = try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.encodeToString(SyncBundle.serializer(), repo.exportBundle(me)).toByteArray())
                    }
                }
                "Backup saved"
            } catch (e: Exception) {
                "Couldn't save the backup"
            }
        }
    }
    val loadFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            result = try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } ?: throw IllegalStateException()
                repo.importBundle(json.decodeFromString(SyncBundle.serializer(), text)).describe()
            } catch (e: Exception) {
                "That file isn't a backup from The Player"
            }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier
                .fillMaxHeight()
                .then(if (tv) Modifier.widthIn(max = 820.dp) else Modifier)
                .fillMaxWidth()
                .padding(horizontal = if (tv) 24.dp else 16.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item { Box(Modifier.padding(horizontal = 4.dp)) { PageHeader("Your devices", onBack = back) } }
            item {
                Text(
                    "Set your playlists up once, then use them on every TV, phone and tablet. " +
                        "${state.playlists.size} playlist" + (if (state.playlists.size == 1) "" else "s") + " on $me.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
            }

            // Automatic sync between linked devices
            item { Title("Sync automatically") }
            item {
                val linked = state.sync.key.isNotEmpty()
                val status by context.autoSync.status.collectAsStateWithLifecycle()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .panel(RoundedCornerShape(14.dp))
                        .padding(18.dp),
                ) {
                    if (!linked) {
                        Text(
                            "Link your TVs and phones once: playlists, favorites, hidden groups and Continue watching then " +
                                "stay the same on all of them, by themselves, while The Player is open on the same Wi-Fi. " +
                                "On the other device open Your devices and choose Start sharing, then choose Link next to it below.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Muted,
                        )
                    } else {
                        Kicker(if (state.settings.syncOn) "On · linked with" else "Paused · linked with", color = Muted)
                        val now = System.currentTimeMillis()
                        if (state.sync.peers.isEmpty()) {
                            Text("Another device", fontFamily = Sans, fontSize = 16.sp, color = Paper)
                        }
                        state.sync.peers.values.sortedByDescending { it.lastSync }.forEach { peer ->
                            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(peer.name, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Paper)
                                Text(
                                    if (peer.lastSync > 0) "  ·  synced ${agoText(now - peer.lastSync)}" else "  ·  not synced yet",
                                    fontSize = 13.sp,
                                    color = Faint,
                                )
                            }
                        }
                        if (state.settings.syncOn && status != null) {
                            Text(status.orEmpty(), fontSize = 12.sp, color = Faint, modifier = Modifier.padding(top = 8.dp))
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (state.settings.syncOn) {
                                PaperButton("Sync now") { context.autoSync.syncNow() }
                                GhostButton("Pause") { vm.updateSettings { it.copy(syncOn = false) } }
                            } else {
                                PaperButton("Turn on") { vm.updateSettings { it.copy(syncOn = true) } }
                            }
                            GhostButton("Unlink") { confirmUnlink = true }
                        }
                    }
                }
            }

            // 1. Manage from a phone or computer
            item { Title("Manage from your phone or computer") }
            item {
                val s = server
                val address = remember(s) { s?.let { SyncServer.localAddress()?.let { ip -> "$ip:${it.port}" } } }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .panel(RoundedCornerShape(14.dp))
                        .padding(18.dp),
                ) {
                    if (s == null) {
                        Text(
                            if (tv) {
                                "Open a page on any phone or computer on the same Wi-Fi to add, rename or remove this TV's " +
                                    "playlists without typing with the remote, and to download or load a backup."
                            } else {
                                "Open a page on a computer or another phone on the same Wi-Fi to add, rename or remove " +
                                    "this phone's playlists, and to download or load a backup."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Muted,
                        )
                        Spacer(Modifier.height(14.dp))
                        PaperButton("Start sharing") { startSharing() }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val url = address?.let { "http://$it/?code=${s.code}" }
                            if (url != null) {
                                val qr = remember(url) { qrBitmap(url, 400) }
                                if (qr != null) {
                                    Image(
                                        qr,
                                        contentDescription = "QR code for this page",
                                        filterQuality = FilterQuality.None,
                                        modifier = Modifier
                                            .size(if (tv) 168.dp else 136.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color.White)
                                            .padding(8.dp),
                                    )
                                    Spacer(Modifier.width(20.dp))
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Kicker("Open in a browser", color = Muted)
                                Text(
                                    address?.let { "http://$it" } ?: "No network connection",
                                    fontFamily = Sans,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = if (tv) 22.sp else 18.sp,
                                    color = Paper,
                                )
                                Spacer(Modifier.height(12.dp))
                                Kicker("Code", color = Muted)
                                Text(
                                    s.code.chunked(3).joinToString(" "),
                                    style = Tabular.copy(fontSize = if (tv) 30.sp else 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp),
                                    color = Paper,
                                )
                                Text(
                                    "Or scan the code with the phone's camera.",
                                    fontSize = 12.sp, lineHeight = 17.sp,
                                    color = Faint,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                        if (events.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Rule()
                            Spacer(Modifier.height(10.dp))
                            events.forEach { e -> Text("•  $e", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(vertical = 2.dp)) }
                        }
                        Spacer(Modifier.height(14.dp))
                        GhostButton("Stop sharing") { stopSharing() }
                    }
                }
            }

            // 2. Copy between devices
            item { Title("Link or copy between devices") }
            item {
                Text(
                    "On the other device, open Your devices and choose Start sharing. It shows up here; " +
                        "link it to keep both in sync, or just copy its playlists here, or send yours there.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
            }
            if (found.isEmpty()) {
                item {
                    Text(
                        "Looking for devices that are sharing…",
                        fontSize = 13.sp,
                        color = Faint,
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                    )
                }
            }
            found.forEach { d ->
                item(key = "dev:${d.name}") {
                    val buttons: @Composable () -> Unit = {
                        PaperButton("Link") { copyFrom = d to CopyMode.LINK }
                        Spacer(Modifier.width(8.dp))
                        GhostButton("Get its playlists") { copyFrom = d to CopyMode.GET }
                        Spacer(Modifier.width(8.dp))
                        GhostButton("Send mine") { copyFrom = d to CopyMode.SEND }
                    }
                    if (tv) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp).panel(RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(d.name, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Paper)
                                Text(d.address, fontSize = 12.sp, color = Faint)
                            }
                            buttons()
                        }
                    } else {
                        // Phones: the buttons go under the name, where there's room for all three.
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp).panel(RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(d.name, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Paper)
                            Text(d.address, fontSize = 12.sp, color = Faint)
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) { buttons() }
                        }
                    }
                }
            }
            item {
                val shape = RoundedCornerShape(10.dp)
                Text(
                    "Not listed? Type its address",
                    color = Signal,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .focusRing(shape)
                        .clip(shape)
                        .clickable { copyFrom = null to CopyMode.GET }
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
            if (busy || result != null) {
                item {
                    Text(
                        if (busy) "Working…" else result.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Paper,
                        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                    )
                }
            }

            // 3. Backup file (needs a file manager, which most TVs don't have)
            if (!tv) {
                item { Title("Backup file") }
                item {
                    Text(
                        "Keep a copy of your playlists, favorites and lists, or move them by sending the file to yourself.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PaperButton("Save backup") {
                            try {
                                saveFile.launch("the-player-backup.json")
                            } catch (e: ActivityNotFoundException) {
                                result = "This device can't save files"
                            }
                        }
                        GhostButton("Load backup") {
                            try {
                                loadFile.launch(arrayOf("application/json", "text/plain", "*/*"))
                            } catch (e: ActivityNotFoundException) {
                                result = "This device can't open files"
                            }
                        }
                    }
                }
            }
        }
    }

    copyFrom?.let { (device, mode) ->
        CopyDialog(device, mode, onDismiss = { copyFrom = null }) { address, code ->
            copyFrom = null
            if (mode == CopyMode.LINK) runLink(address, code) else runCopy(address, code, mode == CopyMode.GET)
        }
    }
    if (confirmUnlink) {
        AlertDialog(
            onDismissRequest = { confirmUnlink = false },
            title = { Text("Unlink this device?") },
            text = {
                Text(
                    "It keeps everything it has, but stops syncing. The other devices keep syncing with each other.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnlink = false
                    repo.unlinkSync()
                }) { Text("Unlink") }
            },
            dismissButton = { TextButton(onClick = { confirmUnlink = false }) { Text("Cancel") } },
        )
    }
}

private enum class CopyMode { GET, SEND, LINK }

/** "just now", "5 min ago", "3 h ago", "2 days ago". */
private fun agoText(ms: Long): String {
    val min = ms / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 48 * 60 -> "${min / 60} h ago"
        else -> "${min / (24 * 60)} days ago"
    }
}

@Composable
private fun Title(text: String) {
    Box(Modifier.padding(start = 4.dp, top = 26.dp, bottom = 10.dp)) { Kicker(text) }
}

/** Asks for the code shown on the other device (and its address when it wasn't found). */
@Composable
private fun CopyDialog(device: FoundDevice?, mode: CopyMode, onDismiss: () -> Unit, onGo: (String, String) -> Unit) {
    var address by remember { mutableStateOf(device?.address.orEmpty()) }
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (mode) {
                    CopyMode.GET -> "Get playlists"
                    CopyMode.SEND -> "Send your playlists"
                    CopyMode.LINK -> "Link and keep in sync"
                },
            )
        },
        text = {
            Column {
                Text(
                    if (device != null) "Enter the code shown on ${device.name}." else "Enter the address and code shown on the other device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                if (device == null) {
                    OutlinedTextField(
                        address, { address = it.trim() },
                        label = { Text("Address, e.g. 192.168.1.20:8765") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                OutlinedTextField(
                    code, { code = it.filter(Char::isDigit).take(6) },
                    label = { Text("6-digit code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = code.length == 6 && address.isNotBlank(), onClick = { onGo(address, code) }) {
                Text(
                    when (mode) {
                        CopyMode.GET -> "Get"
                        CopyMode.SEND -> "Send"
                        CopyMode.LINK -> "Link"
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A QR code for [text], black on white. */
internal fun qrBitmap(text: String, size: Int): ImageBitmap? = try {
    val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
        text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
        mapOf(com.google.zxing.EncodeHintType.MARGIN to 0),
    )
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
} catch (e: Exception) {
    null
}
