package app.falak.player.ui

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
import app.falak.player.data.AppState
import app.falak.player.data.Channel
import app.falak.player.data.progressKey
import coil.compose.AsyncImage

@Composable
fun DetailsScreen(vm: LibraryViewModel, state: AppState, onPlay: (List<Channel>, Channel) -> Unit) {
    val p = vm.playlist ?: return
    val ch = vm.details ?: return
    val info = vm.vodInfo
    val watched = state.progress[progressKey(p.id, ch.id)]
    val isFav = ch.id in state.favorites[p.id].orEmpty() || state.favLists[p.id].orEmpty().any { ch.id in it.ids }
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val playFocus = remember { FocusRequester() }

    BackHandler { vm.screen = Screen.LIBRARY }
    LaunchedEffect(ch.id) { runCatching { playFocus.requestFocus() } }

    val poster = info?.poster?.takeIf { it.isNotBlank() } ?: ch.logo

    val tv = isTv()
    Box(Modifier.fillMaxSize()) {
        // The film's picture across the top, fading out into the page. Without wide artwork, the poster
        // decoded tiny and stretched: a soft wash of its colours (no ghost of its lettering).
        val top = if (tv) 420.dp else 320.dp
        val wide = info?.backdrop?.takeIf { it.isNotBlank() }
        val model: Any? = wide ?: poster.takeIf { it.isNotBlank() }?.let {
            coil.request.ImageRequest.Builder(context).data(it).size(24, 36).memoryCacheKey("$it#wash").build()
        }
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(top)
                .graphicsLayer {
                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                    alpha = if (wide != null) 0.55f else 0.45f
                }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black.copy(alpha = 0.6f), 1f to Color.Transparent),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                    )
                },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = pageMargin()),
        ) {
            Box(Modifier.padding(top = 6.dp)) {
                IconButton(onClick = { vm.screen = Screen.LIBRARY }, modifier = Modifier.offset(x = (-12).dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Paper)
                }
            }
            Spacer(Modifier.height(if (tv) 28.dp else 36.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                AsyncImage(
                    model = poster.ifBlank { null },
                    contentDescription = ch.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(if (tv) 180.dp else 120.dp)
                        .height(if (tv) 270.dp else 180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Ink3),
                )
                Spacer(Modifier.width(if (tv) 28.dp else 16.dp))
                Column(Modifier.weight(1f)) {
                    Kicker(ch.group.ifBlank { "Film" })
                    Spacer(Modifier.height(6.dp))
                    Text(
                        ch.name,
                        fontFamily = Serif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = if (tv) 36.sp else 26.sp,
                        lineHeight = if (tv) 40.sp else 30.sp,
                        color = Paper,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = listOfNotNull(
                        info?.year?.takeIf { it.isNotBlank() },
                        info?.duration?.let { formatRuntime(it) },
                        ratingLabel(info?.rating?.takeIf { it.isNotBlank() } ?: ch.rating),
                    ).joinToString("  ·  ")
                    if (meta.isNotEmpty()) {
                        Text(meta, style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.padding(top = 6.dp))
                    }
                    info?.genre?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Amber, maxLines = 2, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
            }
            if (watched != null && watched.dur > 0) {
                LinearProgressIndicator(
                    progress = { (watched.pos.toFloat() / watched.dur).coerceIn(0f, 1f) },
                    color = Signal,
                    trackColor = Hairline,
                    modifier = Modifier.padding(top = 12.dp).widthIn(max = 420.dp).fillMaxWidth().height(3.dp),
                )
            }

            Spacer(Modifier.height(22.dp))
            if (vm.detailsLoading) {
                LinearProgressIndicator(Modifier.widthIn(max = 420.dp).fillMaxWidth(), color = Signal, trackColor = Hairline)
            }
            // Readable line length on wide screens.
            Column(Modifier.widthIn(max = 760.dp)) {
                info?.plot?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = Paper)
                    Spacer(Modifier.height(16.dp))
                }
                info?.cast?.takeIf { it.isNotBlank() }?.let { Credit("Cast", it) }
                info?.director?.takeIf { it.isNotBlank() }?.let { Credit("Director", it) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Credit(label: String, value: String) {
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
