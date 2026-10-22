package app.falak.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.data.AppState
import app.falak.player.data.Kind

/**
 * Kids mode keeps Settings, playlists and group pages behind the PIN. Cancelling returns to the
 * kids' library, so the only way past this page is the PIN.
 */
@Composable
fun KidsLock(vm: LibraryViewModel, state: AppState) {
    val leave: () -> Unit = {
        val p = vm.playlist ?: state.playlists.firstOrNull { it.id == state.lastPlaylistId }?.also { vm.open(it) }
        if (p != null) vm.screen = Screen.LIBRARY
    }
    BackHandler { leave() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = Muted, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
            Text("Kids mode is on", fontFamily = Serif, fontSize = 22.sp, color = Paper)
            Text("A grown-up's PIN opens this page.", fontFamily = Sans, fontSize = 14.sp, color = Muted, textAlign = TextAlign.Center)
        }
    }
    PinDialog(
        title = "Enter the PIN",
        onDismiss = leave,
        onSubmit = { pin ->
            val ok = pin == state.settings.pin
            if (ok) vm.kidsUnlocked = true
            ok
        },
    )
}

/** Choose the groups kids may see in the open playlist; everything else is hidden in the Kids profile. */
@Composable
fun KidsScreen(vm: LibraryViewModel, state: AppState) {
    val p = vm.playlist
    GroupPickerPage(
        vm = vm,
        title = "Groups for kids",
        note = "The Kids profile shows only the ticked groups. Settings and the other pages need the PIN.",
        chosen = p?.let { state.settings.kidsGroups[it.id] }.orEmpty().toSet(),
        countLabel = "for kids",
        suggest = ::looksForKids,
        onSet = { groups, on -> vm.setKidsGroups(groups, allowed = on) },
    )
}

/** Choose the groups that need the PIN in the open playlist. */
@Composable
fun LocksScreen(vm: LibraryViewModel, state: AppState) {
    val p = vm.playlist
    GroupPickerPage(
        vm = vm,
        title = "Locked groups",
        note = "Ticked groups need the PIN before they open." +
            if (state.settings.lockAdult) " Adult groups are locked anyway." else "",
        chosen = p?.let { state.settings.lockedGroups[it.id] }.orEmpty().toSet(),
        countLabel = "locked",
        suggest = { app.falak.player.data.ADULT_GROUP.containsMatchIn(it) },
        onSet = { groups, on -> vm.setLockedGroups(groups, locked = on) },
        top = {
            // The adult lock lives here with the groups, not on the Settings page.
            val on = state.settings.lockAdult
            val shape = RoundedCornerShape(10.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .panel(shape)
                    .focusRing(shape)
                    .clip(shape)
                    .clickable { vm.updateSettings { it.copy(lockAdult = !it.lockAdult) } }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Lock adult groups automatically", fontFamily = Sans, fontSize = 15.sp, color = Paper)
                    Text("Groups named XXX, Adult or 18+, in every playlist", fontFamily = Sans, fontSize = 12.sp, color = Muted)
                }
                Spacer(Modifier.width(12.dp))
                androidx.compose.material3.Switch(
                    checked = on,
                    onCheckedChange = null,
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Signal,
                        checkedBorderColor = Signal,
                        uncheckedThumbColor = Muted,
                        uncheckedTrackColor = Ink3,
                        uncheckedBorderColor = Faint,
                    ),
                )
            }
        },
    )
}

/** A page of the playlist's groups with a tick each, a search box and "suggested" ones first. */
@Composable
private fun GroupPickerPage(
    vm: LibraryViewModel,
    title: String,
    note: String,
    chosen: Set<String>,
    countLabel: String,
    suggest: (String) -> Boolean,
    onSet: (Collection<String>, Boolean) -> Unit,
    top: (@Composable () -> Unit)? = null,
) {
    val p = vm.playlist
    val back: () -> Unit = { vm.screen = Screen.SETTINGS }
    BackHandler { back() }
    if (p == null) {
        Centered { Text("Open a playlist first", color = Muted) }
        return
    }
    val index = vm.index
    val kinds = remember(index) { Kind.entries.filter { index.groupsOf(it).isNotEmpty() } }
    var kind by remember { mutableStateOf(kinds.firstOrNull() ?: Kind.LIVE) }
    var query by remember { mutableStateOf("") }
    val groups = index.groupsOf(kind)
    val names = remember(groups, query) {
        // Suggested groups come first.
        groups.keys
            .filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
            .sortedByDescending { suggest(it) }
    }
    val chosenHere = groups.keys.count { it in chosen }
    val suggested = remember(groups) { groups.keys.filter { suggest(it) } }

    Column(Modifier.fillMaxSize().padding(horizontal = pageMargin())) {
        PageHeader(title, onBack = back, subtitle = p.name)
        Spacer(Modifier.height(10.dp))
        Text(note, fontFamily = Sans, fontSize = 13.sp, color = Muted)
        top?.invoke()
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            kinds.forEach { k ->
                TagChip(
                    when (k) {
                        Kind.LIVE -> "Live"
                        Kind.MOVIE -> "Films"
                        Kind.SERIES -> "Series"
                    },
                    k == kind,
                ) { kind = k }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().panel(RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Find a group", color = Faint, fontFamily = Sans, fontSize = 14.sp)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Paper, fontFamily = Sans, fontSize = 14.sp),
                    cursorBrush = SolidColor(Signal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$chosenHere of ${groups.size} $countLabel",
                style = Tabular.copy(fontSize = 13.sp),
                color = Muted,
                modifier = Modifier.weight(1f),
            )
            if (query.isBlank() && suggested.isNotEmpty()) {
                GhostButton("Suggested") { onSet(suggested, true) }
                Spacer(Modifier.width(8.dp))
            }
            GhostButton(if (query.isBlank()) "None" else "Untick these") { onSet(names, false) }
            Spacer(Modifier.width(8.dp))
            GhostButton(if (query.isBlank()) "All" else "Tick these") { onSet(names, true) }
        }
        Spacer(Modifier.height(8.dp))
        Rule()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            items(names, key = { it }) { g ->
                val on = g in chosen
                val shape = RoundedCornerShape(6.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .animateItem()
                        .focusRing(shape)
                        .clip(shape)
                        .clickable { onSet(listOf(g), !on) }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (on) Signal else Color.Transparent)
                            .border(1.5.dp, if (on) Signal else Faint, RoundedCornerShape(5.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (on) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(
                        g,
                        fontFamily = Sans,
                        fontSize = 15.sp,
                        color = if (on) Paper else Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (suggest(g)) {
                        Text("Suggested", fontFamily = Sans, fontSize = 11.sp, color = Signal, modifier = Modifier.padding(end = 10.dp))
                    }
                    Text("${groups[g]?.size ?: 0}", style = Tabular.copy(fontSize = 12.sp), color = Faint)
                }
                Rule()
            }
        }
    }
}
