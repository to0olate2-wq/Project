package app.falak.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.data.mine
import app.falak.player.data.AppState
import app.falak.player.data.Channel
import app.falak.player.data.DEFAULT_LIST
import app.falak.player.data.FavList
import app.falak.player.data.GuideChannel
import app.falak.player.data.Kind
import app.falak.player.data.countryAgreement
import app.falak.player.data.countryOf
import app.falak.player.data.guideCountry
import app.falak.player.data.nameWords
import app.falak.player.data.normName

/** Long-press / star sheet: tick the lists an item belongs to, reorder it, fix its guide. */
@Composable
fun ItemSheet(vm: LibraryViewModel, state: AppState, ch: Channel, orderInList: String?, onDismiss: () -> Unit) {
    val p = vm.playlist ?: return
    val lists = state.favLists[state.mine(p.id)].orEmpty()
    val favIds = state.favorites[state.mine(p.id)].orEmpty()
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ch.name, fontFamily = Serif, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Kicker("Save to", color = Muted)
                Spacer(Modifier.height(4.dp))
                CheckLine("Favorites", ch.id in favIds) { on -> vm.setInList(DEFAULT_LIST, ch, on) }
                lists.forEach { l -> CheckLine(l.name, ch.id in l.ids) { on -> vm.setInList(l.id, ch, on) } }
                if (creating) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        InlineField(newName, "List name, e.g. Sports", Modifier.weight(1f)) { newName = it }
                        TextButton(enabled = newName.isNotBlank(), onClick = {
                            vm.createList(newName, ch)
                            newName = ""
                            creating = false
                        }) { Text("Create") }
                    }
                } else {
                    Text(
                        "＋  New list",
                        fontFamily = Sans,
                        fontSize = 15.sp,
                        color = Signal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRing(RoundedCornerShape(6.dp))
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { creating = true }
                            .padding(vertical = 11.dp, horizontal = 4.dp),
                    )
                }
                if (orderInList != null) {
                    Spacer(Modifier.height(10.dp))
                    Kicker("Order in this list", color = Muted)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("To top") { vm.moveInList(orderInList, ch, 0, toTop = true) }
                        GhostButton("Up") { vm.moveInList(orderInList, ch, -1) }
                        GhostButton("Down") { vm.moveInList(orderInList, ch, 1) }
                    }
                }
                if (ch.kind == Kind.LIVE) {
                    Spacer(Modifier.height(10.dp))
                    Rule()
                    Text(
                        "Fix TV guide…",
                        fontFamily = Sans,
                        fontSize = 15.sp,
                        color = Paper,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRing(RoundedCornerShape(6.dp))
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                onDismiss()
                                vm.guideFixFor = ch
                            }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun CheckLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .focusRing(shape)
            .clip(shape)
            .clickable { onChange(!checked) }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (checked) Signal else Color.Transparent)
                .border(1.5.dp, if (checked) Signal else Faint, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(label, fontFamily = Sans, fontSize = 15.sp, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun InlineField(value: String, hint: String, modifier: Modifier = Modifier, autoFocus: Boolean = true, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(modifier.panel(RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (value.isEmpty()) Text(hint, color = Faint, fontFamily = Sans, fontSize = 14.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Paper, fontFamily = Sans, fontSize = 14.sp),
            cursorBrush = SolidColor(Signal),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
}

/** Chips across the top of Favorites: Favorites, your lists, and "New list". Long-press a list to edit it. */
@Composable
fun ListChips(lists: List<FavList>, favCount: Int, selected: String, onSelect: (String) -> Unit, onEdit: (FavList) -> Unit, onNew: () -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = DEFAULT_LIST) { TagChip("Favorites · $favCount", selected == DEFAULT_LIST) { onSelect(DEFAULT_LIST) } }
        items(lists, key = { it.id }) { l ->
            TagChip("${l.name} · ${l.ids.size}", selected == l.id, onLongClick = { onEdit(l) }) {
                if (selected == l.id) onEdit(l) else onSelect(l.id)
            }
        }
        item(key = "__new") { TagChip("＋ New list", false, onClick = onNew) }
    }
}

/** Rename, move or delete one of your lists. */
@Composable
fun ListEditDialog(vm: LibraryViewModel, list: FavList, onDismiss: () -> Unit) {
    var name by remember(list.id) { mutableStateOf(list.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (confirmDelete) "Delete “${list.name}”?" else "Edit list", fontFamily = Serif) },
        text = {
            if (confirmDelete) {
                Text("The list goes away; the channels and videos in it stay in your playlist.", color = Muted)
            } else {
                Column {
                    InlineField(name, "List name", autoFocus = false) { name = it }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("◀ Move left") { vm.moveList(list.id, -1) }
                        GhostButton("Move right ▶") { vm.moveList(list.id, 1) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Delete list",
                        color = Signal,
                        fontFamily = Sans,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .focusRing(RoundedCornerShape(6.dp))
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { confirmDelete = true }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                    )
                    Text(
                        "To reorder what's inside, long-press an item in the list.",
                        color = Faint,
                        fontSize = 12.sp, lineHeight = 17.sp,
                    )
                }
            }
        },
        confirmButton = {
            if (confirmDelete) {
                TextButton(onClick = { vm.deleteList(list.id); onDismiss() }) { Text("Delete") }
            } else {
                TextButton(onClick = { vm.renameList(list.id, name); onDismiss() }) { Text("Save") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (confirmDelete) confirmDelete = false else onDismiss() }) { Text("Cancel") }
        },
    )
}

@Composable
fun NewListDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New list", fontFamily = Serif) },
        text = {
            Column {
                InlineField(name, "e.g. Sports, Kids, News") { name = it }
                Spacer(Modifier.height(8.dp))
                Text("Add to it from any channel, film or series: long-press it, or tap its star.", color = Faint, fontSize = 12.sp, lineHeight = 17.sp)
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick the right guide channel for a playlist channel, from every guide source loaded. */
@Composable
fun GuideFixDialog(vm: LibraryViewModel, ch: Channel, onDismiss: () -> Unit) {
    val all = remember { vm.guideChoices() }
    var query by remember { mutableStateOf(nameWords(ch.name).joinToString(" ")) }
    val country = remember(ch) { countryOf(ch.name, ch.group) }
    // Normalised once; filtering then stays quick on thousands of guide channels.
    val prepared = remember(all) { all.map { it to normName(it.name) } }
    val matches = remember(prepared, query) {
        val q = normName(query)
        val ql = query.trim().lowercase()
        if (q.isEmpty() && ql.isEmpty()) return@remember emptyList<GuideChannel>()
        prepared.asSequence()
            .filter { (g, n) -> (q.isNotEmpty() && n.contains(q)) || (ql.length >= 3 && g.id.lowercase().contains(ql)) }
            .sortedWith(
                compareByDescending<Pair<GuideChannel, String>> { (_, n) -> n == q }
                    .thenByDescending { (g, _) -> countryAgreement(country, guideCountry(g.id)) }
                    .thenByDescending { (_, n) -> n.startsWith(q) }
                    .thenBy { (_, n) -> n.length },
            )
            .map { it.first }
            .distinctBy { it.source + "|" + it.id }
            .take(80)
            .toList()
    }
    val current = vm.guideOverrideFor(ch)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("TV guide for ${ch.name}", fontFamily = Serif, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text(
                    when (current) {
                        null -> "Now: matched automatically"
                        "" -> "Now: no guide (hidden by you)"
                        else -> "Now: you picked “${all.firstOrNull { it.id == current }?.name ?: current}”"
                    },
                    color = Muted,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                if (all.isEmpty()) {
                    Text(
                        "No guide is loaded yet. Wait for the guide to finish loading, or add guide sources in Settings → TV guide.",
                        color = Faint,
                    )
                } else {
                    InlineField(query, "Search guide channels", autoFocus = false) { query = it }
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(Modifier.heightIn(max = 320.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                        if (matches.isEmpty()) {
                            item { Text("No guide channel matches “$query”.", color = Faint, modifier = Modifier.padding(8.dp)) }
                        }
                        items(matches, key = { it.source + "|" + it.id }) { g ->
                            val shape = RoundedCornerShape(6.dp)
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .focusRing(shape)
                                    .clip(shape)
                                    .background(if (g.id == current) Signal.copy(alpha = 0.14f) else Color.Transparent)
                                    .clickable {
                                        vm.setGuideFor(ch, g.id)
                                        onDismiss()
                                    }
                                    .padding(vertical = 9.dp, horizontal = 6.dp),
                            ) {
                                Text(g.name, fontFamily = Sans, fontSize = 15.sp, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${g.id}  ·  ${g.source}", fontFamily = Sans, fontSize = 11.sp, color = Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (current != null) TextButton(onClick = { vm.setGuideFor(ch, null); onDismiss() }) { Text("Automatic") }
                TextButton(onClick = { vm.setGuideFor(ch, ""); onDismiss() }) { Text("No guide") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
