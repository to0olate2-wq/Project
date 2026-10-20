@file:OptIn(ExperimentalMaterial3Api::class)

package app.falak.player.ui

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
import app.falak.player.data.AppState
import app.falak.player.data.Channel
import app.falak.player.data.Watch
import app.falak.player.data.progressKey

@Composable
fun SeriesScreen(vm: LibraryViewModel, state: AppState, onPlay: (List<Channel>, Channel) -> Unit) {
    val p = vm.playlist ?: return
    val show = vm.series ?: return
    val info = vm.seriesInfo
    var seasonNumber by rememberSaveable(show.id) { mutableStateOf<Int?>(null) }

    BackHandler { vm.screen = Screen.LIBRARY }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.screen = Screen.LIBRARY }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                show.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        when {
            info == null && vm.seriesError == null -> Centered {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Loading episodes…")
            }
            info == null -> Centered {
                Text(vm.seriesError.orEmpty(), color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { vm.openSeries(show) }) { Text("Try again") }
            }
            info.seasons.isEmpty() -> Centered { Text("No episodes found for this series.") }
            else -> {
                val season = info.seasons.firstOrNull { it.number == seasonNumber } ?: info.seasons.first()
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    val cover = info.cover.ifBlank { show.logo }
                    if (info.plot.isNotBlank() || cover.isNotBlank()) {
                        item(key = "about") {
                            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                                if (cover.isNotBlank()) {
                                    AsyncImage(
                                        model = cover,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(96.dp, 140.dp).clip(RoundedCornerShape(10.dp)),
                                    )
                                    Spacer(Modifier.width(14.dp))
                                }
                                Column {
                                    val meta = listOfNotNull(
                                        info.year.takeIf { it.isNotBlank() },
                                        ratingLabel(info.rating.ifBlank { show.rating }),
                                        "${info.seasons.size} season" + if (info.seasons.size == 1) "" else "s",
                                    ).joinToString("  ·  ")
                                    Text(meta, style = MaterialTheme.typography.bodyMedium)
                                    if (info.genre.isNotBlank()) {
                                        Text(info.genre, style = MaterialTheme.typography.bodySmall, color = Amber)
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        info.plot,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 8,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (info.cast.isNotBlank()) {
                                        Text(
                                            "Cast: ${info.cast}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (info.seasons.size > 1) {
                        item(key = "seasons") {
                            LazyRow(
                                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(info.seasons, key = { it.number }) { s ->
                                    FilterChip(
                                        selected = s.number == season.number,
                                        onClick = { seasonNumber = s.number },
                                        label = { Text("Season ${s.number}") },
                                    )
                                }
                            }
                        }
                    }
                    items(season.episodes, key = { it.id }) { ep ->
                        EpisodeRow(ep, state.progress[progressKey(p.id, ep.id)]) { onPlay(season.episodes, ep) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(ep: Channel, watched: Watch?, onClick: () -> Unit) {
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
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(ep.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(ep.info, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (watched != null && watched.dur > 0) {
                LinearProgressIndicator(
                    progress = { (watched.pos.toFloat() / watched.dur).coerceIn(0f, 1f) },
                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp),
                )
            }
        }
    }
}
