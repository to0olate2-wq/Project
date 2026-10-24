package app.falak.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.data.Channel
import app.falak.player.data.Program
import app.falak.player.data.Watch
import coil.compose.AsyncImage

/** "7.4" / "7.400" → "★ 7.4"; null when there is no useful rating. */
fun ratingLabel(raw: String): String? {
    val v = raw.toDoubleOrNull() ?: return null
    if (v <= 0) return null
    return "★ " + if (v % 1.0 == 0.0) v.toInt().toString() else String.format(java.util.Locale.US, "%.1f", v)
}

private const val NEW_WINDOW_SECONDS = 7 * 24 * 3600L

fun Channel.isNew(): Boolean = added > 0 && System.currentTimeMillis() / 1000 - added < NEW_WINDOW_SECONDS

/** Thin progress line drawn along the bottom edge of a card. */
@Composable
private fun EdgeProgress(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Color(0x66000000))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(Signal))
    }
}

/** Film / series poster with its title underneath. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun PosterCard(
    ch: Channel,
    watched: Watch?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = 120.dp,
    focusMe: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusMe) {
        if (focusMe) {
            delay(120)
            runCatching { focus.requestFocus() }
        }
    }
    Column(if (width != null) modifier.width(width) else modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .focusRequester(focus)
                .cardFocus(shape)
                .clip(shape)
                .background(Ink3)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            if (ch.logo.isBlank()) {
                Text(
                    ch.name,
                    fontFamily = Serif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = Muted,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(10.dp),
                )
            } else {
                AsyncImage(
                    model = ch.logo,
                    contentDescription = ch.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val details = LocalCardDetails.current
            if (ch.isNew()) {
                Text(
                    "NEW",
                    fontFamily = Sans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    letterSpacing = 1.2.sp,
                    color = Paper,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(7.dp)
                        .background(Signal, RoundedCornerShape(3.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            } else if (details) {
                qualityTag(ch)?.let { CardBadge(it, Amber, Modifier.align(Alignment.TopStart).padding(7.dp)) }
            }
            if (watched != null && watched.dur > 0) {
                EdgeProgress(watched.pos.toFloat() / watched.dur, Modifier.align(Alignment.BottomCenter))
            }
        }
        Spacer(Modifier.height(6.dp))
        if (LocalCardDetails.current) {
            // Sidebar look: title, then group on the left and rating on the right.
            Text(ch.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ch.group, fontFamily = Sans, fontSize = 11.sp, color = Faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                ratingLabel(ch.rating)?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(it, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Amber, maxLines = 1)
                }
            }
        } else {
            Text(ch.name, style = MaterialTheme.typography.bodySmall, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ratingLabel(ch.rating)?.let {
                Text(it, fontFamily = Sans, fontSize = 11.sp, color = Amber)
            }
        }
    }
}

/** Live channel tile: logo on ink, red LIVE mark, what's on and how far in. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun ChannelCard(
    ch: Channel,
    current: Program?,
    now: Long,
    onClick: () -> Unit,
    width: Dp? = 200.dp,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    Column(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .cardFocus(shape)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Ink3, Ink2)))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            if (ch.logo.isBlank()) {
                Text(
                    ch.name,
                    fontFamily = Serif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    color = Paper,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.align(Alignment.Center).padding(12.dp),
                )
            } else {
                AsyncImage(
                    model = ch.logo,
                    contentDescription = ch.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.align(Alignment.Center).fillMaxSize(0.58f),
                )
            }
            Row(Modifier.align(Alignment.TopStart).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveDot(6.dp)
                Spacer(Modifier.width(5.dp))
                Text("LIVE", fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, letterSpacing = 1.2.sp, color = Paper)
            }
            ch.tidyQuality?.let { CardBadge(it, Amber, Modifier.align(Alignment.TopEnd).padding(7.dp)) }
            if (current != null) {
                val length = (current.stop - current.start).coerceAtLeast(1L).toFloat()
                EdgeProgress((now - current.start) / length, Modifier.align(Alignment.BottomCenter))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(ch.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            current?.let { p -> "${p.title}  ·  ${minutesLeft(p, now)}" } ?: ch.group,
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

fun minutesLeft(p: Program, now: Long): String {
    val m = ((p.stop - now) / 60_000).coerceAtLeast(0)
    return if (m >= 60) "${m / 60}h ${m % 60}m left" else "${m}m left"
}

/** A titled horizontal shelf. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Shelf(title: String, onSeeAll: (() -> Unit)? = null, content: LazyListScope.() -> Unit) {
    val scroller = LocalHomeScroller.current
    Column(Modifier.fillMaxWidth().pinToTop().padding(top = 22.dp)) {
        SectionHeader(title, onSeeAll)
        // The page itself scrolls whole rows into place (see HomeScroller); the row keeps the
        // normal sideways behaviour.
        CompositionLocalProvider(LocalBringIntoViewSpec provides (scroller?.inner ?: LocalBringIntoViewSpec.current)) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                content = content,
            )
        }
    }
}

/**
 * TV Home scrolling: a row that takes focus slides to the top of the screen, and the slideshow
 * shows whole when it takes focus, so nothing is ever left half on screen.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
class HomeScroller(
    val state: LazyListState,
    val scope: kotlinx.coroutines.CoroutineScope,
    /** The normal bring-into-view behaviour, restored inside rows. */
    val inner: BringIntoViewSpec,
) {
    var viewport: LayoutCoordinates? = null

    /** Used by the page list: never scroll by itself, rows place themselves instead. */
    val manual = object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
    }

    private var pinning: Job? = null

    /**
     * Scrolls the page so the row in [target] starts at the top of the screen. A row the remote
     * brings in from off screen is only laid out in the frame after it takes focus, so it is
     * measured then; measuring it at once found nothing and left the page stuck on the slideshow.
     */
    fun pin(target: Array<LayoutCoordinates?>) {
        pinning?.cancel()
        pinning = scope.launch {
            repeat(4) {
                withFrameNanos { }
                val me = target[0]
                val port = viewport
                if (me != null && port != null && me.isAttached && port.isAttached) {
                    val delta = port.localPositionOf(me, Offset.Zero).y
                    if (kotlin.math.abs(delta) > 1f) state.animateScrollBy(delta)
                    return@launch
                }
            }
        }
    }
}

val LocalHomeScroller = staticCompositionLocalOf<HomeScroller?> { null }

/** Scrolls the page so this element's top sits at the top of the screen when it gains focus. */
@Composable
fun Modifier.pinToTop(): Modifier {
    val scroller = LocalHomeScroller.current ?: return this
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return this
        .onGloballyPositioned { holder[0] = it }
        .onFocusChanged { st -> if (st.hasFocus) scroller.pin(holder) }
}
