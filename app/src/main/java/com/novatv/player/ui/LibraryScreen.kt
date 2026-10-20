@file:OptIn(ExperimentalMaterial3Api::class)

package com.novatv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.novatv.player.data.AppState
import com.novatv.player.data.Channel
import com.novatv.player.data.Epg
import com.novatv.player.data.Kind
import com.novatv.player.data.Program
import kotlinx.coroutines.delay

@Composable
fun LibraryScreen(vm: LibraryViewModel, state: AppState, onPlay: (List<Channel>, Channel) -> Unit) {
    val p = vm.playlist ?: return
    val epg by vm.epg.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val all = vm.channels
    val favIds = state.favorites[p.id].orEmpty()
    val recentIds = state.recents[p.id].orEmpty()
    val favSet = remember(favIds) { favIds.toHashSet() }
    val kinds = remember(all) { all.mapTo(HashSet()) { it.kind } }

    val sections = Section.entries.filter { s ->
        when (s) {
            Section.FAVORITES -> favIds.isNotEmpty()
            Section.RECENT -> recentIds.isNotEmpty()
            else -> s.kind != null && s.kind in kinds
        }
    }
    val section = vm.section.takeIf { it in sections } ?: sections.firstOrNull() ?: Section.LIVE
    val byId = remember(all) { all.associateBy { it.id } }
    val base = when (section) {
        Section.FAVORITES -> remember(byId, favIds) { favIds.mapNotNull { byId[it] } }
        Section.RECENT -> remember(byId, recentIds) { recentIds.mapNotNull { byId[it] } }
        else -> remember(all, section) { all.filter { it.kind == section.kind } }
    }
    val showGroups = section.kind != null
    val group = if (showGroups) vm.group else null
    val q = vm.query.trim()
    val counts = remember(base) { base.groupingBy { it.group }.eachCount() }
    val visible = remember(base, group, q) {
        base.filter { ch ->
            if (q.isNotEmpty()) ch.name.contains(q, ignoreCase = true)
            else group == null || ch.group == group
        }
    }

    var searching by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(section, group, q) { listState.scrollToItem(0) }

    BackHandler(enabled = searching || group != null) {
        if (searching) {
            searching = false
            vm.query = ""
        } else {
            vm.group = null
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // Header
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.screen = Screen.PLAYLISTS }) {
                Icon(Icons.Filled.Menu, contentDescription = "Playlists")
            }
            Text(
                p.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { searching = true }) {
                Icon(Icons.Filled.Search, contentDescription = "Search")
            }
            IconButton(onClick = { vm.open(p, refresh = true) }, enabled = !vm.loading) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh playlist")
            }
        }

        if (searching) {
            val focus = remember { FocusRequester() }
            OutlinedTextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                placeholder = { Text("Search ${section.label}") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { vm.query = ""; searching = false }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close search")
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        }

        if (vm.loading && all.isNotEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        vm.error?.takeIf { all.isNotEmpty() }?.let {
            Text("Refresh failed: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        // Sections
        if (sections.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sections.forEach { s ->
                    FilterChip(
                        selected = s == section,
                        onClick = { vm.section = s; vm.group = null },
                        label = { Text(s.label) },
                    )
                }
            }
        }

        when {
            vm.loading && all.isEmpty() -> Centered {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Loading playlist…")
            }
            vm.error != null && all.isEmpty() -> Centered {
                Text(vm.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { vm.open(p, refresh = true) }) { Text("Try again") }
            }
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 720.dp
                val groupsVisible = showGroups && q.isEmpty() && counts.size > 1
                val channelList: @Composable (Modifier) -> Unit = { m ->
                    ChannelList(
                        list = visible, favIds = favSet, epg = epg, now = now, state = listState,
                        onPlay = { ch -> onPlay(visible, ch) },
                        onFav = { ch -> vm.toggleFavorite(ch) },
                        modifier = m,
                    )
                }
                if (wide && groupsVisible) {
                    Row(Modifier.fillMaxSize()) {
                        GroupList(counts, base.size, group, { vm.group = it }, Modifier.width(260.dp).fillMaxHeight())
                        Spacer(Modifier.width(8.dp))
                        channelList(Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        if (groupsVisible) GroupChips(counts, group) { vm.group = it }
                        channelList(Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

@Composable
private fun GroupList(
    counts: Map<String, Int>,
    total: Int,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier,
) {
    val names = remember(counts) { counts.keys.toList() }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "__all") { GroupItem("All", total, selected == null) { onSelect(null) } }
        items(names, key = { "g:$it" }) { g -> GroupItem(g, counts[g] ?: 0, selected == g) { onSelect(g) } }
    }
}

@Composable
private fun GroupItem(name: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .focusRing(shape)
            .clip(shape)
            .background(if (selected) colors.primary.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) colors.primary else colors.onBackground,
            modifier = Modifier.weight(1f),
        )
        Text("$count", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun GroupChips(counts: Map<String, Int>, selected: String?, onSelect: (String?) -> Unit) {
    val names = remember(counts) { listOf<String?>(null) + counts.keys }
    LazyRow(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(names, key = { it ?: "__all" }) { g ->
            FilterChip(
                selected = selected == g,
                onClick = { onSelect(g) },
                label = { Text(g ?: "All", maxLines = 1) },
            )
        }
    }
}

@Composable
private fun ChannelList(
    list: List<Channel>,
    favIds: Set<String>,
    epg: Map<String, List<Program>>,
    now: Long,
    state: LazyListState,
    onPlay: (Channel) -> Unit,
    onFav: (Channel) -> Unit,
    modifier: Modifier,
) {
    if (list.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Nothing here", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier, state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
        itemsIndexed(list, key = { _, ch -> ch.id }) { i, ch ->
            val current = if (ch.kind == Kind.LIVE) Epg.current(epg[ch.epgKey], now) else null
            ChannelRow(i + 1, ch, current, ch.id in favIds, now, { onPlay(ch) }, { onFav(ch) })
        }
    }
}

@Composable
private fun ChannelRow(
    number: Int,
    ch: Channel,
    current: Program?,
    isFav: Boolean,
    now: Long,
    onClick: () -> Unit,
    onFav: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val colors = MaterialTheme.colorScheme
    val isVod = ch.kind != Kind.LIVE
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .focusRing(shape)
            .clip(shape)
            .background(colors.surfaceVariant.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isVod) {
            Text(
                "$number",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.width(40.dp),
            )
        }
        AsyncImage(
            model = ch.logo.ifBlank { null },
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(if (isVod) 48.dp else 56.dp, if (isVod) 72.dp else 36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.06f)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(ch.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (current != null) {
                Text(
                    current.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val length = (current.stop - current.start).coerceAtLeast(1L).toFloat()
                val frac = ((now - current.start) / length).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp),
                )
            } else if (isVod) {
                Text(
                    ch.group,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onFav) {
            Icon(
                if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (isFav) "Remove from favorites" else "Add to favorites",
                tint = if (isFav) Color(0xFFFF5C8A) else colors.onSurfaceVariant,
            )
        }
    }
}
