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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.data.AppState
import app.falak.player.data.Kind

/** Show or hide whole groups in one tap; hidden groups vanish from lists, Home, guide and player. */
@Composable
fun GroupsScreen(vm: LibraryViewModel, state: AppState) {
    val p = vm.playlist
    // 8.57: the offer after a playlist is added ("What do you want to see?", Done / Skip for now).
    val first = vm.groupsFirstTime
    // Opened from Settings: back there; from the lists ("Edit groups") or as the offer: back to them.
    val back: () -> Unit = {
        vm.groupsFirstTime = false
        vm.screen = if (p != null && (first || vm.settingsPage == null)) Screen.LIBRARY else Screen.SETTINGS
    }
    BackHandler { back() }
    if (p == null) {
        Centered { Text("Open a playlist first", color = Muted) }
        return
    }
    val index = vm.index
    val kinds = remember(index) { Kind.entries.filter { index.groupsOf(it).isNotEmpty() } }
    var kind by remember { mutableStateOf(kinds.firstOrNull() ?: Kind.LIVE) }
    var query by remember { mutableStateOf("") }
    val hidden = state.settings.hiddenGroups[p.id].orEmpty().toSet()
    val groups = index.groupsOf(kind)
    val names = remember(groups, query) {
        groups.keys.filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
    }
    val hiddenHere = groups.keys.count { it in hidden }
    val tv = isTv()

    Column(Modifier.fillMaxSize().padding(horizontal = pageMargin())) {
        if (first) {
            PageHeader("What do you want to see?", onBack = back, subtitle = p.name, search = false)
            Text(
                "Untick what you never watch, or hide all of Live TV, Films or Series. You can change it any time in " +
                    "Settings → Playlists & TV guide → Show & hide groups.",
                fontFamily = Sans,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = Muted,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val done = remember { androidx.compose.ui.focus.FocusRequester() }
                PaperButton("Done", modifier = Modifier.focusRequester(done)) { back() }
                GhostButton("Skip for now") { back() }
                // A TV: the remote starts on Done.
                LaunchedEffect(Unit) { if (tv) runCatching { done.requestFocus() } }
            }
        } else {
            PageHeader("Show & hide groups", onBack = back, subtitle = p.name, search = false)
        }
        Spacer(Modifier.height(10.dp))
        ChipFlow {
            kinds.forEach { k ->
                // (8.57) How many of its groups are shown, so a whole kind hidden can be seen at a glance.
                val all = index.groupsOf(k).keys
                val on = all.count { it !in hidden }
                TagChip(
                    when (k) {
                        Kind.LIVE -> "Live"
                        Kind.MOVIE -> "Films"
                        Kind.SERIES -> "Series"
                    } + if (on == 0) " · hidden" else if (on < all.size) " · $on of ${all.size}" else "",
                    k == kind,
                ) { kind = k }
            }
            // Your own order for these groups (not in the first-time offer).
            if (!first) TagChip("↕ Arrange order", false) { vm.openArrange(kind, null) }
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
                "$hiddenHere of ${groups.size} hidden",
                style = Tabular.copy(fontSize = 13.sp),
                color = Muted,
                modifier = Modifier.weight(1f),
            )
            val what = when (kind) {
                Kind.LIVE -> "Live TV"
                Kind.MOVIE -> "Films"
                Kind.SERIES -> "Series"
            }
            GhostButton(if (query.isNotBlank()) "Show these" else if (first) "Show all $what" else "Show all") { vm.setGroupsHidden(names, hidden = false) }
            Spacer(Modifier.width(8.dp))
            GhostButton(if (query.isNotBlank()) "Hide these" else if (first) "Hide all $what" else "Hide all") { vm.setGroupsHidden(names, hidden = true) }
        }
        Spacer(Modifier.height(8.dp))
        Rule()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            items(names, key = { it }) { g ->
                val visible = g !in hidden
                val shape = RoundedCornerShape(6.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .animateItem()
                        .focusRing(shape)
                        .clip(shape)
                        .clickable { vm.toggleHiddenGroup(g) }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (visible) Signal else Color.Transparent)
                            .border(1.5.dp, if (visible) Signal else Faint, RoundedCornerShape(5.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (visible) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(
                        g,
                        fontFamily = Sans,
                        fontSize = 15.sp,
                        color = if (visible) Paper else Faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${groups[g]?.size ?: 0}", style = Tabular.copy(fontSize = 12.sp), color = Faint)
                }
                Rule()
            }
        }
    }
}
