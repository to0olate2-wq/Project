package app.falak.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.data.Channel
import app.falak.player.data.Epg
import app.falak.player.data.Program
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * Sizes shared with the player, which fits the picture into the free space beside the browser
 * (above it on a phone held upright).
 */
object BrowserLayout {
    fun channelsWidthDp(tv: Boolean): Int = if (tv) 420 else 320
    fun groupsWidthDp(tv: Boolean): Int = if (tv) 240 else 160

    /** Upright phones: the picture sits on top across the full width, the list below it. */
    const val PORTRAIT_GROUPS_DP = 140

    /** Width taken by the browser in landscape, in dp. */
    fun panelWidthDp(tv: Boolean, showGroups: Boolean): Int =
        channelsWidthDp(tv) + if (showGroups) groupsWidthDp(tv) else 0
}

/**
 * In-player browser. It opens on the channels of the playing channel's group; Left (or the Groups
 * button) brings in the list of groups. The picture keeps playing beside it. Picking a live channel
 * plays it there; picking the channel that is already playing goes back to full screen.
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun ChannelBrowser(
    open: Boolean,
    showGroups: Boolean,
    groups: Map<String, List<Channel>>,
    currentId: String?,
    epg: Map<String, List<Program>>,
    favorites: Set<String> = emptySet(),
    canFavorite: (Channel) -> Boolean = { false },
    onFavorite: (Channel) -> Unit = {},
    onShowGroups: (Boolean) -> Unit,
    onPick: (List<Channel>, Channel) -> Unit,
    onClose: () -> Unit,
) {
    val tv = isTv()
    AnimatedVisibility(
        visible = open,
        enter = slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { -it / 3 } + fadeIn(tween(220)),
        exit = slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it / 3 } + fadeOut(tween(180)),
    ) {
        // This content is disposed after the exit animation, so plain remember resets on every open.
        val startGroup = remember {
            groups.entries.firstOrNull { e -> e.value.any { it.id == currentId } }?.key ?: groups.keys.firstOrNull()
        }
        // `group` follows the remote instantly; the channel list follows once it rests for a moment,
        // so holding up/down through many groups stays smooth.
        var group by remember { mutableStateOf(startGroup) }
        var shown by remember { mutableStateOf(startGroup) }
        LaunchedEffect(group) {
            if (group != shown) {
                delay(160)
                shown = group
            }
        }
        val channels = groups[shown].orEmpty()
        val now = remember { System.currentTimeMillis() }
        val currentFocus = remember { FocusRequester() }
        val firstFocus = remember { FocusRequester() }
        val groupFocus = remember { FocusRequester() }

        // The remote goes to the groups when they appear, and back to the channels when they close.
        LaunchedEffect(showGroups) {
            repeat(8) {
                delay(45)
                val ok = if (showGroups) {
                    runCatching { groupFocus.requestFocus() }.isSuccess
                } else {
                    runCatching { currentFocus.requestFocus() }.isSuccess || runCatching { firstFocus.requestFocus() }.isSuccess
                }
                if (ok) return@LaunchedEffect
            }
        }

        // Tapping the picture (outside the list) closes the browser.
        BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onClose() } }) {
            val portrait = maxHeight > maxWidth
            val top = if (portrait) maxWidth * 9f / 16f else 0.dp
            val groupsWidth = (if (portrait) BrowserLayout.PORTRAIT_GROUPS_DP else BrowserLayout.groupsWidthDp(tv)).dp
            Row(
                Modifier
                    .then(if (portrait) Modifier.fillMaxWidth() else Modifier)
                    .padding(top = top)
                    .fillMaxHeight()
                    .background(Ink.copy(alpha = 0.97f))
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                // Groups: only when asked for.
                AnimatedVisibility(
                    visible = showGroups,
                    enter = expandHorizontally(tween(180)) + fadeIn(tween(180)),
                    exit = shrinkHorizontally(tween(160)) + fadeOut(tween(120)),
                ) {
                    Row(Modifier.fillMaxHeight()) {
                        Column(Modifier.width(groupsWidth).fillMaxHeight().padding(top = 22.dp)) {
                            Box(Modifier.padding(start = 18.dp, bottom = 8.dp)) { Kicker("Groups", color = Muted) }
                            val groupNames = remember(groups) { groups.keys.toList() }
                            val groupState = rememberLazyListState(
                                initialFirstVisibleItemIndex = (groupNames.indexOf(group) - 3).coerceAtLeast(0),
                            )
                            LazyColumn(
                                state = groupState,
                                contentPadding = PaddingValues(bottom = 24.dp),
                                // Right (or OK) goes back to the channels of the group the remote is on.
                                modifier = Modifier.onPreviewKeyEvent { e ->
                                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                                        shown = group
                                        onShowGroups(false)
                                        true
                                    } else {
                                        false
                                    }
                                },
                            ) {
                                itemsIndexed(groupNames, key = { _, g -> g }) { _, g ->
                                    val sel = g == group
                                    val shape = RoundedCornerShape(6.dp)
                                    val own = remember { FocusRequester() }
                                    Text(
                                        g,
                                        fontFamily = Sans,
                                        fontSize = 14.sp,
                                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (sel) Paper else Muted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp)
                                            .focusRequester(if (sel) groupFocus else own)
                                            .onFocusChanged { if (it.isFocused) group = g }
                                            .rowFocus(shape, if (sel) Ink3 else Color.Transparent)
                                            .clickable {
                                                group = g
                                                shown = g
                                                onShowGroups(false)
                                            }
                                            .padding(horizontal = 10.dp, vertical = 10.dp),
                                    )
                                }
                            }
                        }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(Hairline))
                    }
                }

                // Channels of the selected group
                Column(
                    Modifier
                        .then(if (portrait) Modifier.weight(1f) else Modifier.width(BrowserLayout.channelsWidthDp(tv).dp))
                        .fillMaxHeight()
                        .padding(top = 16.dp),
                ) {
                    Row(Modifier.padding(start = 8.dp, bottom = 6.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!showGroups) {
                            val shape = RoundedCornerShape(50)
                            Row(
                                Modifier
                                    .rowFocus(shape, Ink3)
                                    .clickable { onShowGroups(true) }
                                    .padding(start = 4.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
                                Text("Groups", fontFamily = Sans, fontSize = 12.sp, color = Muted)
                            }
                            Spacer(Modifier.width(10.dp))
                        } else {
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            shown.orEmpty(),
                            fontFamily = Serif,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp,
                            color = Paper,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("${channels.size}", style = Tabular.copy(fontSize = 13.sp), color = Faint)
                    }
                    val listState = rememberLazyListState(
                        initialFirstVisibleItemIndex = (channels.indexOfFirst { it.id == currentId } - 3).coerceAtLeast(0),
                    )
                    LaunchedEffect(shown) {
                        val i = channels.indexOfFirst { it.id == currentId }
                        listState.scrollToItem((if (i >= 0) i - 3 else 0).coerceAtLeast(0))
                    }
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(bottom = 24.dp),
                        // Left brings in the groups.
                        modifier = Modifier.onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) {
                                onShowGroups(true)
                                true
                            } else {
                                false
                            }
                        },
                    ) {
                        itemsIndexed(channels, key = { _, ch -> ch.id }) { i, ch ->
                            val playing = ch.id == currentId
                            val shape = RoundedCornerShape(6.dp)
                            val own = remember { FocusRequester() }
                            val prog = Epg.current(epg[ch.epgKey], now)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp)
                                    .focusRequester(
                                        when {
                                            playing -> currentFocus
                                            i == 0 -> firstFocus
                                            else -> own
                                        },
                                    )
                                    .rowFocus(shape, if (playing) Signal.copy(alpha = 0.16f) else Color.Transparent)
                                    // Hold OK (or long-press) to add or remove it from Favorites.
                                    .combinedClickable(
                                        onClick = { onPick(channels, ch) },
                                        onLongClick = { if (canFavorite(ch)) onFavorite(ch) },
                                    )
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${i + 1}",
                                    style = Tabular.copy(fontSize = 12.sp),
                                    color = if (playing) Signal else Faint,
                                    modifier = Modifier.width(32.dp),
                                )
                                Box(
                                    Modifier.size(52.dp, 32.dp).clip(RoundedCornerShape(5.dp)).background(Ink3),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    AsyncImage(
                                        model = ch.logo.ifBlank { null },
                                        contentDescription = null,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize().padding(4.dp),
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(ch.name, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (prog != null) {
                                        Text(
                                            "${prog.title}  ·  ${minutesLeft(prog, now)}",
                                            fontFamily = Sans,
                                            fontSize = 12.sp,
                                            color = Muted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val length = (prog.stop - prog.start).coerceAtLeast(1L).toFloat()
                                        Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(2.dp).background(Hairline)) {
                                            Box(Modifier.fillMaxWidth(((now - prog.start) / length).coerceIn(0f, 1f)).height(2.dp).background(Signal))
                                        }
                                    }
                                }
                                if (playing) {
                                    Spacer(Modifier.width(8.dp))
                                    LiveDot(7.dp)
                                }
                                if (canFavorite(ch)) {
                                    // Its own stop for the remote: right from the row, then OK.
                                    val fav = ch.id in favorites
                                    Spacer(Modifier.width(4.dp))
                                    Box(
                                        Modifier
                                            .size(36.dp)
                                            .rowFocus(CircleShape, Color.Transparent)
                                            .clickable { onFavorite(ch) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Filled.Star,
                                            contentDescription = if (fav) "Remove from Favorites" else "Add to Favorites",
                                            tint = if (fav) Amber else Faint.copy(alpha = 0.5f),
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The row the remote is on: a light fill and a paper outline, easy to see from the sofa. */
@Composable
private fun Modifier.rowFocus(shape: Shape, base: Color): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .clip(shape)
        .background(if (focused) Paper.copy(alpha = 0.16f) else base)
        .then(if (focused) Modifier.border(2.dp, Paper, shape) else Modifier)
}
