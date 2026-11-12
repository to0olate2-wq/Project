package app.falak.player.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.falak.player.data.Channel
import app.falak.player.data.Epg
import app.falak.player.data.GroupFolders
import app.falak.player.data.Program

/*
 * Live TV for a big playlist (8.20; the owner chose a mix of three sketches):
 *  - Folders: groups whose names start the same way ("UK | Sports", "UK | News") fold into one
 *    folder ("UK") that opens in place (data/GroupFolders.kt; Settings → Playlists turns it off).
 *  - Find a group by typing: a window with a box; any words of the name, from any folder.
 *  - Your channels first: "All channels" opens on your favorites and on what you often watch
 *    around this time, as tiles above the list.
 * With many groups (or folders) a TV's group column gets "Find a group" at its top, and where
 * the groups were a long row of chips (phones, tablets, the grid view) the row is short: Find a
 * group, All, Favorites, the group open and the ones watched lately.
 */

/** More groups than this (or folders): the finder and the short chip row come in. */
internal const val MANY_GROUPS = 12

/** The window for finding a group is open (Live TV), and what is typed in it. */
internal var groupFinderOpen by mutableStateOf(false)
internal var groupFinderTyped by mutableStateOf("")

/** One group in a list of groups: its name, how many channels (or a lock); the open one is marked. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun GroupLine(
    name: String,
    count: Int,
    selected: Boolean,
    locked: Boolean,
    onLongClick: () -> Unit,
    /** Inside a folder: set in from the edge. */
    indent: Dp = 0.dp,
    /** A second, small line (the folder it is in, where the folders aren't drawn). */
    sub: String? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .focusRing(shape)
            .clip(shape)
            .background(if (selected) Ink3 else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = if (sub == null) 10.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .padding(start = indent, end = 10.dp)
                .width(3.dp)
                .height(18.dp)
                .background(if (selected) Signal else Color.Transparent, RoundedCornerShape(2.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = Sans,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Paper else Muted,
            )
            if (sub != null) Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = Sans, fontSize = 11.sp, color = Faint)
        }
        if (locked) {
            Icon(Icons.Filled.Lock, contentDescription = "Locked", tint = Faint, modifier = Modifier.padding(end = 8.dp).size(14.dp))
        } else {
            Text("$count", style = Tabular.copy(fontSize = 12.sp), color = Faint, modifier = Modifier.padding(end = 8.dp))
        }
    }
}

/** A folder of groups: OK (or a tap) opens and closes it in place; marked when the open group is inside. */
@Composable
private fun FolderLine(name: String, groups: Int, open: Boolean, holdsPicked: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .focusRing(shape)
            .clip(shape)
            .clickable(onClickLabel = if (open) "Close folder" else "Open folder", onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = if (holdsPicked) Signal else Muted,
            modifier = Modifier.padding(end = 2.dp).size(16.dp),
        )
        Text(
            name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = Sans,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Paper,
            modifier = Modifier.weight(1f),
        )
        Text("$groups", style = Tabular.copy(fontSize = 12.sp), color = Faint, modifier = Modifier.padding(end = 8.dp))
    }
}

/** "Find a group", as the first line of a list of groups. */
@Composable
private fun FindLine(onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp).focusRing(shape).clip(shape).background(Ink2).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = Muted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text("Find a group", fontFamily = Sans, fontSize = 14.sp, color = Muted, maxLines = 1)
    }
}

/** The folders and loose groups of [tree], folders open in place. */
private fun LazyListScope.groupTree(
    tree: GroupFolders.Tree,
    groups: Map<String, List<Channel>>,
    selected: String?,
    locked: Set<String>,
    open: Set<String>,
    onToggle: (String) -> Unit,
    onSelect: (String?) -> Unit,
    onOptions: (String) -> Unit,
) {
    for (e in tree.entries) when (e) {
        is GroupFolders.Leaf -> item(key = "g:${e.group}") {
            GroupLine(e.label, groups[e.group]?.size ?: 0, selected == e.group, e.group in locked, { onOptions(e.group) }) { onSelect(e.group) }
        }
        is GroupFolders.Folder -> {
            val isOpen = e.name in open
            item(key = "f:${e.name}") {
                FolderLine(e.name, e.groups.size, isOpen, holdsPicked = e.groups.any { it.group == selected }) { onToggle(e.name) }
            }
            if (isOpen) {
                items(e.groups, key = { "g:${it.group}" }) { leaf ->
                    GroupLine(
                        leaf.label, groups[leaf.group]?.size ?: 0, selected == leaf.group, leaf.group in locked, { onOptions(leaf.group) }, indent = 14.dp,
                    ) { onSelect(leaf.group) }
                }
            }
        }
    }
}

/**
 * TV, Live TV: "All channels", your favorites and the provider's groups as a column beside the
 * channels (OK picks one; hold OK on a group for its options). With folders, OK on a folder
 * opens it in place; the folder of the group that is open starts open.
 */
@Composable
internal fun GroupColumn(
    groups: Map<String, List<Channel>>,
    tree: GroupFolders.Tree,
    total: Int,
    favorites: Int,
    selected: String?,
    locked: Set<String>,
    onSelect: (String?) -> Unit,
    onOptions: (String) -> Unit,
    onManage: () -> Unit,
    /** Many groups: "Find a group" at the top. */
    onFind: (() -> Unit)?,
    modifier: Modifier,
) {
    var open by remember(tree) { mutableStateOf(setOfNotNull(tree.folderOf(selected)?.name)) }
    LaunchedEffect(selected, tree) { tree.folderOf(selected)?.let { if (it.name !in open) open = open + it.name } }
    LazyColumn(modifier, contentPadding = PaddingValues(top = 2.dp, bottom = 32.dp)) {
        if (onFind != null) item(key = "__find") { FindLine(onFind) }
        item(key = "__all") { GroupLine("All channels", total, selected == null, false, {}) { onSelect(null) } }
        if (favorites > 0) {
            item(key = "__fav") { GroupLine("★ Favorites", favorites, selected == FAV_FILTER, false, {}) { onSelect(FAV_FILTER) } }
        }
        groupTree(tree, groups, selected, locked, open, { open = if (it in open) open - it else open + it }, onSelect, onOptions)
        item(key = "__manage") {
            Text(
                "Edit groups",
                fontFamily = Sans,
                fontSize = 13.sp,
                color = Muted,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .focusRing(RoundedCornerShape(6.dp))
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onManage() }
                    .padding(horizontal = 13.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Where the groups are chips above the list and there are many of them: a short row instead of
 * hundreds of chips. Find a group, All, Favorites, the group that is open, the ones watched lately.
 */
@Composable
internal fun GroupBar(
    groups: Map<String, List<Channel>>,
    tree: GroupFolders.Tree,
    total: Int,
    favorites: Int,
    selected: String?,
    locked: Set<String>,
    lately: List<String>,
    onSelect: (String?) -> Unit,
    onOptions: (String) -> Unit,
    onManage: () -> Unit,
    onFind: () -> Unit,
) {
    val shown = remember(selected, lately, groups) {
        (listOfNotNull(selected?.takeIf { it in groups }) + lately.filter { it in groups }).distinct()
    }
    LazyRow(
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        item(key = "__find") {
            val shape = RoundedCornerShape(10.dp)
            Row(
                Modifier.focusRing(shape).clip(shape).background(Paper).clickable(onClick = onFind).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = Ink, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("Find a group", fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Ink, maxLines = 1)
            }
        }
        item(key = "__all") { CountChip("All", total, selected == null) { onSelect(null) } }
        if (favorites > 0) {
            item(key = "__fav") { CountChip("★ Favorites", favorites, selected == FAV_FILTER) { onSelect(FAV_FILTER) } }
        }
        items(shown, key = { "g:$it" }) { g ->
            CountChip(
                if (g in locked) "🔒 ${tree.title(g)}" else tree.title(g),
                if (g in locked) null else groups[g]?.size,
                selected == g,
                onLongClick = { onOptions(g) },
            ) { onSelect(g) }
        }
        item(key = "__manage") { CountChip("Edit groups", null, false, onClick = onManage) }
    }
}

/**
 * The window for finding a group: type any part of its name ("spo" finds the sports groups of
 * every folder); with nothing typed, the groups watched lately and then all of them, folders
 * opening in place.
 */
@Composable
internal fun GroupFinderDialog(
    groups: Map<String, List<Channel>>,
    tree: GroupFolders.Tree,
    total: Int,
    favorites: Int,
    selected: String?,
    locked: Set<String>,
    lately: List<String>,
    onPick: (String?) -> Unit,
    onOptions: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val typed = groupFinderTyped
    // (Opened afresh each time; a screen test types into it before it opens.)
    DisposableEffect(Unit) { onDispose { groupFinderTyped = "" } }
    var open by remember(tree) { mutableStateOf(setOfNotNull(tree.folderOf(selected)?.name)) }
    val names = remember(groups) { groups.keys.toList() }
    val found = remember(names, typed) { GroupFolders.find(names, typed) }
    val tv = isTv()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            (if (tv) Modifier.width(440.dp) else Modifier.fillMaxWidth(0.94f))
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(16.dp))
                .background(Ink2)
                .padding(16.dp),
        ) {
            Text("Choose a group", fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = Paper)
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Ink3).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).padding(vertical = 13.dp)) {
                    if (typed.isEmpty()) Text("Type part of its name", color = Faint, fontFamily = Sans, fontSize = 14.sp, maxLines = 1)
                    BasicTextField(
                        value = typed,
                        onValueChange = { groupFinderTyped = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Paper, fontFamily = Sans, fontSize = 14.sp),
                        cursorBrush = SolidColor(Signal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (typed.isNotEmpty()) {
                    IconButton(onClick = { groupFinderTyped = "" }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Clear", tint = Muted, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
                when {
                    typed.isBlank() -> {
                        val recent = lately.filter { it in groups }
                        if (recent.isNotEmpty()) {
                            item(key = "__lately") { Box(Modifier.padding(top = 6.dp, bottom = 4.dp)) { Kicker("Lately") } }
                            items(recent, key = { "l:$it" }) { g ->
                                GroupLine(tree.label(g), groups[g]?.size ?: 0, selected == g, g in locked, { onOptions(g) }, sub = tree.folderOf(g)?.name) { onPick(g) }
                            }
                            item(key = "__every") { Box(Modifier.padding(top = 12.dp, bottom = 4.dp)) { Kicker("Every group") } }
                        }
                        item(key = "__all") { GroupLine("All channels", total, selected == null, false, {}) { onPick(null) } }
                        if (favorites > 0) {
                            item(key = "__fav") { GroupLine("★ Favorites", favorites, selected == FAV_FILTER, false, {}) { onPick(FAV_FILTER) } }
                        }
                        groupTree(tree, groups, selected, locked, open, { open = if (it in open) open - it else open + it }, onPick, onOptions)
                    }
                    found.isEmpty() -> item(key = "__none") {
                        Text("No group has “${typed.trim()}” in its name.", fontFamily = Sans, fontSize = 14.sp, color = Muted, modifier = Modifier.padding(top = 12.dp))
                    }
                    else -> {
                        item(key = "__count") {
                            Box(Modifier.padding(top = 6.dp, bottom = 4.dp)) { Kicker(if (found.size == 1) "1 group" else "${found.size} groups") }
                        }
                        items(found, key = { "r:$it" }) { g ->
                            GroupLine(tree.label(g), groups[g]?.size ?: 0, selected == g, g in locked, { onOptions(g) }, sub = tree.folderOf(g)?.name) { onPick(g) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Live TV, "All channels": your own channels first, as tiles above the list. Your favorites (two
 * rows at most), then the channels you often watch around this time (one row), each with what is
 * on now. As many across as fit: three on a phone.
 */
@Composable
internal fun YourChannels(
    favorites: List<Channel>,
    often: List<Channel>,
    epg: Map<String, List<Program>>,
    now: Long,
    /** Open a channel, with the row it is in (next and previous step through that row). */
    onOpen: (List<Channel>, Channel) -> Unit,
    onLongPress: (Channel) -> Unit,
    /** The remote moved onto a tile. */
    onFocus: ((Channel) -> Unit)?,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        val across = (maxWidth / 112.dp).toInt().coerceIn(3, 6)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (favorites.isNotEmpty()) {
                Kicker("Favorites")
                Tiles(favorites.take(across * 2), across, epg, now, { onOpen(favorites, it) }, onLongPress, onFocus)
            }
            if (often.isNotEmpty()) {
                Box(Modifier.padding(top = if (favorites.isNotEmpty()) 6.dp else 0.dp)) { Kicker("Often on at this time") }
                Tiles(often.take(across), across, epg, now, { onOpen(often, it) }, onLongPress, onFocus)
            }
            Box(Modifier.padding(top = 8.dp)) { Kicker("All channels", Muted) }
        }
    }
}

@Composable
private fun Tiles(
    list: List<Channel>,
    across: Int,
    epg: Map<String, List<Program>>,
    now: Long,
    onOpen: (Channel) -> Unit,
    onLongPress: (Channel) -> Unit,
    onFocus: ((Channel) -> Unit)?,
) {
    list.chunked(across).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { ch ->
                ChannelTile(ch, Epg.current(epg[ch.epgKey], now), now, Modifier.weight(1f), { onOpen(ch) }, { onLongPress(ch) }, onFocus)
            }
            // (A short last row keeps the tiles' size.)
            repeat(across - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** One of your channels: its logo, its name, what's on now and how far in it is. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ChannelTile(
    ch: Channel,
    current: Program?,
    now: Long,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocus: ((Channel) -> Unit)?,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .then(if (onFocus != null) Modifier.onFocusChanged { if (it.isFocused) onFocus(ch) } else Modifier)
            .focusRing(shape)
            .clip(shape)
            .background(Ink2)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(10.dp),
    ) {
        Box(Modifier.size(60.dp, 36.dp).clip(RoundedCornerShape(6.dp)).background(Ink3), contentAlignment = Alignment.Center) {
            LogoOr(ch.logo, null, ContentScale.Fit, Modifier.size(60.dp, 36.dp).padding(4.dp)) { Initials(ch.name, 13.sp) }
        }
        Spacer(Modifier.height(8.dp))
        Text(ch.name, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(current?.title.orEmpty(), fontFamily = Sans, fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val length = if (current != null) (current.stop - current.start).coerceAtLeast(1L).toFloat() else 1f
        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(2.dp).background(if (current != null) Hairline else Color.Transparent)) {
            if (current != null) Box(Modifier.fillMaxWidth(((now - current.start) / length).coerceIn(0f, 1f)).height(2.dp).background(Signal))
        }
    }
}
