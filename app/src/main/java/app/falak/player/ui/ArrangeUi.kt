package app.falak.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.data.AppState
import app.falak.player.data.Channel
import app.falak.player.data.Kind
import kotlinx.coroutines.launch

/*
 * Your own order: groups and the channels (films, series) in them. "To top", up and down from a
 * long-press, or the Arrange page for a whole list. What you move comes first; the rest, and
 * anything new from your provider, follows in the playlist's own order.
 */

private fun kindWord(kind: Kind, plural: Boolean = true) = when (kind) {
    Kind.LIVE -> if (plural) "channels" else "channel"
    Kind.MOVIE -> if (plural) "films" else "film"
    Kind.SERIES -> "series"
}

/** Long-press on a group: its place in the list, arranging, hiding. */
@Composable
fun GroupMenu(vm: LibraryViewModel, kind: Kind?, group: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(group, fontFamily = Serif, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                if (kind != null) {
                    // Where it is now: changes as soon as it moves.
                    val order = vm.groupOrder(kind)
                    val at = order.indexOf(group)
                    Kicker(if (at >= 0) "Order · ${at + 1} of ${order.size}" else "Order", color = Muted)
                    Spacer(Modifier.height(6.dp))
                    ChipFlow {
                        GhostButton("To top") { vm.moveGroup(kind, group, 0, toTop = true) }
                        GhostButton("Up") { vm.moveGroup(kind, group, -1) }
                        GhostButton("Down") { vm.moveGroup(kind, group, 1) }
                    }
                    Spacer(Modifier.height(8.dp))
                    ChipFlow {
                        GhostButton("Arrange groups…") {
                            onDismiss()
                            vm.openArrange(kind, null)
                        }
                        GhostButton("Arrange ${kindWord(kind)}…") {
                            onDismiss()
                            vm.openArrange(kind, group)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                Text(
                    "Hidden groups disappear from lists, Home, the guide and the player. You can bring them back in Show & hide groups.",
                    fontFamily = Sans,
                    fontSize = 13.sp,
                    color = Muted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.toggleHiddenGroup(group)
                onDismiss()
            }) { Text("Hide group") }
        },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
                vm.screen = Screen.GROUPS
            }) { Text("Show & hide groups…") }
        },
    )
}

/** In a channel's long-press sheet: its place in its group. */
@Composable
fun OrderInGroup(vm: LibraryViewModel, ch: Channel, onDismiss: () -> Unit) {
    if (ch.group.isBlank() || vm.kidsActive) return
    val list = vm.index.groupsOf(ch.kind)[ch.group].orEmpty()
    // Where it is now: changes as soon as it moves.
    val at = list.indexOfFirst { it.id == ch.id }
    if (at < 0) return
    Spacer(Modifier.height(10.dp))
    Kicker("Order in “${ch.group}” · ${at + 1} of ${list.size}", color = Muted)
    Spacer(Modifier.height(6.dp))
    ChipFlow {
        GhostButton("To top") { vm.moveChannel(ch, 0, toTop = true) }
        GhostButton("Up") { vm.moveChannel(ch, -1) }
        GhostButton("Down") { vm.moveChannel(ch, 1) }
        GhostButton("Arrange…") {
            onDismiss()
            vm.openArrange(ch.kind, ch.group)
        }
    }
}

/** A whole list in your own order: the groups of Live, Films or Series, or one group's channels. */
@Composable
fun ArrangeScreen(vm: LibraryViewModel, state: AppState) {
    val back: () -> Unit = { vm.screen = vm.arrangeBack }
    BackHandler { back() }
    val p = vm.playlist
    val target = vm.arrangeTarget
    if (p == null || target == null) {
        Centered { Text("Open a playlist first", color = Muted) }
        return
    }
    val kind = target.kind
    val group = target.group
    // (id, name) in the order now; moving one rebuilds the lists, and this follows.
    val items: List<Pair<String, String>> = if (group == null) {
        vm.groupOrder(kind).map { it to it }
    } else {
        vm.index.groupsOf(kind)[group].orEmpty().map { it.id to it.name }
    }
    val arranged = vm.isArranged(kind, group)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val section = when (kind) {
        Kind.LIVE -> "Live"
        Kind.MOVIE -> "Films"
        Kind.SERIES -> "Series"
    }

    Column(Modifier.fillMaxSize().padding(horizontal = pageMargin())) {
        PageHeader(
            if (group == null) "Arrange groups" else "Arrange “$group”",
            onBack = back,
            subtitle = "$section · ${p.name}",
        )
        Text(
            if (group == null) {
                "Move groups up or down, or to the top. The rest, and any new groups, follow in your playlist's order."
            } else {
                "Move ${kindWord(kind)} up or down, or to the top. The rest, and any new ones from your provider, follow in your playlist's order."
            },
            fontFamily = Sans,
            fontSize = 13.sp,
            color = Muted,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )
        ChipFlow(Modifier.padding(bottom = 8.dp)) {
            if (group != null) {
                TagChip("All groups", false) { vm.openArrange(kind, null) }
            }
            if (arranged) TagChip("Back to playlist order", false) { vm.resetOrder(kind, group) }
        }
        Rule()
        if (items.isEmpty()) {
            Centered { Text("Nothing to arrange here", color = Muted) }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
            itemsIndexed(items, key = { _, it -> it.first }) { i, (id, name) ->
                val shape = RoundedCornerShape(6.dp)
                fun move(delta: Int, top: Boolean = false) {
                    val at = if (group == null) {
                        vm.moveGroup(kind, id, delta, toTop = top)
                    } else {
                        vm.index.groupsOf(kind)[group]?.firstOrNull { it.id == id }?.let { vm.moveChannel(it, delta, toTop = top) } ?: -1
                    }
                    if (at < 0) return
                    // Keep the moved row on screen (the remote stays on it).
                    val visible = listState.layoutInfo.visibleItemsInfo
                    val first = visible.firstOrNull()?.index ?: 0
                    val last = visible.lastOrNull()?.index ?: 0
                    when {
                        at <= first -> scope.launch { listState.animateScrollToItem((at - 1).coerceAtLeast(0)) }
                        at >= last -> scope.launch { listState.animateScrollToItem((at - visible.size + 3).coerceAtLeast(0)) }
                    }
                }
                Row(
                    // Rows slide when moved, and show at once (no fade) when the list changes.
                    Modifier.fillMaxWidth().animateItem(fadeInSpec = null, fadeOutSpec = null).padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${i + 1}",
                        style = Tabular.copy(fontSize = 12.sp),
                        color = Faint,
                        modifier = Modifier.width(40.dp),
                    )
                    Text(
                        name,
                        fontFamily = Sans,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        color = Paper,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Always there and always reachable (at the top or bottom they just do nothing), so the
                        // remote never loses its place when a row arrives at either end.
                        TagChip("To top", false) { if (i > 0) move(0, top = true) }
                        IconButton(onClick = { if (i > 0) move(-1) }, modifier = Modifier.focusRing(shape)) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Up", tint = if (i > 0) Paper else Faint)
                        }
                        IconButton(onClick = { if (i < items.lastIndex) move(1) }, modifier = Modifier.focusRing(shape)) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Down", tint = if (i < items.lastIndex) Paper else Faint)
                        }
                    }
                }
            }
        }
    }
}
