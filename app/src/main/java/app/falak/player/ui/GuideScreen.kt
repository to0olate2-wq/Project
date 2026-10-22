@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.falak.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.layout
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import app.falak.player.data.AppState
import app.falak.player.data.Catchup
import app.falak.player.data.Channel
import app.falak.player.data.Kind
import app.falak.player.data.Program
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.Date

private val MinuteWidth = 4.dp
private val RowHeight = 62.dp
private val ChannelColumn = 150.dp
private const val HALF_HOUR = 30 * 60_000L

/** Timeline guide: channels down the side, programmes across, all rows scroll together. */
@Composable
fun GuideScreen(vm: LibraryViewModel, state: AppState, onPlay: (List<Channel>, Channel) -> Unit) {
    vm.playlist ?: return
    val epg by vm.epg.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    var day by remember { mutableIntStateOf(0) }
    val windowStart = remember(day) {
        if (day == 0) {
            System.currentTimeMillis() / HALF_HOUR * HALF_HOUR - 2 * HALF_HOUR
        } else {
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            cal.add(java.util.Calendar.DAY_OF_YEAR, day)
            cal.timeInMillis
        }
    }
    val windowEnd = windowStart + if (day == 0) 28 * HALF_HOUR else 48 * HALF_HOUR
    val totalWidth = xOf(windowEnd, windowStart)
    val locked = rememberLockedGroups(vm, state)
    val hidden = remember(state.settings.hiddenGroups, state.settings.kidsMode, state.settings.kidsGroups, vm.playlist?.id, vm.index) {
        vm.playlist?.let { state.settings.hiddenGroups[it.id] }.orEmpty().toHashSet().apply { addAll(vm.kidsHidden(state.settings, vm.index)) }
    }
    val group = vm.group
    val all = vm.channels
    // Only channels that actually have programmes (empty rows were just noise); worked out off
    // the main thread so the guide opens at once even with thousands of channels.
    val guidedOrNull by produceState<List<Channel>?>(null, all, locked, hidden, epg) {
        value = try {
            withContext(Dispatchers.Default) {
                val hiddenSet = hidden.toHashSet()
                all.filter { it.kind == Kind.LIVE && it.group !in locked && it.group !in hiddenSet && !epg[it.epgKey].isNullOrEmpty() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }
    val guided = guidedOrNull.orEmpty()
    val guideGroups = remember(guided) { guided.mapTo(LinkedHashSet()) { it.group }.toList() }
    val channels = remember(guided, group) { if (group == null) guided else guided.filter { it.group == group } }
    val hScroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(day) {
        val target = if (day == 0) with(density) { xOf(System.currentTimeMillis() - HALF_HOUR, windowStart).roundToPx() } else 0
        hScroll.scrollTo(target.coerceAtLeast(0))
    }
    val replayDays = remember(channels) { channels.maxOfOrNull { it.catchupDays }?.coerceAtMost(3) ?: 0 }
    val timeFormat = rememberTimeFormat()
    var details by remember { mutableStateOf<Pair<Channel, Program>?>(null) }
    // The channel playing small above the guide (picking it again opens it full screen) and the
    // programme search (null = closed). Kept in the view model so tests can set them up.
    val preview = vm.guidePreview
    val searching = vm.guideSearch != null
    val query = vm.guideSearch.orEmpty()
    DisposableEffect(Unit) { onDispose { vm.guidePreview = null } }
    val full: (List<Channel>, Channel) -> Unit = { list, ch ->
        vm.guidePreview = null
        onPlay(list, ch)
    }
    val pick: (List<Channel>, Channel) -> Unit = { list, ch ->
        if (vm.guidePreview?.id == ch.id) full(list, ch) else vm.guidePreview = ch
    }
    val hits by produceState<List<Pair<Channel, Program>>?>(null, query, guided, epg) {
        val q = query.trim()
        if (q.length < 2) {
            value = null
            return@produceState
        }
        delay(250) // typing
        value = withContext(Dispatchers.Default) { searchGuide(guided, epg, q, System.currentTimeMillis()) }
    }

    val back: () -> Unit = {
        vm.guidePreview = null
        vm.guideSearch = null
        if (!vm.leaveDetour()) vm.screen = Screen.LIBRARY
    }
    // Back stops the preview first, then closes the search, then leaves the guide.
    BackHandler {
        when {
            vm.guidePreview != null -> vm.guidePreview = null
            vm.guideSearch != null -> vm.guideSearch = null
            else -> back()
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = pageMargin())) {
        PageHeader(if (group != null) "TV Guide · $group" else "TV Guide", onBack = back) {
            IconButton(onClick = { vm.guideSearch = if (searching) null else "" }) {
                Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, contentDescription = "Search programmes", tint = Paper)
            }
            IconButton(onClick = { vm.loadGuide(force = true) }, enabled = !vm.guideLoading) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reload guide", tint = Paper)
            }
        }
        if (vm.guideLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.guideStatus.isNotEmpty()) {
            Text(vm.guideStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        preview?.let { ch ->
            GuidePreviewPane(
                ch = ch,
                userAgent = ch.userAgent.ifBlank { vm.playlist?.userAgent.orEmpty() },
                programs = epg[ch.epgKey],
                now = now,
                onOpen = { full(if (ch in channels) channels else guided, ch) },
            )
        }
        if (searching) {
            GuideSearch(
                query = query,
                onQuery = { vm.guideSearch = it },
                hits = hits,
                now = now,
                timeFormat = timeFormat,
                onHit = { ch, prog ->
                    val live = now >= prog.start && now < prog.stop
                    if (live) pick(guided, ch) else details = ch to prog
                },
                onHitLong = { ch, prog -> details = ch to prog },
            )
        } else {
            // While previewing, the picture takes the place of the day and group rows.
            if (preview == null) Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ((-replayDays)..1).forEach { d ->
                    TagChip(
                        when (d) {
                            0 -> "Today"
                            -1 -> "Yesterday"
                            1 -> "Tomorrow"
                            else -> java.text.SimpleDateFormat("EEE d", java.util.Locale.getDefault())
                                .format(Date(System.currentTimeMillis() + d * 86_400_000L))
                        },
                        d == day,
                    ) { day = d }
                }
            }

            if (guideGroups.size > 1 && preview == null) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "__all") { TagChip("All · ${guided.size}", group == null) { vm.group = null } }
                    items(guideGroups, key = { "g:$it" }) { g -> TagChip(g, group == g) { vm.group = g } }
                }
            }

            // Time ruler
            Row(Modifier.fillMaxWidth().height(26.dp)) {
                Spacer(Modifier.width(ChannelColumn + 4.dp))
                Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
                    Box(Modifier.width(totalWidth).fillMaxHeight()) {
                        var t = windowStart
                        while (t < windowEnd) {
                            val labelX = xOf(t, windowStart)
                            Text(
                                timeFormat.format(Date(t)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                // A label already scrolling out of view is hidden rather than cut in half.
                                modifier = Modifier
                                    .offset(x = labelX)
                                    .padding(start = 4.dp)
                                    .layout { measurable, constraints ->
                                        val placeable = measurable.measure(constraints)
                                        val cut = hScroll.value > labelX.roundToPx()
                                        layout(placeable.width, placeable.height) { if (!cut) placeable.place(0, 0) }
                                    },
                            )
                            t += HALF_HOUR
                        }
                    }
                }
            }

            if (channels.isEmpty()) {
                Centered {
                    when {
                        guidedOrNull == null || vm.guideLoading || (epg.isEmpty() && vm.guideStatus.isEmpty()) -> {
                            CircularProgressIndicator(color = Signal, strokeWidth = 2.dp)
                            Spacer(Modifier.height(12.dp))
                            Text("Loading the TV guide…", color = Muted)
                        }
                        guided.isNotEmpty() -> {
                            Text("No guide information for ${group.orEmpty()}.", color = Muted)
                            Spacer(Modifier.height(12.dp))
                            PaperButton("Show all channels with a guide") { vm.group = null }
                        }
                        else -> Text(
                            "No guide information yet. Add guide sources in Settings → TV guide.",
                            color = Muted,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(channels, key = { it.id }) { ch ->
                        GuideRow(
                            ch = ch,
                            programs = epg[ch.epgKey],
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                            totalWidth = totalWidth,
                            now = now,
                            hScroll = hScroll,
                            previewing = preview?.id == ch.id,
                            onChannel = { pick(channels, ch) },
                            onChannelLong = { vm.listSheetFor = ch },
                            onProgram = { prog ->
                                // On now: preview first, full screen on the second press.
                                val live = now >= prog.start && now < prog.stop
                                if (live) pick(channels, ch) else details = ch to prog
                            },
                            onProgramLong = { prog -> details = ch to prog },
                        )
                    }
                }
            }
        }
    }

    details?.let { (ch, prog) ->
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text(prog.title.ifBlank { ch.name }) },
            text = {
                Column {
                    Text(
                        "${ch.name} · ${timeFormat.format(Date(prog.start))}–${timeFormat.format(Date(prog.stop))}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (prog.desc.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(prog.desc, maxLines = 12, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            confirmButton = {
                Row {
                    if (Catchup.canReplay(ch, prog, now)) {
                        val live = now >= prog.start && now < prog.stop
                        TextButton(onClick = {
                            details = null
                            val item = vm.replay(ch, prog, now)
                            if (item != null) onPlay(listOf(item), item)
                        }) { Text(if (live) "Watch from start" else "Watch replay") }
                    }
                    TextButton(onClick = { details = null; full(if (ch in channels) channels else guided, ch) }) { Text("Watch live") }
                }
            },
            dismissButton = { TextButton(onClick = { details = null }) { Text("Close") } },
        )
    }
}

private fun xOf(time: Long, windowStart: Long): Dp = MinuteWidth * ((time - windowStart) / 60_000L).toInt()

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun GuideRow(
    ch: Channel,
    programs: List<Program>?,
    windowStart: Long,
    windowEnd: Long,
    totalWidth: Dp,
    now: Long,
    hScroll: ScrollState,
    previewing: Boolean,
    onChannel: () -> Unit,
    onChannelLong: () -> Unit,
    onProgram: (Program) -> Unit,
    onProgramLong: (Program) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val cellShape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().height(RowHeight).padding(vertical = 2.dp)) {
        Row(
            Modifier
                .width(ChannelColumn)
                .fillMaxHeight()
                .focusRing(cellShape)
                .panel(cellShape, raised = true)
                .then(if (previewing) Modifier.border(1.5.dp, Signal, cellShape) else Modifier)
                .combinedClickable(onClick = onChannel, onLongClick = onChannelLong)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = ch.logo.ifBlank { null },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(36.dp, 28.dp),
            )
            Spacer(Modifier.width(6.dp))
            Column {
                Text(ch.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (ch.hasCatchup) {
                    Text("⟲ ${ch.catchupDays}d replay", style = MaterialTheme.typography.labelSmall, color = Signal)
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
            Box(Modifier.width(totalWidth).fillMaxHeight()) {
                val inWindow = programs?.filter { it.stop > windowStart && it.start < windowEnd }.orEmpty()
                if (inWindow.isEmpty()) {
                    Text(
                        "No guide information",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.offset(x = xOf(now, windowStart)).padding(start = 8.dp, top = 18.dp),
                    )
                }
                for (prog in inWindow) {
                    key(prog.start, prog.stop, prog.title) {
                        val start = maxOf(prog.start, windowStart)
                        val end = minOf(prog.stop, windowEnd)
                        val live = now >= prog.start && now < prog.stop
                        val shape = RoundedCornerShape(8.dp)
                        val cellX = xOf(start, windowStart)
                        val cellWidth = MinuteWidth * ((end - start) / 60_000L).toInt().coerceAtLeast(3)
                        Box(
                            Modifier
                                .offset(x = cellX)
                                .width(cellWidth)
                                .fillMaxHeight()
                                .padding(horizontal = 1.dp)
                                .focusRing(shape)
                                .clip(shape)
                                .background(
                                    if (live) Signal.copy(alpha = 0.16f) else Ink2,
                                )
                                .combinedClickable(onClick = { onProgram(prog) }, onLongClick = { onProgramLong(prog) })
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Text(
                                (if (Catchup.canReplay(ch, prog, now) && !live) "⟲ " else "") + prog.title,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                // A programme that started before the visible part keeps its title in view
                                // (read while placing, so scrolling doesn't recompose the guide).
                                modifier = Modifier.layout { measurable, constraints ->
                                    // Scrolling re-places the title only (no recomposition). A sliver of a
                                    // programme too narrow to read shows no title at all.
                                    val keep = 56.dp.roundToPx()
                                    val hidden = (hScroll.value - cellX.roundToPx()).coerceAtLeast(0)
                                    val visible = constraints.maxWidth - hidden
                                    if (visible < keep) {
                                        layout(constraints.maxWidth, 0) {}
                                    } else {
                                        val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = visible))
                                        layout(constraints.maxWidth, placeable.height) { placeable.place(hidden, 0) }
                                    }
                                },
                            )
                        }
                    }
                }
                // "Now" marker
                Box(
                    Modifier
                        .offset(x = xOf(now, windowStart))
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(Signal),
                )
            }
        }
    }
}

@Composable
internal fun rememberTimeFormat(): java.text.DateFormat {
    val context = LocalContext.current
    return remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
}
