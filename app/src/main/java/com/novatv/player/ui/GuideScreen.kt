package com.novatv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.novatv.player.data.AppState
import com.novatv.player.data.Channel
import com.novatv.player.data.Kind
import com.novatv.player.data.Program
import kotlinx.coroutines.delay
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
    val windowStart = remember { System.currentTimeMillis() / HALF_HOUR * HALF_HOUR - 2 * HALF_HOUR }
    val windowEnd = windowStart + 28 * HALF_HOUR // 14 hours
    val totalWidth = xOf(windowEnd, windowStart)
    val locked = rememberLockedGroups(vm, state)
    val group = vm.group
    val all = vm.channels
    val channels = remember(all, group, locked) {
        all.filter { it.kind == Kind.LIVE && it.group !in locked && (group == null || it.group == group) }
    }
    val hScroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(Unit) {
        val target = with(density) { xOf(System.currentTimeMillis() - HALF_HOUR, windowStart).roundToPx() }
        hScroll.scrollTo(target.coerceAtLeast(0))
    }
    val timeFormat = rememberTimeFormat()
    var details by remember { mutableStateOf<Pair<Channel, Program>?>(null) }

    BackHandler { vm.screen = Screen.LIBRARY }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.screen = Screen.LIBRARY }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                if (group != null) "TV Guide · $group" else "TV Guide",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Time ruler
        Row(Modifier.fillMaxWidth().height(26.dp)) {
            Spacer(Modifier.width(ChannelColumn + 4.dp))
            Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
                Box(Modifier.width(totalWidth).fillMaxHeight()) {
                    var t = windowStart
                    while (t < windowEnd) {
                        Text(
                            timeFormat.format(Date(t)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.offset(x = xOf(t, windowStart)).padding(start = 4.dp),
                        )
                        t += HALF_HOUR
                    }
                }
            }
        }

        if (channels.isEmpty()) {
            Centered { Text("No channels in this group.") }
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
                        onChannel = { onPlay(channels, ch) },
                        onProgram = { prog ->
                            if (now >= prog.start && now < prog.stop) onPlay(channels, ch) else details = ch to prog
                        },
                    )
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
                TextButton(onClick = { details = null; onPlay(channels, ch) }) { Text("Watch channel") }
            },
            dismissButton = { TextButton(onClick = { details = null }) { Text("Close") } },
        )
    }
}

private fun xOf(time: Long, windowStart: Long): Dp = MinuteWidth * ((time - windowStart) / 60_000L).toInt()

@Composable
private fun GuideRow(
    ch: Channel,
    programs: List<Program>?,
    windowStart: Long,
    windowEnd: Long,
    totalWidth: Dp,
    now: Long,
    hScroll: ScrollState,
    onChannel: () -> Unit,
    onProgram: (Program) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val cellShape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().height(RowHeight).padding(vertical = 2.dp)) {
        Row(
            Modifier
                .width(ChannelColumn)
                .fillMaxHeight()
                .focusRing(cellShape)
                .clip(cellShape)
                .background(colors.surfaceVariant)
                .clickable(onClick = onChannel)
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
            Text(ch.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                    key(prog.start) {
                        val start = maxOf(prog.start, windowStart)
                        val end = minOf(prog.stop, windowEnd)
                        val live = now >= prog.start && now < prog.stop
                        val shape = RoundedCornerShape(8.dp)
                        Box(
                            Modifier
                                .offset(x = xOf(start, windowStart))
                                .width(MinuteWidth * ((end - start) / 60_000L).toInt().coerceAtLeast(3))
                                .fillMaxHeight()
                                .padding(horizontal = 1.dp)
                                .focusRing(shape)
                                .clip(shape)
                                .background(
                                    if (live) colors.primary.copy(alpha = 0.30f) else colors.surfaceVariant.copy(alpha = 0.6f),
                                )
                                .clickable { onProgram(prog) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Text(
                                prog.title,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
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
                        .background(colors.primary),
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
