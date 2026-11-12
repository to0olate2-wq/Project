@file:OptIn(ExperimentalMaterial3Api::class)

package app.falak.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import app.falak.player.data.mine
import app.falak.player.data.AppState
import app.falak.player.data.Channel
import app.falak.player.data.Watch
import app.falak.player.data.progressKey

@Composable
fun SeriesScreen(vm: LibraryViewModel, state: AppState, onPlay: (List<Channel>, Channel) -> Unit) {
    val p = vm.playlist ?: return
    val show = vm.series ?: return
    val info = vm.seriesInfo
    val me = state.mine(p.id)
    var seasonNumber by rememberSaveable(show.id) { mutableStateOf<Int?>(null) }
    val list = rememberLazyListState()
    val playFocus = remember { FocusRequester() }
    val tv = isTv()

    BackHandler { vm.screen = Screen.LIBRARY }

    // The player gets every episode of every season, so the next one follows across seasons.
    val all = remember(info) { info?.seasons?.flatMap { it.episodes }.orEmpty() }
    // Where to carry on: the episode watched last (the one after it when that one is as good as
    // finished), or the first.
    val last = all.mapNotNull { ep -> state.progress[progressKey(me, ep.id)]?.let { ep to it } }.maxByOrNull { it.second.at }
    val lastDone = last != null && last.second.dur > 0 && last.second.pos >= last.second.dur * 0.95
    val next = when {
        last == null -> all.firstOrNull()
        lastDone -> all.getOrNull(all.indexOf(last.first) + 1) ?: last.first
        else -> last.first
    }
    LaunchedEffect(show.id, next != null) { if (next != null) runCatching { playFocus.requestFocus() } }

    val cover = info?.cover?.takeIf { it.isNotBlank() } ?: show.logo
    TitleStage(info?.backdrop, cover, show.name, list, onBack = { vm.screen = Screen.LIBRARY }) { stage ->
        // (TV: the episodes keep to the left, the picture in view beside them.)
        val column = Modifier.padding(horizontal = pageMargin()).widthIn(max = if (stage.tv) 600.dp else 760.dp).fillMaxWidth()
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "head") {
                TitleHead(
                    stage,
                    kicker = show.group.ifBlank { "Series" },
                    name = show.name,
                    meta = listOfNotNull(
                        info?.year?.takeIf { it.isNotBlank() },
                        ratingLabel(info?.rating?.takeIf { it.isNotBlank() } ?: show.rating),
                        info?.seasons?.size?.takeIf { it > 0 }?.let { "$it season" + if (it == 1) "" else "s" },
                    ).joinToString("  ·  "),
                    genre = info?.genre,
                    plot = info?.plot,
                ) {
                    ButtonRow(stage.tv) {
                        if (next != null) {
                            PaperButton(
                                if (last != null && !lastDone) "Resume" else if (last != null) "Next episode" else "Play",
                                Icons.Filled.PlayArrow,
                                Modifier.focusRequester(playFocus),
                            ) { onPlay(all, next) }
                        }
                        // Follow: What's new (and a notification) when new episodes come out.
                        if (show.seriesId.isNotEmpty()) {
                            val following = state.followed[p.id].orEmpty().any { it.seriesId == show.seriesId }
                            GhostButton(if (following) "Following" else "Follow", if (following) Icons.Filled.Check else Icons.Filled.Add) {
                                vm.toggleFollow(show, all.size)
                            }
                        }
                        if (vm.index.byId.containsKey(show.id)) {
                            val isFav = show.id in state.favorites[me].orEmpty() || state.favLists[me].orEmpty().any { show.id in it.ids }
                            GhostButton(if (isFav) "In my list" else "My list", if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder) {
                                vm.onStar(show)
                            }
                        }
                    }
                    // Which episode the button plays.
                    if (next != null && last != null) {
                        Text(next.name, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            info?.cast?.takeIf { it.isNotBlank() }?.let { cast ->
                item(key = "credits") {
                    Column(Modifier.padding(horizontal = pageMargin()).padding(top = 16.dp).widthIn(max = 760.dp)) { Credit("Cast", cast) }
                }
            }
            when {
                info == null && vm.seriesError == null -> item(key = "loading") {
                    Column(column.padding(top = 22.dp)) {
                        Text("Loading episodes…", style = MaterialTheme.typography.bodyMedium, color = Muted)
                        BusyLine(Modifier.padding(top = 10.dp).widthIn(max = 420.dp).fillMaxWidth())
                    }
                }
                info == null -> item(key = "error") {
                    Column(column.padding(top = 22.dp)) {
                        Text(vm.seriesError.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        GhostButton("Try again", Icons.Filled.Refresh) { vm.openSeries(show) }
                    }
                }
                info.seasons.isEmpty() -> item(key = "none") {
                    Text("No episodes found for this series.", style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = column.padding(top = 22.dp))
                }
                else -> {
                    val season = info.seasons.firstOrNull { it.number == seasonNumber }
                        // Open on the season of the episode to carry on with.
                        ?: info.seasons.firstOrNull { s -> next != null && s.episodes.any { it.id == next.id } }
                        ?: info.seasons.first()
                    if (info.seasons.size > 1) {
                        item(key = "seasons") {
                            LazyRow(
                                Modifier.fillMaxWidth().padding(top = 18.dp),
                                contentPadding = PaddingValues(horizontal = pageMargin()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(info.seasons, key = { it.number }) { s ->
                                    TagChip("Season ${s.number}", s.number == season.number) { seasonNumber = s.number }
                                }
                            }
                        }
                    }
                    // Save the whole season to watch without internet.
                    item(key = "download") {
                        ChipFlow(column.padding(top = 10.dp, bottom = 8.dp)) {
                            SeasonDownload(vm, season.episodes)
                            SeasonDelete(season.episodes)
                        }
                    }
                    items(season.episodes, key = { it.id }) { ep ->
                        Box(column) {
                            EpisodeRow(vm, ep, state.progress[progressKey(me, ep.id)], upNext = last != null && ep.id == next?.id) { onPlay(all, ep) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(vm: LibraryViewModel, ep: Channel, watched: Watch?, upNext: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .focusRing(shape)
            .panel(shape)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ep.logo.isNotBlank()) {
            AsyncImage(
                model = ep.logo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(96.dp, 54.dp).clip(RoundedCornerShape(6.dp))
                    .background(Ink3),
            )
        } else {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Muted, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // The one the page's button plays.
            if (upNext) Kicker("Up next")
            Text(ep.name, style = MaterialTheme.typography.bodyLarge, color = Paper, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (ep.info.isNotBlank()) Text(ep.info, style = MaterialTheme.typography.bodySmall, color = Muted)
            if (watched != null && watched.dur > 0) {
                LinearProgressIndicator(
                    progress = { (watched.pos.toFloat() / watched.dur).coerceIn(0f, 1f) },
                    color = Signal,
                    trackColor = Hairline,
                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp),
                )
            }
            // Its download, when there is one: how far, or how big the saved copy is.
            rememberDownloads().firstOrNull { it.id == ep.id }?.let { d ->
                Text(
                    downloadStatusShort(d),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (d.state == app.falak.player.data.OfflineState.FAILED) Amber else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        // Download, cancel, or delete the saved copy.
        EpisodeDownload(vm, ep)
    }
}
