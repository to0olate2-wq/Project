package app.falak.player.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.data.ADULT_GROUP
import app.falak.player.repo
import app.falak.player.data.AppState
import app.falak.player.data.Channel
import app.falak.player.data.Kind
import app.falak.player.data.Program
import app.falak.player.data.Sports
import app.falak.player.data.Versions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 8.34, the owner picked them from what paid apps do: the sports hub, groups pinned to Home,
 * popular now, "Because you watched", collections and one poster per title (data/Discover.kt).
 */

/** A collection opened as a list: [COLLECTION_FILTER] + its name is the "group" shown. */
const val COLLECTION_FILTER = "\u0000collection:"

/** Screen tests: what the internet would say (titles' names: "popular-MOVIE", "popular-SERIES", "because"). */
@androidx.annotation.VisibleForTesting
internal var discoverForTest: Map<String, List<String>>? = null

/** The matches on now and later today on the sports channels you can see (every 5 minutes). */
@Composable
fun rememberSports(vm: LibraryViewModel, epg: Map<String, List<Program>>, now: Long, excluded: Set<String>): List<Sports.Match> {
    val index = vm.index
    val slot = now / 300_000
    val list by produceState(emptyList<Sports.Match>(), index, epg, slot, excluded) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val live = index.live.filter { (excluded.isEmpty() || it.group !in excluded) && !ADULT_GROUP.containsMatchIn(it.group) }
                Sports.find(live, epg, System.currentTimeMillis())
            }.getOrDefault(emptyList())
        }
    }
    return list
}

/** What a match does when chosen: on now, it plays; later, a reminder (or none). */
private fun openMatch(vm: LibraryViewModel, m: Sports.Match, now: Long, context: android.content.Context, onPlay: (List<Channel>, Channel) -> Unit) {
    val ch = m.channels.first()
    if (m.live(now)) {
        onPlay(m.channels, ch)
    } else {
        val was = vm.isReminded(ch, m.program)
        vm.toggleReminder(ch, m.program)
        Toast.makeText(context, if (was) "Reminder off" else "I'll remind you when it starts", Toast.LENGTH_SHORT).show()
    }
}

/** Home's and Live TV's row of matches; "See all" lists the day's. */
@Composable
fun SportsShelf(vm: LibraryViewModel, state: AppState, matches: List<Sports.Match>, now: Long, onPlay: (List<Channel>, Channel) -> Unit) {
    if (matches.isEmpty()) return
    val context = LocalContext.current
    var all by remember { mutableStateOf(false) }
    var choose by remember { mutableStateOf<Sports.Match?>(null) }
    fun reminded(m: Sports.Match) = state.reminders.any { r -> r.start == m.program.start && m.channels.any { it.id == r.channelId } }
    Shelf("Sports on now & today", key = "sports", onSeeAll = { all = true }) {
        items(matches.take(30), key = { "m:" + it.title + "|" + it.start }) { m ->
            MatchCard(
                m, now, reminded(m),
                onClick = { openMatch(vm, m, now, context, onPlay) },
                onLongClick = if (m.channels.size > 1) ({ choose = m }) else null,
            )
        }
    }
    if (all) {
        SportsDialog(matches, now, ::reminded, onDismiss = { all = false }) { m ->
            all = false
            if (m.channels.size > 1 && m.live(now)) choose = m else openMatch(vm, m, now, context, onPlay)
        }
    }
    choose?.let { m ->
        // A match on several channels: which one.
        AlertDialog(
            onDismissRequest = { choose = null },
            title = { Text(m.title, fontFamily = Serif, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(m.channels, key = { it.id }) { ch ->
                        Text(
                            ch.name,
                            fontFamily = Sans,
                            fontSize = 15.sp,
                            color = Paper,
                            modifier = Modifier.fillMaxWidth().clickable {
                                choose = null
                                onPlay(m.channels, ch)
                            }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choose = null }) { Text("Close") } },
        )
    }
}

/** Today's matches, on now first, then by the time they start. */
@Composable
private fun SportsDialog(
    matches: List<Sports.Match>,
    now: Long,
    reminded: (Sports.Match) -> Boolean,
    onDismiss: () -> Unit,
    onPick: (Sports.Match) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sports on now & today", fontFamily = Serif) },
        text = {
            Column {
                Text(
                    "From your TV guide. Choose one on now to watch it, or a later one to be reminded.",
                    fontFamily = Sans,
                    fontSize = 13.sp,
                    color = Muted,
                )
                Spacer(Modifier.padding(top = 8.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(matches, key = { "d:" + it.title + "|" + it.start }) { m ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(m) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (m.live(now)) "LIVE" else hourMinute(m.start),
                                fontFamily = Sans,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = if (m.live(now)) Signal else Muted,
                                modifier = Modifier.width(72.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(m.title, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Paper, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    m.channels.first().name + (if (m.channels.size > 1) "  ·  +${m.channels.size - 1} more" else "") +
                                        (if (reminded(m)) "  ·  reminder on" else ""),
                                    fontFamily = Sans,
                                    fontSize = 12.sp,
                                    color = Muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** The playlist's films or series among what is popular now (Cinemeta; English names), most popular first. */
@Composable
fun rememberPopular(vm: LibraryViewModel, kind: Kind, excluded: Set<String>): List<Channel> {
    val index = vm.index
    val repo = LocalContext.current.repo
    val list by produceState(emptyList<Channel>(), index, kind, excluded) {
        value = withContext(Dispatchers.IO) {
            val found = discoverForTest?.get("popular-" + kind.name)?.mapNotNull { n -> index.all.firstOrNull { it.name == n } }
                ?: runCatching { repo.popularIn(index, kind) }.getOrDefault(emptyList())
            found.filter { (excluded.isEmpty() || it.group !in excluded) && !ADULT_GROUP.containsMatchIn(it.group) }.take(30)
        }
    }
    return list
}

/**
 * "Because you watched …": the film or series watched last ([kind] only, when given) and the
 * playlist's titles most like it (Wikidata). Null until there's something to show.
 */
@Composable
fun rememberBecause(vm: LibraryViewModel, state: AppState, me: String, kind: Kind?, excluded: Set<String>): Pair<Channel, List<Channel>>? {
    val index = vm.index
    val repo = LocalContext.current.repo
    val source = remember(state.progress, index, me, kind) {
        state.progress.entries.asSequence()
            .filter { it.key.startsWith("$me|") && it.value.kind != Kind.LIVE }
            .sortedByDescending { it.value.at }
            .mapNotNull { (key, w) ->
                val id = key.substringAfter('|')
                index.byId[id]?.takeIf { it.kind == Kind.MOVIE }
                    ?: w.seriesId.takeIf { it.isNotEmpty() }?.let { sid -> index.series.firstOrNull { it.seriesId == sid } }
            }
            .firstOrNull { kind == null || it.kind == kind }
    }
    val result by produceState<Pair<Channel, List<Channel>>?>(null, source?.id, index, excluded) {
        val src = source ?: run {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            val found = discoverForTest?.get("because")?.mapNotNull { n -> index.all.firstOrNull { it.name == n } }
                ?: runCatching { repo.becauseYouWatched(index, src) }.getOrDefault(emptyList())
            val shown = found.filter { (excluded.isEmpty() || it.group !in excluded) && !ADULT_GROUP.containsMatchIn(it.group) }
            if (shown.isEmpty()) null else src to shown
        }
    }
    return result
}

/** "Because you watched The Matrix" (the name without the provider's tags and year). */
fun becauseTitle(ch: Channel): String = "Because you watched " + Versions.title(ch.name).first

/** A film's or series' other copies (8.34): chips under its buttons; choosing one opens it. */
@Composable
fun VersionChips(vm: LibraryViewModel, ch: Channel, onPick: (Channel) -> Unit) {
    val copies = remember(ch.id, vm.index) { vm.index.copiesOf(ch) }
    if (copies.size < 2) return
    val labels = remember(copies) { Versions.labels(copies) }
    Column(Modifier.padding(top = 14.dp)) {
        Kicker("Versions", color = Muted)
        Spacer(Modifier.padding(top = 4.dp))
        ChipFlow {
            copies.forEachIndexed { i, c ->
                FilterChip(selected = c.id == ch.id, onClick = { if (c.id != ch.id) onPick(c) }, label = { Text(labels[i]) })
            }
        }
    }
}
