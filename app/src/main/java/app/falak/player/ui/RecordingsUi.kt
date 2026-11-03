package app.falak.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    val back = { vm.screen = if (vm.playlist != null) Screen.LIBRARY else Screen.PLAYLISTS }
    BackHandler { back() }
    val tv = isTv()
    var confirm by remember { mutableStateOf<Recording?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    var stopRule by remember { mutableStateOf<SeriesRule?>(null) }
    val now = all.filter { it.state == RecState.RECORDING }
    val planned = all.filter { it.state == RecState.SCHEDULED }.sortedBy { it.start }
    val saved = all.filter { it.state == RecState.DONE || it.state == RecState.FAILED }.asReversed()
    Column(Modifier.fillMaxSize().padding(horizontal = pageMargin())) {
        PageHeader("Recordings", onBack = back)
        ChipFlow(Modifier.padding(bottom = 10.dp)) {
            TagChip("Recording settings", false) {
                vm.settingsFocus = SettingsPage.RECORDINGS.name
                vm.settingsPage = SettingsPage.RECORDINGS
                vm.screen = Screen.SETTINGS
            }
            if (saved.isNotEmpty()) TagChip("Delete all saved", false) { confirmAll = true }
        }
        val place = remember(state.settings.recordDir) { RecordCenter.places().firstOrNull { it.path == state.settings.recordDir }?.label ?: "This device" }
        Text(
            listOfNotNull(
                if (saved.isNotEmpty()) "${saved.count { it.state == RecState.DONE }} saved · ${OfflineCenter.sizeLabel(RecordCenter.usedSpace())}" else null,
                "Saved to $place",
                "${OfflineCenter.sizeLabel(RecordCenter.freeSpace())} free",
            ).joinToString("  ·  "),
            fontFamily = Sans,
            fontSize = 13.sp,
            color = Muted,
            modifier = Modifier.padding(bottom = 6.dp),
        )
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
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (now.isNotEmpty()) {
                item(key = "h-now") { Kicker("Recording now", color = Signal) }
                items(now, key = { it.id }) { r ->
                    RecordingRow(r, tv, onPlay = { RecordCenter.playable(r)?.let { onPlay(listOf(it), it) } }, onStop = { RecordCenter.stop(r.id) }, onDelete = { confirm = r })
                }
            }
            if (planned.isNotEmpty()) {
                item(key = "h-planned") { Kicker("Planned", color = Muted) }
                items(planned, key = { it.id }) { r ->
                    RecordingRow(r, tv, onPlay = null, onStop = { RecordCenter.cancel(r.id) }, onDelete = null)
                }
            }
            if (rules.isNotEmpty()) {
                item(key = "h-rules") { Kicker("Series being recorded", color = Muted) }
                items(rules, key = { "rule:" + it.id }) { rule ->
                    RuleRow(rule, onStop = { stopRule = rule })
                }
            }
            if (saved.isNotEmpty()) {
                item(key = "h-saved") { Kicker("Saved", color = Muted) }
                items(saved, key = { it.id }) { r ->
                    RecordingRow(r, tv, onPlay = if (r.state == RecState.DONE) ({ RecordCenter.playable(r)?.let { onPlay(listOf(it), it) } }) else null, onStop = null, onDelete = { confirm = r })
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
