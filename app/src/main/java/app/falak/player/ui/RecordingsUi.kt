package app.falak.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.OfflineCenter
import app.falak.player.data.AppState
import app.falak.player.data.Catchup
import app.falak.player.data.Channel
import app.falak.player.data.Kind
import app.falak.player.data.Program
import app.falak.player.record.RecState
import app.falak.player.record.RecordCenter
import app.falak.player.record.Recording
import app.falak.player.record.SeriesRule
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/*
 * Recordings: Record in the player, on a channel's long-press and in the TV guide (a programme,
 * or every episode of a series); Save replay for past programmes; and the Recordings page with
 * what's recording, what's planned and what's saved.
 */

/** Every recording, kept up to date. */
@Composable
fun rememberRecordings(): List<Recording> {
    val book by RecordCenter.book.collectAsState()
    return book.recordings
}

@Composable
private fun rememberRules(): List<SeriesRule> {
    val book by RecordCenter.book.collectAsState()
    return book.rules
}

/** "21:00", "Tomorrow 21:00", "Fri 7 Nov, 21:00". */
@Composable
private fun whenLabel(at: Long): String {
    val context = LocalContext.current
    val time = android.text.format.DateFormat.getTimeFormat(context).format(Date(at))
    val day = Calendar.getInstance().apply { timeInMillis = at }
    val today = Calendar.getInstance()
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        same(day, today) -> time
        same(day, tomorrow) -> "Tomorrow $time"
        same(day, yesterday) -> "Yesterday $time"
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(at)) + ", " + time
    }
}

/** What a recording is doing, in a few words. */
@Composable
fun recordingStatus(r: Recording): String = when (r.state) {
    RecState.SCHEDULED -> "Planned · " + whenLabel(r.progStart.takeIf { it > 0 } ?: r.start) +
        (if (r.ruleId.isNotEmpty()) " · series" else "")
    RecState.RECORDING -> (if (r.replay) "Saving replay" else "Recording") + " · " + OfflineCenter.sizeLabel(r.size) +
        (if (!r.replay) " · until " + whenLabel(r.stop) else "") + (if (r.note.isNotBlank()) " · ${r.note}" else "")
    RecState.DONE -> whenLabel(r.progStart.takeIf { it > 0 } ?: r.startedAt.takeIf { it > 0 } ?: r.start) + " · " +
        OfflineCenter.sizeLabel(r.size) + (if (r.note.isNotBlank()) " · ${r.note}" else "")
    RecState.FAILED -> r.note.ifBlank { "Couldn't record" }
}

/** Home: recordings saved (and being made) on this device. */
@Composable
fun RecordingsShelf(vm: LibraryViewModel, items: List<Recording>, onPlay: (List<Channel>, Channel) -> Unit) {
    val shown = items.filter { it.state == RecState.DONE || it.state == RecState.RECORDING }.asReversed()
    if (shown.isEmpty()) return
    Shelf("Recordings", onSeeAll = { vm.screen = Screen.RECORDINGS }) {
        items(shown, key = { "rec:" + it.id }) { r ->
            val ch = remember(r.id, r.state) { RecordCenter.playable(r) }
            val card = Channel(id = "rec:" + r.id, name = r.title, group = "", url = "", logo = r.logo, kind = Kind.MOVIE)
            PosterCard(card, null, onClick = { ch?.let { onPlay(listOf(it), it) } ?: run { vm.screen = Screen.RECORDINGS } })
        }
    }
}

/** Opens Android's "Alarms & reminders" page for this app (recordings start on time with it on). */
internal fun askForAlarms(context: android.content.Context) {
    if (android.os.Build.VERSION.SDK_INT < 31) return
    val pkg = android.net.Uri.parse("package:" + context.packageName)
    runCatching {
        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        runCatching {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** A planned recording and the app may not wake for it on time: say so, with the way to allow it. */
@Composable
private fun AlarmNote() {
    val context = LocalContext.current
    if (remember { RecordCenter.canWakeOnTime() }) return
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(
            "With the app closed, recordings start on time only with “Alarms & reminders” allowed for The Player.",
            fontFamily = Sans,
            fontSize = 13.sp,
            color = Amber,
        )
        Spacer(Modifier.height(6.dp))
        GhostButton("Allow alarms & reminders") { askForAlarms(context) }
    }
}

/** What's recording, planned and saved: play, stop, cancel, delete; the series being recorded. */
@Composable
fun RecordingsScreen(vm: LibraryViewModel, state: AppState, onPlay: (List<Channel>, Channel) -> Unit) {
    val all = rememberRecordings()
    val rules = rememberRules()
    val back = { vm.closePage() }
    BackHandler { back() }
    val tv = isTv()
    val shell = LocalShell.current
    var confirm by remember { mutableStateOf<Recording?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    var stopRule by remember { mutableStateOf<SeriesRule?>(null) }
    // Which kind the chips show: everything, or only what's recording, planned, saved, or the series.
    var filter by rememberSaveable { mutableStateOf(RecFilter.ALL) }
    val now = all.filter { it.state == RecState.RECORDING }
    val planned = all.filter { it.state == RecState.SCHEDULED }.sortedBy { it.start }
    val saved = all.filter { it.state == RecState.DONE || it.state == RecState.FAILED }.asReversed()
    fun shows(f: RecFilter) = filter == RecFilter.ALL || filter == f
    val play: (Recording) -> Unit = { r -> RecordCenter.playable(r)?.let { onPlay(listOf(it), it) } }
    val place = remember(state.settings.recordDir) { RecordCenter.places().firstOrNull { it.path == state.settings.recordDir }?.label ?: "This device" }
    val storage = listOfNotNull(
        place + " · " + OfflineCenter.sizeLabel(RecordCenter.freeSpace()) + " free",
        if (saved.isNotEmpty()) "${saved.count { it.state == RecState.DONE }} saved use ${OfflineCenter.sizeLabel(RecordCenter.usedSpace())}" else null,
    ).joinToString("  ·  ")
    Column(Modifier.fillMaxSize().padding(horizontal = if (shell == ShellMode.RAIL && tv) 28.dp else pageMargin())) {
        if (tv) {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Recordings", fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = Paper)
                    Text(storage, fontFamily = Sans, fontSize = 13.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Clock()
            }
        } else {
            PageHeader("Recordings", onBack = back, subtitle = storage)
        }
        // TV: the chips wrap; phones: one row that scrolls sideways.
        val chipRow: @Composable (@Composable () -> Unit) -> Unit = { chips ->
            if (tv) {
                ChipFlow(Modifier.padding(top = 6.dp, bottom = 10.dp)) { chips() }
            } else {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) { chips() }
            }
        }
        chipRow {
            TagChip("All", filter == RecFilter.ALL) { filter = RecFilter.ALL }
            if (now.isNotEmpty()) TagChip("Recording now  ${now.size}", filter == RecFilter.NOW) { filter = RecFilter.NOW }
            if (planned.isNotEmpty()) TagChip("Planned  ${planned.size}", filter == RecFilter.PLANNED) { filter = RecFilter.PLANNED }
            if (saved.isNotEmpty()) TagChip("Saved  ${saved.size}", filter == RecFilter.SAVED) { filter = RecFilter.SAVED }
            if (rules.isNotEmpty()) TagChip("Series  ${rules.size}", filter == RecFilter.SERIES) { filter = RecFilter.SERIES }
            TagChip("Recording settings", false) {
                vm.settingsFocus = SettingsPage.RECORDINGS.name
                vm.settingsPage = SettingsPage.RECORDINGS
                vm.screen = Screen.SETTINGS
            }
            if (saved.isNotEmpty()) TagChip("Delete all saved", false) { confirmAll = true }
        }
        if (planned.isNotEmpty() || rules.isNotEmpty()) AlarmNote()
        if (all.isEmpty() && rules.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Nothing recorded yet", fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = Paper)
                Spacer(Modifier.height(8.dp))
                Text(
                    "While watching a live channel, choose Record. In the TV guide, pick a programme to record it, or every episode of a series. " +
                        "Past programmes with a replay can be saved too.",
                    fontFamily = Sans,
                    fontSize = 14.sp,
                    color = Muted,
                    modifier = Modifier.widthIn(max = 520.dp),
                )
            }
            return@Column
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Saved recordings as cards: four across a TV, three on a tablet, two on a phone.
            val columns = when {
                maxWidth >= 840.dp -> 4
                maxWidth >= 560.dp -> 3
                else -> 2
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (shows(RecFilter.NOW)) {
                    items(now, key = { "now:" + it.id }) { r ->
                        RecordingNowCard(r, tv, onWatch = { play(r) }, onStop = { RecordCenter.stop(r.id) })
                    }
                }
                if (shows(RecFilter.SAVED) && saved.isNotEmpty()) {
                    item(key = "h-saved") {
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Kicker("Saved", color = Muted)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (tv) "Hold OK on one to delete it" else "Long-press one to delete it",
                                fontFamily = Sans,
                                fontSize = 12.sp,
                                color = Faint,
                            )
                        }
                    }
                    items(saved.chunked(columns), key = { row -> "saved:" + row.first().id }) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            row.forEach { r ->
                                SavedCard(
                                    r,
                                    onPlay = if (r.state == RecState.DONE) ({ play(r) }) else null,
                                    onDelete = { confirm = r },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                if (shows(RecFilter.PLANNED) && planned.isNotEmpty()) {
                    item(key = "h-planned") { Box(Modifier.padding(top = 6.dp)) { Kicker("Planned", color = Muted) } }
                    items(planned, key = { it.id }) { r ->
                        RecordingRow(r, tv, onPlay = null, onStop = { RecordCenter.cancel(r.id) }, onDelete = null)
                    }
                }
                if (shows(RecFilter.SERIES) && rules.isNotEmpty()) {
                    item(key = "h-rules") { Box(Modifier.padding(top = 6.dp)) { Kicker("Series being recorded", color = Muted) } }
                    items(rules, key = { "rule:" + it.id }) { rule ->
                        RuleRow(rule, onStop = { stopRule = rule })
                    }
                }
            }
        }
    }
    confirm?.let { r ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete recording?") },
            text = {
                Text(
                    if (r.state == RecState.RECORDING) "“${r.title}” stops recording and what's saved so far is removed."
                    else "“${r.title}” (${OfflineCenter.sizeLabel(r.size)}) will be removed from this device.",
                )
            },
            confirmButton = { TextButton(onClick = { RecordCenter.delete(r.id); confirm = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Keep") } },
        )
    }
    stopRule?.let { rule ->
        AlertDialog(
            onDismissRequest = { stopRule = null },
            title = { Text("Stop recording “${rule.title}”?") },
            text = { Text("New episodes on ${rule.channelName} won't be recorded. The ones saved stay, and planned ones are called off.") },
            confirmButton = { TextButton(onClick = { RecordCenter.removeRule(rule.id); stopRule = null }) { Text("Stop series") } },
            dismissButton = { TextButton(onClick = { stopRule = null }) { Text("Keep") } },
        )
    }
    if (confirmAll) {
        val done = saved
        ConfirmDeleteMany(
            title = "Delete all saved recordings?",
            text = "All ${done.size} (${OfflineCenter.sizeLabel(done.sumOf { it.size })}) will be removed from this device. Recordings under way and planned ones stay.",
            confirm = "Delete all",
            onConfirm = { RecordCenter.deleteMany(done.map { it.id }) },
        ) { confirmAll = false }
    }
}

/** What the Recordings page's chips show. */
private enum class RecFilter { ALL, NOW, PLANNED, SAVED, SERIES }

/** "58 min", "1 h 05". */
private fun lengthLabel(ms: Long): String {
    val minutes = (ms / 60_000L).toInt().coerceAtLeast(0)
    return if (minutes < 60) "$minutes min" else String.format(Locale.US, "%d h %02d", minutes / 60, minutes % 60)
}

/** How long a saved recording runs (as recorded; the planned times if it didn't say). */
private fun recordedLength(r: Recording): Long =
    if (r.startedAt > 0 && r.endedAt > r.startedAt) r.endedAt - r.startedAt else (r.stop - r.start).coerceAtLeast(0)

/** The channel's logo on a dark 16:9 tile (recordings have no picture of their own). */
@Composable
private fun RecordingTile(r: Recording, modifier: Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}) {
    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Ink3)) {
        AsyncImage(
            model = r.logo.ifBlank { null },
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.5f).fillMaxHeight(0.5f),
        )
        content()
    }
}

/** A recording under way: big, with Watch from the start and Stop (phones: the buttons under it). */
@Composable
private fun RecordingNowCard(r: Recording, tv: Boolean, onWatch: () -> Unit, onStop: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val time = rememberTimeFormat()
    val buttons: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PaperButton("Watch from the start", Icons.Filled.PlayArrow, onClick = onWatch)
            GhostButton("Stop", onClick = onStop)
        }
    }
    Column(Modifier.fillMaxWidth().panel(shape).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RecordingTile(r, Modifier.width(if (tv) 240.dp else 112.dp)) {
                Text(
                    "● REC",
                    fontFamily = Sans,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(if (tv) 8.dp else 5.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Signal)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.width(if (tv) 16.dp else 12.dp))
            Column(Modifier.weight(1f)) {
                Kicker(if (r.replay) "Saving replay" else "Recording now", color = Signal)
                Spacer(Modifier.height(4.dp))
                Text(r.title, fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = if (tv) 22.sp else 18.sp, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        r.channelName,
                        r.startedAt.takeIf { it > 0 }?.let { "since " + time.format(Date(it)) },
                        if (!r.replay) "until " + time.format(Date(r.stop)) else null,
                        OfflineCenter.sizeLabel(r.size) + " so far",
                    ).joinToString(" · "),
                    fontFamily = Sans,
                    fontSize = 13.sp,
                    color = Muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (tv) {
                    Spacer(Modifier.height(12.dp))
                    buttons()
                }
            }
        }
        if (!tv) {
            Spacer(Modifier.height(12.dp))
            buttons()
        }
    }
}

/** A saved recording as a card: OK (a tap) plays it; holding it deletes. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SavedCard(r: Recording, onPlay: (() -> Unit)?, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val failed = r.state == RecState.FAILED
    Column(
        modifier
            .focusRing(shape)
            .clip(shape)
            .combinedClickable(onClick = { onPlay?.invoke() ?: onDelete() }, onLongClick = onDelete)
            .padding(4.dp),
    ) {
        RecordingTile(r, Modifier.fillMaxWidth()) {
            if (r.replay) {
                CardBadge("Replay", Paper, Modifier.align(Alignment.TopStart).padding(6.dp))
            }
            if (!failed) {
                Text(
                    lengthLabel(recordedLength(r)),
                    style = Tabular.copy(fontSize = 11.sp),
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(r.title, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (failed) r.note.ifBlank { "Couldn't record" } else r.channelName + " · " + whenLabel(r.progStart.takeIf { it > 0 } ?: r.startedAt.takeIf { it > 0 } ?: r.start),
            fontFamily = Sans,
            fontSize = 12.sp,
            color = if (failed) Amber else Faint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RecordingRow(r: Recording, tv: Boolean, onPlay: (() -> Unit)?, onStop: (() -> Unit)?, onDelete: (() -> Unit)?) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .panel(shape)
            .then(if (!tv && onPlay != null) Modifier.clip(shape).clickable { onPlay() } else Modifier)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = r.logo.ifBlank { null },
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.width(72.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Ink3),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(r.channelName + if (r.replay) " · replay" else "", fontFamily = Sans, fontSize = 12.sp, color = Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                recordingStatus(r),
                fontFamily = Sans,
                fontSize = 12.sp,
                color = when (r.state) {
                    RecState.FAILED -> Amber
                    RecState.RECORDING -> Signal
                    else -> Muted
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        ChipFlow {
            if (onPlay != null) TagChip(if (r.state == RecState.RECORDING) "▶ Watch" else "▶ Play", false, onClick = onPlay)
            if (onStop != null) TagChip(if (r.state == RecState.SCHEDULED) "Cancel" else "Stop", false, onClick = onStop)
            if (onDelete != null) TagChip("Delete", false, onClick = onDelete)
        }
    }
}

@Composable
private fun RuleRow(rule: SeriesRule, onStop: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.fillMaxWidth().panel(shape).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(rule.title, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Every episode on ${rule.channelName}", fontFamily = Sans, fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TagChip("Stop series", false, onClick = onStop)
    }
}

/** A live channel's long-press: record it now (the programme on now, or for a while), or stop. */
@Composable
fun RecordChannelDialog(vm: LibraryViewModel, ch: Channel, epg: Map<String, List<Program>>, onDismiss: () -> Unit) {
    val p = vm.playlist ?: return onDismiss()
    val busy = rememberRecordings().firstOrNull { it.channelId == ch.id && it.state == RecState.RECORDING }
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val prog = remember(ch.id) { epg[ch.epgKey]?.firstOrNull { now >= it.start && now < it.stop } }
    val next = remember(ch.id) { epg[ch.epgKey]?.firstOrNull { it.start > now } }
    val late = vm.state.value.settings.recordLate.coerceIn(0, 60) * 60_000L
    fun done(r: Recording?) {
        android.widget.Toast.makeText(
            context,
            if (r == null) "This channel can't be recorded" else if (r.state == RecState.SCHEDULED) "Planned: it's in Recordings" else "Recording “${r.title}”: it's in Recordings",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (busy != null) "Recording ${ch.name}" else "Record ${ch.name}", fontFamily = Serif, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                if (busy != null) {
                    Text(recordingStatus(busy), fontFamily = Sans, fontSize = 14.sp, color = Muted)
                    Spacer(Modifier.height(10.dp))
                    ChipFlow {
                        GhostButton("Stop recording") { RecordCenter.stop(busy.id); onDismiss() }
                        GhostButton("Recordings") { onDismiss(); vm.screen = Screen.RECORDINGS }
                    }
                } else {
                    ChipFlow {
                        if (prog != null) GhostButton("“${prog.title}” now") { done(RecordCenter.recordNow(p, ch, prog, prog.stop + late)) }
                        if (next != null) GhostButton("Next: “${next.title}”") { done(RecordCenter.schedule(p, ch, next)) }
                        for ((label, minutes) in listOf("30 min" to 30, "1 hour" to 60, "2 hours" to 120)) {
                            GhostButton("For $label") { done(RecordCenter.recordNow(p, ch, null, System.currentTimeMillis() + minutes * 60_000L)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * In the TV guide's programme box: Record (coming up or on now), Record series, Save replay
 * (a past programme the provider keeps). [replayUrl]: the replay's address, for past ones.
 */
@Composable
fun GuideRecordActions(vm: LibraryViewModel, ch: Channel, prog: Program, now: Long, replayUrl: () -> String?, onDone: () -> Unit) {
    if (ch.kind != Kind.LIVE || vm.kidsActive) return
    val p = vm.playlist ?: return
    val all = rememberRecordings()
    val rules = rememberRules()
    val context = LocalContext.current
    val planned = all.firstOrNull { it.channelId == ch.id && it.progStart == prog.start && !it.replay && (it.state == RecState.SCHEDULED || it.state == RecState.RECORDING) }
    val saving = all.firstOrNull { it.channelId == ch.id && it.progStart == prog.start && it.replay }
    val rule = rules.firstOrNull { it.channelId == ch.id && app.falak.player.record.RecordRules.matches(it, prog) }
    val epg = vm.epg.value
    fun say(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    Column(Modifier.padding(top = 10.dp)) {
        ChipFlow {
            if (prog.stop > now) {
                when {
                    planned != null && planned.state == RecState.RECORDING -> TagChip("● Recording · stop", true) { RecordCenter.stop(planned.id) }
                    planned != null -> TagChip("✓ Recording planned · cancel", true) { RecordCenter.cancel(planned.id) }
                    else -> TagChip("⏺ Record", false) {
                        val r = RecordCenter.schedule(p, ch, prog)
                        say(if (r == null) "This programme can't be recorded" else if (r.state == RecState.SCHEDULED) "Planned: it's in Recordings" else "Recording now: it's in Recordings")
                    }
                }
                if (rule != null) {
                    TagChip("✓ Series · stop", true) { RecordCenter.removeRule(rule.id) }
                } else if (prog.title.isNotBlank()) {
                    TagChip("Record series", false) {
                        RecordCenter.addRule(p, ch, prog) { key -> epg[key] }
                        say("Every “${prog.title}” on ${ch.name} will be recorded")
                    }
                }
            } else if (Catchup.canReplay(ch, prog, now)) {
                when (saving?.state) {
                    RecState.RECORDING -> TagChip("Saving replay…", true) { vm.screen = Screen.RECORDINGS; onDone() }
                    RecState.DONE -> TagChip("✓ Replay saved", true) { vm.screen = Screen.RECORDINGS; onDone() }
                    else -> TagChip("Save replay", false) {
                        val url = replayUrl()
                        if (url == null) {
                            say("This programme can't be saved")
                        } else {
                            RecordCenter.saveReplay(p, ch, prog, url)
                            say("Saving the replay: it's in Recordings")
                        }
                    }
                }
            }
        }
        if (prog.start > now && (planned != null || rule != null)) AlarmNote()
    }
}
