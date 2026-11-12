package app.falak.player.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import app.falak.player.data.Kind
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import app.falak.player.data.mine
import app.falak.player.data.AppState
import app.falak.player.data.Channel
import app.falak.player.data.progressKey
import coil.compose.AsyncImage

@Composable
fun DetailsScreen(vm: LibraryViewModel, state: AppState, onPlay: (List<Channel>, Channel) -> Unit) {
    val p = vm.playlist ?: return
    val ch = vm.details ?: return
    val info = vm.vodInfo
    val me = state.mine(p.id)
    val watched = state.progress[progressKey(me, ch.id)]
    val isFav = ch.id in state.favorites[me].orEmpty() || state.favLists[me].orEmpty().any { ch.id in it.ids }
    val context = LocalContext.current
    val playFocus = remember { FocusRequester() }
    val list = rememberLazyListState()
    // Other films of the same group, to carry on from here.
    val more = remember(ch.id, vm.index) {
        vm.index.groupsOf(Kind.MOVIE)[ch.group].orEmpty().asSequence().filter { it.id != ch.id }.take(24).toList()
    }

    BackHandler { vm.screen = Screen.LIBRARY }
    LaunchedEffect(ch.id) {
        // (Also when another film is opened from "More like this": back at its top, on Play.)
        list.scrollToItem(0)
        runCatching { playFocus.requestFocus() }
    }

    val poster = info?.poster?.takeIf { it.isNotBlank() } ?: ch.logo
    TitleStage(info?.backdrop, poster, ch.name, list, onBack = { vm.screen = Screen.LIBRARY }) { stage ->
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "head") {
                TitleHead(
                    stage,
                    kicker = ch.group.ifBlank { "Film" },
                    name = ch.name,
                    meta = listOfNotNull(
                        info?.year?.takeIf { it.isNotBlank() },
                        info?.duration?.let { formatRuntime(it) },
                        ratingLabel(info?.rating?.takeIf { it.isNotBlank() } ?: ch.rating),
                    ).joinToString("  ·  "),
                    genre = info?.genre,
                    plot = info?.plot,
                ) {
                    ButtonRow(stage.tv) {
                        PaperButton(
                            if (watched != null) "Resume ${formatClock(watched.pos)}" else "Play",
                            Icons.Filled.PlayArrow,
                            Modifier.focusRequester(playFocus),
                        ) { onPlay(listOf(ch), ch) }
                        if (watched != null) {
                            GhostButton("Start over", Icons.Filled.Refresh) { vm.clearProgress(ch); onPlay(listOf(ch), ch) }
                        }
                        info?.trailer?.takeIf { it.isNotBlank() }?.let { trailer ->
                            GhostButton("Trailer") {
                                val url = if (trailer.startsWith("http")) trailer else "https://www.youtube.com/watch?v=$trailer"
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }
                        }
                        GhostButton(if (isFav) "In my list" else "My list", if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder) {
                            vm.onStar(ch)
                        }
                        // Save it to watch without internet; delete the copy once it's downloaded.
                        DownloadActions(vm, ch)
                    }
                    DownloadProgress(ch)
                    if (watched != null && watched.dur > 0) {
                        LinearProgressIndicator(
                            progress = { (watched.pos.toFloat() / watched.dur).coerceIn(0f, 1f) },
                            color = Signal,
                            trackColor = Hairline,
                            modifier = Modifier.padding(top = 12.dp).widthIn(max = 420.dp).fillMaxWidth().height(3.dp),
                        )
                    }
                    if (vm.detailsLoading) BusyLine(Modifier.padding(top = 14.dp).widthIn(max = 420.dp).fillMaxWidth())
                }
            }
            val cast = info?.cast?.takeIf { it.isNotBlank() }
            val director = info?.director?.takeIf { it.isNotBlank() }
            if (cast != null || director != null) {
                item(key = "credits") {
                    Column(Modifier.padding(horizontal = pageMargin()).padding(top = 18.dp).widthIn(max = 760.dp)) {
                        cast?.let { Credit("Cast", it) }
                        director?.let { Credit("Director", it) }
                    }
                }
            }
            if (more.isNotEmpty()) {
                item(key = "more") {
                    Box(Modifier.padding(horizontal = pageMargin() - 4.dp)) {
                        Shelf("More like this") {
                            items(more, key = { it.id }) { other ->
                                PosterCard(other, state.progress[progressKey(me, other.id)], onClick = { vm.openDetails(other) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Credit(label: String, value: String) {
    Row(Modifier.padding(bottom = 6.dp)) {
        Text("$label: ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Paper)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}

/** "01:52:10" or "112" (minutes) → "1h 52m". */
internal fun formatRuntime(raw: String): String? {
    val parts = raw.trim().split(':').mapNotNull { it.toIntOrNull() }
    val minutes = when {
        parts.size >= 2 -> parts[0] * 60 + parts[1]
        parts.size == 1 -> parts[0]
        else -> return null
    }
    if (minutes <= 0) return null
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}

/** 2_052_000 ms → "34:12" (or "1:02:03"). */
internal fun formatClock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(java.util.Locale.US, "%d:%02d", m, s)
}

/** The page's buttons: one line on TV (the remote scrolls along it); on phones they wrap, so none is off the edge. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ButtonRow(tv: Boolean, content: @Composable () -> Unit) {
    if (tv) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    } else {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { content() }
    }
}
