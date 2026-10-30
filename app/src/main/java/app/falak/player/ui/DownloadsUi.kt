package app.falak.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.OfflineCenter
import app.falak.player.R
import app.falak.player.data.AppState
import app.falak.player.data.Channel
import app.falak.player.data.Kind
import app.falak.player.data.OfflineItem
import app.falak.player.data.OfflineState
import app.falak.player.data.Repository
import coil.compose.AsyncImage

/*
 * Downloads: a Download button on a film's page and beside each episode (and "Download season"),
 * Delete once it's saved, and the Downloads page with everything saved or on its way.
 */

/** Every download, kept up to date as they move. */
@Composable
fun rememberDownloads(): List<OfflineItem> {
    val items by OfflineCenter.items.collectAsState()
    // "Waits while you watch" comes and goes with the player.
    OfflineCenter.watching.collectAsState().value
    return items
}

@Composable
fun downloadIcon(): ImageVector = ImageVector.vectorResource(R.drawable.ic_download)

/** The user agent downloads use: the item's own, else the playlist's. */
private fun agentFor(vm: LibraryViewModel): String = vm.playlist?.userAgent?.ifBlank { null } ?: Repository.DEFAULT_UA

/** What a download is doing, in a few words ("Downloading 34%", "Waiting for Wi-Fi"…). */
fun downloadStatus(item: OfflineItem): String = when (item.state) {
    OfflineState.DONE -> "Downloaded · " + OfflineCenter.sizeLabel(item.size)
    OfflineState.RUNNING -> if (item.size > 0) {
        "Downloading ${(item.fraction * 100).toInt()}% · ${OfflineCenter.sizeLabel(item.done)} of ${OfflineCenter.sizeLabel(item.size)}"
    } else {
        "Downloading · " + OfflineCenter.sizeLabel(item.done)
    }
    OfflineState.QUEUED -> item.note.ifBlank { OfflineCenter.waitingNote() }
    OfflineState.PAUSED -> "Paused" + if (item.size > 0) " · ${(item.fraction * 100).toInt()}%" else ""
    OfflineState.FAILED -> item.note.ifBlank { "Couldn't download" }
}

/** The same, shorter, for lists ("Downloading 36% of 2.0 GB"). */
fun downloadStatusShort(item: OfflineItem): String =
    if (item.state == OfflineState.RUNNING && item.size > 0) {
        "Downloading ${(item.fraction * 100).toInt()}% of ${OfflineCenter.sizeLabel(item.size)}"
    } else {
        downloadStatus(item)
    }

/** "Delete the downloaded copy?" before removing a finished download. */
@Composable
private fun ConfirmDelete(item: OfflineItem?, onDismiss: () -> Unit) {
    item ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete download?") },
        text = {
            Text(
                if (item.state == OfflineState.DONE) {
                    "“${item.title}” (${OfflineCenter.sizeLabel(item.size)}) will be removed from this device. You can download it again any time."
                } else {
                    "The download of “${item.title}” stops and what's saved so far is removed."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                OfflineCenter.delete(item.id)
                onDismiss()
            }) { Text(if (item.state == OfflineState.DONE) "Delete" else "Cancel download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } },
    )
}

/** A film's page: Download, its progress (tap to pause or resume), and Delete once it's saved. */
@Composable
fun DownloadActions(vm: LibraryViewModel, ch: Channel) {
    if (ch.url.isBlank() || ch.isSeriesFolder || ch.kind == Kind.LIVE || ch.id.startsWith("cu:")) return
    val p = vm.playlist ?: return
    val item = rememberDownloads().firstOrNull { it.id == ch.id }
    var confirm by remember { mutableStateOf<OfflineItem?>(null) }
    val icon = downloadIcon()
    when (item?.state) {
        null -> GhostButton("Download", icon) { OfflineCenter.download(ch, p.id, agentFor(vm)) }
        OfflineState.DONE -> {
            GhostButton("Downloaded", Icons.Filled.Check) { vm.screen = Screen.DOWNLOADS }
            GhostButton("Delete download", Icons.Filled.Delete) { confirm = item }
        }
        OfflineState.RUNNING -> {
            GhostButton("Pause · ${(item.fraction * 100).toInt()}%", icon) { OfflineCenter.pause(ch.id) }
            GhostButton("Cancel download", Icons.Filled.Close) { confirm = item }
        }
        OfflineState.QUEUED -> {
            GhostButton(downloadStatus(item), icon) { vm.screen = Screen.DOWNLOADS }
            GhostButton("Cancel download", Icons.Filled.Close) { confirm = item }
        }
        OfflineState.PAUSED, OfflineState.FAILED -> {
            GhostButton(if (item.state == OfflineState.FAILED) "Try download again" else "Resume download", icon) { OfflineCenter.resume(ch.id) }
            GhostButton("Cancel download", Icons.Filled.Close) { confirm = item }
        }
    }
    ConfirmDelete(confirm) { confirm = null }
}

/** Under a film's buttons: how its download is going (a bar and a line), while it isn't finished. */
@Composable
fun DownloadProgress(ch: Channel) {
    val item = rememberDownloads().firstOrNull { it.id == ch.id } ?: return
    if (item.state == OfflineState.DONE) return
    Column(Modifier.padding(top = 12.dp).widthIn(max = 420.dp).fillMaxWidth()) {
        if (item.size > 0) {
            LinearProgressIndicator(
                progress = { item.fraction },
                color = if (item.state == OfflineState.FAILED) Amber else Signal,
                trackColor = Hairline,
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }
        Text(downloadStatus(item), fontFamily = Sans, fontSize = 12.sp, color = if (item.state == OfflineState.FAILED) Amber else Muted, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Beside an episode: download it, see it coming, cancel it, or delete the saved copy. */
@Composable
fun EpisodeDownload(vm: LibraryViewModel, ep: Channel) {
    if (ep.url.isBlank()) return
    val p = vm.playlist ?: return
    val item = rememberDownloads().firstOrNull { it.id == ep.id }
    var confirm by remember { mutableStateOf<OfflineItem?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (item?.state) {
            null -> IconButton(onClick = { OfflineCenter.download(ep, p.id, agentFor(vm), vm.series) }) {
                Icon(downloadIcon(), contentDescription = "Download ${ep.name}", tint = Paper)
            }
            OfflineState.DONE -> {
                Icon(Icons.Filled.Check, contentDescription = "Downloaded", tint = Signal, modifier = Modifier.size(20.dp))
                IconButton(onClick = { confirm = item }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete download", tint = Muted)
                }
            }
            OfflineState.RUNNING -> {
                IconButton(onClick = { OfflineCenter.pause(ep.id) }) {
                    Box(contentAlignment = Alignment.Center) {
                        if (item.size > 0) {
                            CircularProgressIndicator(progress = { item.fraction }, color = Signal, trackColor = Hairline, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
                        } else {
                            CircularProgressIndicator(color = Signal, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
                        }
                    }
                }
                IconButton(onClick = { confirm = item }) { Icon(Icons.Filled.Close, contentDescription = "Cancel download", tint = Muted) }
            }
            // Why it waits is said under the episode's name.
            OfflineState.QUEUED -> IconButton(onClick = { confirm = item }) { Icon(Icons.Filled.Close, contentDescription = "Cancel download", tint = Muted) }
            OfflineState.PAUSED, OfflineState.FAILED -> {
                IconButton(onClick = { OfflineCenter.resume(ep.id) }) {
                    Icon(downloadIcon(), contentDescription = "Resume download", tint = Amber)
                }
                IconButton(onClick = { confirm = item }) { Icon(Icons.Filled.Close, contentDescription = "Cancel download", tint = Muted) }
            }
        }
    }
    ConfirmDelete(confirm) { confirm = null }
}

/** A season's episodes: download the ones not saved yet, or how far that has got. */
@Composable
fun SeasonDownload(vm: LibraryViewModel, episodes: List<Channel>) {
    val p = vm.playlist ?: return
    val all = episodes.filter { it.url.isNotBlank() }
    if (all.isEmpty()) return
    val downloads = rememberDownloads()
    val mine = all.mapNotNull { ep -> downloads.firstOrNull { it.id == ep.id } }
    val done = mine.count { it.state == OfflineState.DONE }
    val label = when {
        done == all.size -> "✓ Season downloaded"
        mine.isNotEmpty() -> "Downloaded $done of ${all.size}"
        else -> "Download season (${all.size})"
    }
    TagChip(label, done == all.size) {
        if (done < all.size) {
            all.forEach { OfflineCenter.download(it, p.id, agentFor(vm), vm.series) }
        } else {
            vm.screen = Screen.DOWNLOADS
        }
    }
}

/** A season's episodes that are downloaded or on their way: delete them all at once. */
@Composable
fun SeasonDelete(episodes: List<Channel>) {
    val ids = remember(episodes) { episodes.mapTo(HashSet()) { it.id } }
    val mine = rememberDownloads().filter { it.id in ids }
    if (mine.isEmpty()) return
    var confirm by remember { mutableStateOf(false) }
    TagChip("Delete season downloads (${mine.size})", false) { confirm = true }
    if (confirm) {
        val size = mine.sumOf { if (it.state == OfflineState.DONE) it.size else it.done }
        ConfirmDeleteMany(
            title = "Delete this season's downloads?",
            text = "${mine.size} episode" + (if (mine.size == 1) "" else "s") + " (${OfflineCenter.sizeLabel(size)}) will be removed from this device. " +
                "Any still downloading stop.",
            confirm = "Delete",
            onConfirm = { OfflineCenter.deleteMany(mine.map { it.id }) },
        ) { confirm = false }
    }
}

/** Home: what's saved on this device, to watch without internet. */
@Composable
fun DownloadsShelf(vm: LibraryViewModel, items: List<OfflineItem>, onPlay: (List<Channel>, Channel) -> Unit) {
    val done = items.filter { it.state == OfflineState.DONE }.asReversed()
    if (done.isEmpty()) return
    Shelf("Downloads", onSeeAll = { vm.screen = Screen.DOWNLOADS }) {
        items(done, key = { "dl:" + it.id }) { item ->
            val ch = item.asChannel()
            // An episode shows its series' poster and name ("Harbour Lights · S1 · E3").
            PosterCard(ch.copy(name = item.title, logo = item.showPoster.ifBlank { item.logo }), null, onClick = { onPlay(listOf(ch), ch) })
        }
    }
}

/** Everything downloaded or on its way: play, pause, carry on, delete; Wi-Fi only; the space it takes. */
@Composable
fun DownloadsScreen(vm: LibraryViewModel, state: AppState, onPlay: (List<Channel>, Channel) -> Unit) {
    val items = rememberDownloads()
    val back = { vm.screen = if (vm.playlist != null) Screen.LIBRARY else Screen.PLAYLISTS }
    BackHandler { back() }
    var confirm by remember { mutableStateOf<OfflineItem?>(null) }
    val wifiOnly = state.settings.downloadWifiOnly
    val tv = isTv()
    var confirmAll by remember { mutableStateOf(false) }
    val deleteWatched = state.settings.downloadDeleteWatched
    Column(Modifier.fillMaxSize().padding(horizontal = pageMargin())) {
        PageHeader("Downloads", onBack = back)
        // How downloads behave on this device, and clearing them all.
        ChipFlow(Modifier.padding(bottom = 10.dp)) {
            TagChip(if (wifiOnly) "✓ Wi-Fi only" else "Wi-Fi only", wifiOnly) {
                vm.updateSettings { it.copy(downloadWifiOnly = !it.downloadWifiOnly) }
                // The waiting downloads follow the new choice (settings change at once).
                OfflineCenter.rescheduleForNetwork()
            }
            TagChip(if (deleteWatched) "✓ Delete when watched" else "Delete when watched", deleteWatched) {
                vm.updateSettings { it.copy(downloadDeleteWatched = !it.downloadDeleteWatched) }
            }
            if (items.isNotEmpty()) TagChip("Delete all", false) { confirmAll = true }
        }
        val done = items.count { it.state == OfflineState.DONE }
        Text(
            listOfNotNull(
                if (done > 0) "$done downloaded · ${OfflineCenter.sizeLabel(OfflineCenter.usedSpace())}" else null,
                "${OfflineCenter.sizeLabel(OfflineCenter.freeSpace())} free",
                if (wifiOnly) "Downloads use Wi-Fi only" else "Downloads use Wi-Fi or mobile data",
                if (deleteWatched) "Each one goes once watched to the end" else null,
            ).joinToString("  ·  "),
            fontFamily = Sans,
            fontSize = 13.sp,
            color = Muted,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        if (items.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Nothing downloaded yet", fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = Paper)
                Spacer(Modifier.height(8.dp))
                Text(
                    "On a film's page, or beside an episode, tap Download to watch it later without internet.",
                    fontFamily = Sans,
                    fontSize = 14.sp,
                    color = Muted,
                    modifier = Modifier.widthIn(max = 460.dp),
                )
            }
        } else {
            // On their way first, then the saved ones, newest first.
            val ordered = items.filter { it.state != OfflineState.DONE } + items.filter { it.state == OfflineState.DONE }.asReversed()
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ordered, key = { it.id }) { item ->
                    DownloadRow(item, tv, onPlay = { ch -> onPlay(listOf(ch), ch) }, onDelete = { confirm = item })
                }
            }
        }
    }
    ConfirmDelete(confirm) { confirm = null }
    if (confirmAll) {
        val size = items.sumOf { if (it.state == OfflineState.DONE) it.size else it.done }
        ConfirmDeleteMany(
            title = "Delete all downloads?",
            text = "All ${items.size} (${OfflineCenter.sizeLabel(size)}) will be removed from this device, and downloads on their way stop. " +
                "You can download them again any time.",
            confirm = "Delete all",
            onConfirm = { OfflineCenter.deleteAll() },
        ) { confirmAll = false }
    }
}

/** "Delete these?" for several downloads at once. */
@Composable
fun ConfirmDeleteMany(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } },
    )
}

@Composable
private fun DownloadRow(item: OfflineItem, tv: Boolean, onPlay: (Channel) -> Unit, onDelete: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val done = item.state == OfflineState.DONE
    Row(
        Modifier
            .fillMaxWidth()
            .panel(shape)
            // Phones: a tap on a saved one plays it (TV uses its Play button).
            .then(if (!tv && done) Modifier.clip(shape).clickable { onPlay(item.asChannel()) } else Modifier)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // An episode's own picture, else its series' poster.
        val still = item.kind == Kind.SERIES && item.logo.isNotBlank()
        AsyncImage(
            model = item.logo.ifBlank { item.showPoster }.ifBlank { null },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(if (still) 96.dp else 56.dp)
                .aspectRatio(if (still) 16f / 9f else 2f / 3f)
                .clip(RoundedCornerShape(6.dp))
                .background(Ink3),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // An episode: its series first, then "S1 · E3 · its own name".
            val second = if (item.show.isNotBlank()) listOf(item.episodeMark, item.name).filter { it.isNotBlank() }.joinToString(" · ") else item.info
            Text(item.show.ifBlank { item.name }, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (second.isNotBlank()) {
                Text(second, fontFamily = Sans, fontSize = 12.sp, color = Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                downloadStatusShort(item),
                fontFamily = Sans,
                fontSize = 12.sp,
                color = if (item.state == OfflineState.FAILED) Amber else Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!done && item.size > 0) {
                LinearProgressIndicator(
                    progress = { item.fraction },
                    color = if (item.state == OfflineState.FAILED) Amber else Signal,
                    trackColor = Hairline,
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(if (tv) 10.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tv) {
                when (item.state) {
                    OfflineState.DONE -> TagChip("▶ Play", false) { onPlay(item.asChannel()) }
                    OfflineState.RUNNING, OfflineState.QUEUED -> TagChip("Pause", false) { OfflineCenter.pause(item.id) }
                    OfflineState.PAUSED -> TagChip("Resume", false) { OfflineCenter.resume(item.id) }
                    OfflineState.FAILED -> TagChip("Try again", false) { OfflineCenter.resume(item.id) }
                }
            } else {
                // Phones: icons, so the words beside them have room.
                when (item.state) {
                    OfflineState.DONE -> IconButton(onClick = { onPlay(item.asChannel()) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Paper)
                    }
                    OfflineState.RUNNING, OfflineState.QUEUED -> IconButton(onClick = { OfflineCenter.pause(item.id) }) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_pause), contentDescription = "Pause", tint = Paper)
                    }
                    OfflineState.PAUSED -> IconButton(onClick = { OfflineCenter.resume(item.id) }) {
                        Icon(downloadIcon(), contentDescription = "Resume", tint = Amber)
                    }
                    OfflineState.FAILED -> IconButton(onClick = { OfflineCenter.resume(item.id) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Try again", tint = Amber)
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(if (done) Icons.Filled.Delete else Icons.Filled.Close, contentDescription = if (done) "Delete download" else "Cancel download", tint = Muted)
            }
        }
    }
}
