package app.falak.player.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.falak.player.data.Channel
import app.falak.player.data.Program
import app.falak.player.data.Watch
import coil.compose.AsyncImage

/** Grows slightly and shows an outline when focused with a TV remote. */
@Composable
fun Modifier.cardFocus(shape: Shape): Modifier {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "cardFocus")
    val color = MaterialTheme.colorScheme.primary
    return this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(2.5.dp, color, shape) else Modifier)
}

/** "7.4" / "7.400" → "★ 7.4"; null when there is no useful rating. */
fun ratingLabel(raw: String): String? {
    val v = raw.toDoubleOrNull() ?: return null
    if (v <= 0) return null
    return "★ " + if (v % 1.0 == 0.0) v.toInt().toString() else String.format(java.util.Locale.US, "%.1f", v)
}

private const val NEW_WINDOW_SECONDS = 7 * 24 * 3600L

fun Channel.isNew(): Boolean = added > 0 && System.currentTimeMillis() / 1000 - added < NEW_WINDOW_SECONDS

@Composable
fun PosterCard(
    ch: Channel,
    watched: Watch?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = 118.dp,
) {
    val shape = RoundedCornerShape(10.dp)
    val colors = MaterialTheme.colorScheme
    Column(if (width != null) modifier.width(width) else modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .cardFocus(shape)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(colors.surfaceVariant, colors.surface)))
                .clickable(onClick = onClick),
        ) {
            if (ch.logo.isBlank()) {
                Text(
                    ch.name,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                )
            } else {
                AsyncImage(
                    model = ch.logo,
                    contentDescription = ch.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (ch.isNew()) {
                Text(
                    "NEW",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSecondary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(colors.secondary, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            ratingLabel(ch.rating)?.let { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color(0xB3000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            if (watched != null && watched.dur > 0) {
                LinearProgressIndicator(
                    progress = { (watched.pos.toFloat() / watched.dur).coerceIn(0f, 1f) },
                    color = colors.secondary,
                    trackColor = Color(0x66000000),
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(ch.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ChannelCard(
    ch: Channel,
    current: Program?,
    now: Long,
    onClick: () -> Unit,
    width: Dp = 196.dp,
) {
    val shape = RoundedCornerShape(12.dp)
    val colors = MaterialTheme.colorScheme
    Column(Modifier.width(width)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .cardFocus(shape)
                .clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xFF2A1B7E), Color(0xFF16132A))))
                .clickable(onClick = onClick),
        ) {
            if (ch.logo.isBlank()) {
                Text(
                    ch.name,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.align(Alignment.Center).padding(10.dp),
                )
            } else {
                AsyncImage(
                    model = ch.logo,
                    contentDescription = ch.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.align(Alignment.Center).fillMaxSize(0.6f),
                )
            }
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).background(Color(0xFFFF4D4D), CircleShape))
                Spacer(Modifier.width(4.dp))
                Text("LIVE", style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
            }
            if (current != null) {
                val length = (current.stop - current.start).coerceAtLeast(1L).toFloat()
                LinearProgressIndicator(
                    progress = { ((now - current.start) / length).coerceIn(0f, 1f) },
                    color = colors.secondary,
                    trackColor = Color(0x55000000),
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(ch.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            current?.title ?: ch.group,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A titled horizontal shelf, Netflix-style. */
@Composable
fun ShelfRow(title: String, onSeeAll: (() -> Unit)? = null, content: LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (onSeeAll != null) TextButton(onClick = onSeeAll) { Text("See all") }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
            content = content,
        )
    }
}
