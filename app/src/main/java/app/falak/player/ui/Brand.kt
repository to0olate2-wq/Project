package app.falak.player.ui

import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.R
import kotlinx.coroutines.delay
import java.util.Date

// ---- palette ----
val Night = Color(0xFF0B0A14)
val Violet = Color(0xFF3B2AA8)
val Teal = Color(0xFF0C7A8C)
val Gold = Color(0xFFFFC857)
val GoldDeep = Color(0xFFFFA62B)
val GoldBrush = Brush.linearGradient(listOf(Color(0xFFFFDB8A), GoldDeep))
val OrbitBrush = Brush.linearGradient(listOf(Color(0xFF9D8CFF), Gold))

/** True on Android TV / Google TV (lean-back) devices. */
@Composable
fun isTv(): Boolean =
    (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

/**
 * Falak's signature backdrop: a slow "aurora" of violet, teal and gold light drifting over
 * deep night. Still on TVs (saves the box's GPU for video), gently animated on phones.
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val animate = !isTv()
    val transition = rememberInfiniteTransition(label = "aurora")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (animate) 1f else 0f,
        animationSpec = infiniteRepeatable(tween(30_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )
    Canvas(modifier.fillMaxSize()) {
        drawRect(Night)
        val w = size.width
        val h = size.height
        val r = maxOf(w, h)
        fun glow(x: Float, y: Float, color: Color, radius: Float) {
            drawCircle(
                brush = Brush.radialGradient(listOf(color, Color.Transparent), center = Offset(x, y), radius = radius),
                radius = radius,
                center = Offset(x, y),
            )
        }
        glow(w * (0.10f + 0.25f * drift), h * (0.05f + 0.12f * drift), Violet.copy(alpha = 0.45f), r * 0.75f)
        glow(w * (0.95f - 0.30f * drift), h * (0.40f + 0.15f * drift), Teal.copy(alpha = 0.28f), r * 0.6f)
        glow(w * (0.55f + 0.15f * drift), h * (1.0f - 0.12f * drift), Gold.copy(alpha = 0.10f), r * 0.5f)
        // faint orbit ring, the brand motif
        drawOval(
            color = Color.White.copy(alpha = 0.035f),
            topLeft = Offset(-w * 0.2f, h * 0.18f),
            size = androidx.compose.ui.geometry.Size(w * 1.4f, h * 0.5f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
        )
    }
}

/** Frosted-glass panel used for rows, lists and cards. */
fun Modifier.glass(shape: Shape, alpha: Float = 0.06f): Modifier = this
    .clip(shape)
    .background(Color.White.copy(alpha = alpha))
    .border(1.dp, Color.White.copy(alpha = 0.08f), shape)

/** The orbit mark (launcher foreground) on its gradient badge. */
@Composable
fun FalakMark(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.linearGradient(listOf(Color(0xFF2A1B7E), Violet, Teal))),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = "Falak",
            modifier = Modifier.size(size * 1.45f),
        )
    }
}

/** Lower-case "falak" wordmark with the violet→gold orbit gradient. */
@Composable
fun Wordmark(fontSize: Int = 24) {
    Text(
        "falak",
        style = TextStyle(
            brush = OrbitBrush,
            fontFamily = Poppins,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize.sp,
            letterSpacing = (-0.5).sp,
        ),
    )
}

/** Small live clock, like a TV channel bug. */
@Composable
fun Clock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val format = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }
    Text(
        format.format(Date(now)),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

fun Section.icon(): ImageVector = when (this) {
    Section.HOME -> Icons.Filled.Home
    Section.LIVE -> Icons.Filled.PlayArrow
    Section.MOVIES -> Icons.Filled.Star
    Section.SERIES -> Icons.AutoMirrored.Filled.List
    Section.FAVORITES -> Icons.Filled.Favorite
    Section.RECENT -> Icons.Filled.Refresh
}

/** Phone navigation: gold "planet" pill for the selected section. */
@Composable
fun OrbitTabs(items: List<Section>, selected: Section, onSelect: (Section) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { s ->
            val sel = s == selected
            val shape = RoundedCornerShape(50)
            val content by animateColorAsState(if (sel) Night else MaterialTheme.colorScheme.onBackground, label = "tab")
            Row(
                Modifier
                    .focusRing(shape)
                    .clip(shape)
                    .then(if (sel) Modifier.background(GoldBrush) else Modifier.glass(shape))
                    .clickable { onSelect(s) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(s.icon(), contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    s.label,
                    color = content,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

/** TV / tablet navigation: a slim rail with the orbit mark on top. */
@Composable
fun OrbitRail(
    items: List<Section>,
    selected: Section,
    onSelect: (Section) -> Unit,
    footer: @Composable () -> Unit,
) {
    Column(
        Modifier
            .width(96.dp)
            .fillMaxHeight()
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FalakMark(44.dp)
        Spacer(Modifier.height(14.dp))
        items.forEach { s ->
            val sel = s == selected
            val shape = RoundedCornerShape(18.dp)
            Column(
                Modifier
                    .width(80.dp)
                    .focusRing(shape)
                    .clip(shape)
                    .background(if (sel) Gold.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSelect(s) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    s.icon(),
                    contentDescription = null,
                    tint = if (sel) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    s.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (sel) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                )
                if (sel) {
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.size(5.dp).background(Gold, CircleShape))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        footer()
    }
}
