package app.falak.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
 * In-player browser: groups on the left, the group's channels on the right. Moving through the
 * groups updates the channels instantly; picking a channel switches without leaving the player.
 */
@Composable
fun ChannelBrowser(
    open: Boolean,
    groups: Map<String, List<Channel>>,
    currentId: String?,
    epg: Map<String, List<Program>>,
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
        val groupFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            delay(180)
            if (runCatching { currentFocus.requestFocus() }.isFailure) runCatching { groupFocus.requestFocus() }
        }

        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onClose() } }) {
            Row(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(if (tv) 0.66f else 0.9f)
                    .background(Brush.horizontalGradient(listOf(Ink.copy(alpha = 0.97f), Ink.copy(alpha = 0.90f))))
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                // Groups
                Column(Modifier.width(if (tv) 260.dp else 150.dp).fillMaxHeight().padding(top = 22.dp)) {
                    Box(Modifier.padding(start = 18.dp, bottom = 8.dp)) { Kicker("Groups", color = Muted) }
                    val groupNames = remember(groups) { groups.keys.toList() }
                    val groupState = rememberLazyListState(
                        initialFirstVisibleItemIndex = (groupNames.indexOf(group) - 3).coerceAtLeast(0),
                    )
                    LazyColumn(state = groupState, contentPadding = PaddingValues(bottom = 24.dp)) {
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
                                    .focusRing(shape)
                                    .clip(shape)
                                    .background(if (sel) Ink3 else Color.Transparent)
                                    .clickable {
                                        group = g
                                        shown = g
                                    }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(Hairline))
                // Channels of the selected group
                Column(Modifier.weight(1f).fillMaxHeight().padding(top = 22.dp)) {
                    Row(Modifier.padding(start = 18.dp, bottom = 8.dp, end = 18.dp)) {
                        Text(shown.orEmpty(), fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Paper, maxLines = 1)
                        Spacer(Modifier.weight(1f))
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
                        // Left always returns to the selected group, not whichever group is level with the row.
                        modifier = Modifier.onPreviewKeyEvent { e ->
                            e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft &&
                                runCatching { groupFocus.requestFocus() }.isSuccess
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
                                    .focusRequester(if (playing) currentFocus else own)
                                    .focusRing(shape)
                                    .clip(shape)
                                    .background(if (playing) Signal.copy(alpha = 0.14f) else Color.Transparent)
                                    .clickable { onPick(channels, ch) }
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
                            }
                        }
                    }
                }
            }
        }
    }
}
