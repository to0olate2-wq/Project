package app.falak.player.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.data.AppState
import app.falak.player.data.Channel
import app.falak.player.data.Epg
import app.falak.player.data.Kind
import app.falak.player.data.Program
import app.falak.player.data.progressKey
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/** Netflix-style start page for the open playlist: live, movies and series together. */
@Composable
fun HomeContent(
    vm: LibraryViewModel,
    state: AppState,
    all: List<Channel>,
    locked: Set<String>,
    epg: Map<String, List<Program>>,
    now: Long,
    onPlay: (List<Channel>, Channel) -> Unit,
) {
    val p = vm.playlist ?: return
    val open = remember(all, locked) { if (locked.isEmpty()) all else all.filterNot { it.group in locked } }
    val byId = remember(all) { all.associateBy { it.id } }
    val favIds = state.favorites[p.id].orEmpty()
    val recentIds = state.recents[p.id].orEmpty()

    val continueWatching = remember(state.progress, byId, p.id, locked) {
        state.progress.entries.toList().asReversed()
            .filter { it.key.startsWith("${p.id}|") }
            .mapNotNull { (key, w) ->
                val id = key.substringAfter('|')
                val known = byId[id]
                when {
                    known != null -> known
                    w.url.isNotBlank() -> Channel(id = id, name = w.title, group = "", url = w.url, logo = w.image, kind = w.kind)
                    else -> null
                }
            }
            .filterNot { it.group in locked }
            .take(20)
    }
    val favLive = remember(favIds, byId, locked) {
        favIds.mapNotNull { byId[it] }.filter { it.kind == Kind.LIVE && it.group !in locked }
    }
    val favVod = remember(favIds, byId, locked) {
        favIds.mapNotNull { byId[it] }.filter { it.kind != Kind.LIVE && it.group !in locked }
    }
    val recentLive = remember(recentIds, byId, locked) {
        recentIds.mapNotNull { byId[it] }.filter { it.kind == Kind.LIVE && it.group !in locked }.take(20)
    }
    val newMovies = remember(open) { open.filter { it.kind == Kind.MOVIE && it.added > 0 }.sortedByDescending { it.added }.take(30) }
    val newSeries = remember(open) { open.filter { it.isSeriesFolder && it.added > 0 }.sortedByDescending { it.added }.take(30) }
    val liveGroups = remember(open) { open.filter { it.kind == Kind.LIVE }.groupBy { it.group }.toList().take(6) }
    val movieGroups = remember(open) {
        open.filter { it.kind == Kind.MOVIE }.groupBy { it.group }.toList().filter { it.second.size >= 3 }.take(5)
    }
    val seriesGroups = remember(open) {
        open.filter { it.kind == Kind.SERIES }.groupBy { it.group }.toList().filter { it.second.size >= 3 }.take(4)
    }
    val hero = remember(newMovies, newSeries, open) {
        (newMovies.filter { it.logo.isNotBlank() }.take(4) + newSeries.filter { it.logo.isNotBlank() }.take(2))
            .ifEmpty { open.filter { it.kind != Kind.LIVE && it.logo.isNotBlank() }.take(5) }
    }

    // Movies open their details page, series open the episode list, everything else plays.
    val openItem: (List<Channel>, Channel) -> Unit = { row, ch ->
        when {
            ch.isSeriesFolder -> vm.openSeries(ch)
            ch.kind == Kind.MOVIE && byId.containsKey(ch.id) -> vm.openDetails(ch)
            else -> onPlay(row, ch)
        }
    }
    val seeAll: (Section, String?, Sort) -> Unit = { section, group, sort ->
        vm.section = section
        vm.group = group
        vm.sort = sort
    }
    val watched = { ch: Channel -> state.progress[progressKey(p.id, ch.id)] }

    LazyColumn(Modifier.fillMaxSize(), state = vm.homeState, contentPadding = PaddingValues(bottom = 32.dp)) {
        if (hero.isNotEmpty()) {
            item(key = "hero") {
                HeroBanner(
                    items = hero,
                    onPrimary = { ch -> if (ch.isSeriesFolder) vm.openSeries(ch) else onPlay(listOf(ch), ch) },
                    onDetails = { ch -> if (ch.isSeriesFolder) vm.openSeries(ch) else vm.openDetails(ch) },
                )
            }
        }
        if (continueWatching.isNotEmpty()) {
            item(key = "continue") {
                ShelfRow("Continue watching") {
                    items(continueWatching, key = { it.id }) { ch ->
                        PosterCard(ch, watched(ch), onClick = { onPlay(listOf(ch), ch) })
                    }
                }
            }
        }
        if (favLive.isNotEmpty()) {
            item(key = "favLive") {
                LiveShelf("Your channels · on now", favLive, epg, now, onPlay, onSeeAll = { seeAll(Section.FAVORITES, null, Sort.DEFAULT) })
            }
        }
        if (recentLive.isNotEmpty()) {
            item(key = "recentLive") {
                LiveShelf("Recently watched", recentLive, epg, now, onPlay, onSeeAll = { seeAll(Section.RECENT, null, Sort.DEFAULT) })
            }
        }
        if (newMovies.isNotEmpty()) {
            item(key = "newMovies") {
                ShelfRow("New movies", onSeeAll = { seeAll(Section.MOVIES, null, Sort.NEWEST) }) {
                    items(newMovies, key = { it.id }) { ch -> PosterCard(ch, watched(ch), onClick = { openItem(newMovies, ch) }) }
                }
            }
        }
        if (newSeries.isNotEmpty()) {
            item(key = "newSeries") {
                ShelfRow("New series", onSeeAll = { seeAll(Section.SERIES, null, Sort.NEWEST) }) {
                    items(newSeries, key = { it.id }) { ch -> PosterCard(ch, null, onClick = { openItem(newSeries, ch) }) }
                }
            }
        }
        if (favVod.isNotEmpty()) {
            item(key = "favVod") {
                ShelfRow("My list", onSeeAll = { seeAll(Section.FAVORITES, null, Sort.DEFAULT) }) {
                    items(favVod, key = { it.id }) { ch -> PosterCard(ch, watched(ch), onClick = { openItem(favVod, ch) }) }
                }
            }
        }
        items(liveGroups, key = { "live:" + it.first }) { (group, list) ->
            LiveShelf(group, list.take(20), epg, now, onPlay = { _, ch -> onPlay(list, ch) }, onSeeAll = { seeAll(Section.LIVE, group, Sort.DEFAULT) })
        }
        items(movieGroups, key = { "movies:" + it.first }) { (group, list) ->
            ShelfRow(group, onSeeAll = { seeAll(Section.MOVIES, group, Sort.DEFAULT) }) {
                items(list.take(20), key = { it.id }) { ch -> PosterCard(ch, watched(ch), onClick = { openItem(list, ch) }) }
            }
        }
        items(seriesGroups, key = { "series:" + it.first }) { (group, list) ->
            ShelfRow(group, onSeeAll = { seeAll(Section.SERIES, group, Sort.DEFAULT) }) {
                items(list.take(20), key = { it.id }) { ch -> PosterCard(ch, null, onClick = { openItem(list, ch) }) }
            }
        }
    }
}

@Composable
private fun LiveShelf(
    title: String,
    list: List<Channel>,
    epg: Map<String, List<Program>>,
    now: Long,
    onPlay: (List<Channel>, Channel) -> Unit,
    onSeeAll: (() -> Unit)?,
) {
    ShelfRow(title, onSeeAll = onSeeAll) {
        items(list, key = { it.id }) { ch ->
            ChannelCard(ch, Epg.current(epg[ch.epgKey], now), now, onClick = { onPlay(list, ch) })
        }
    }
}

@Composable
private fun HeroBanner(items: List<Channel>, onPrimary: (Channel) -> Unit, onDetails: (Channel) -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(items.size, focused) {
        while (!focused && items.size > 1) {
            delay(8_000)
            index = (index + 1) % items.size
        }
    }
    val colors = MaterialTheme.colorScheme
    val ch = items[index.coerceIn(0, items.lastIndex)]
    Box(
        Modifier
            .fillMaxWidth()
            .height(250.dp)
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surfaceVariant)
            .onFocusChanged { focused = it.hasFocus },
    ) {
        Crossfade(targetState = ch, label = "hero") { item ->
            AsyncImage(
                model = item.logo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF20B0A14), Color(0x990B0A14), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC0B0A14)))))
        Row(Modifier.align(Alignment.BottomStart).padding(18.dp), verticalAlignment = Alignment.Bottom) {
            AsyncImage(
                model = ch.logo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(96.dp).height(144.dp).clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    if (ch.isNew()) (if (ch.isSeriesFolder) "NEW SERIES" else "NEW MOVIE") else "FEATURED",
                    color = colors.secondary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                )
                Text(ch.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(ratingLabel(ch.rating), ch.group.takeIf { it.isNotBlank() }).joinToString("  ·  ")
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { onPrimary(ch) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (ch.isSeriesFolder) "Episodes" else "Play")
                    }
                    if (!ch.isSeriesFolder) {
                        OutlinedButton(onClick = { onDetails(ch) }) {
                            Icon(Icons.Filled.Info, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Details")
                        }
                    }
                }
            }
        }
        if (items.size > 1) {
            Row(Modifier.align(Alignment.BottomEnd).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                items.indices.forEach { i ->
                    Box(
                        Modifier
                            .size(if (i == index) 8.dp else 6.dp)
                            .background(if (i == index) colors.secondary else Color(0x88FFFFFF), CircleShape),
                    )
                }
            }
        }
    }
}
