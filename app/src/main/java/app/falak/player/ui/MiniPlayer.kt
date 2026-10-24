package app.falak.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import app.falak.player.FalakApp
import app.falak.player.R
import app.falak.player.data.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The mini player (Settings → Picture & playback, on unless turned off): Back from a live channel
 * keeps it playing small in a corner of the lists. OK on it (TVs) or a tap (phones) goes back to
 * full screen; Back on it, or ×, closes it. It closes by itself when anything else starts playing
 * or the app leaves the screen.
 */
object MiniPlayerState {
    class Request(val list: List<Channel>, val index: Int, val userAgent: String, val playlistId: String?) {
        val channel: Channel get() = list[index]
    }

    val request = MutableStateFlow<Request?>(null)

    /** The mini's own player while it plays (main thread only), so closing it lets go of the stream at once. */
    internal var player: Player? = null

    fun show(list: List<Channel>, index: Int, userAgent: String, playlistId: String?) {
        if (index in list.indices) request.value = Request(list, index, userAgent, playlistId)
    }

    /** Stops the stream now (many providers allow one connection at a time) and closes the mini player. */
    fun close() {
        player?.stop()
        player = null
        request.value = null
    }
}

@Composable
internal fun BoxScope.MiniPlayer(r: MiniPlayerState.Request, onOpen: () -> Unit, onClose: () -> Unit) {
    val tv = isTv()
    val ch = r.channel
    val context = LocalContext.current
    val repo = remember { (context.applicationContext as FalakApp).repo }
    val close by rememberUpdatedState(onClose)
    // The full player has only just let go of the stream: a moment before connecting again.
    var started by remember(r) { mutableStateOf(false) }
    LaunchedEffect(r) {
        delay(400)
        started = true
    }
    // The app left the screen (Home, another app, the full player): the mini player is done.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) close() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val onNow = remember(ch.epgKey, now) {
        repo.epg.value[ch.epgKey]?.firstOrNull { now >= it.start && now < it.stop }?.title
    }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    // TVs: the remote lands on it (after the list has put its focus back on the channel), so OK is
    // all it takes to go back; any arrow moves on to the page.
    if (tv) LaunchedEffect(r) {
        delay(700)
        runCatching { focus.requestFocus() }
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .align(Alignment.BottomEnd)
            .padding(end = if (tv) 28.dp else 12.dp, bottom = if (tv) 24.dp else 16.dp)
            .width(if (tv) 304.dp else 208.dp)
            .clip(shape)
            .background(Color(0xF5141210))
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) Color.White else Color.White.copy(alpha = 0.14f),
                shape,
            )
            .then(
                if (tv) {
                    Modifier
                        .focusRequester(focus)
                        .onFocusChanged { focused = it.isFocused }
                        .onKeyEvent { e ->
                            // Back on the mini player closes it (elsewhere Back works as usual).
                            if (e.key == Key.Back || e.key == Key.Escape) {
                                if (e.type == KeyEventType.KeyDown) close()
                                true
                            } else {
                                false
                            }
                        }
                } else {
                    Modifier
                },
            )
            .clickable { onOpen() },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            if (started) {
                PreviewPicture(
                    ch,
                    r.userAgent,
                    onOpen,
                    Modifier.fillMaxSize(),
                    onPlayer = { MiniPlayerState.player = it },
                )
            } else {
                CircularProgressIndicator(color = Signal, strokeWidth = 2.dp, modifier = Modifier.align(Alignment.Center))
            }
            if (!tv) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(0xA6000000))
                        .clickable { close() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_pl_close), contentDescription = "Close", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFFF5A3C)))
                Spacer(Modifier.width(6.dp))
                Text(
                    ch.name,
                    fontFamily = Sans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (tv) 14.sp else 13.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val line = when {
                tv && focused -> "OK  full screen  ·  Back  close"
                onNow != null -> onNow
                tv -> "Mini player"
                else -> "Tap for full screen"
            }
            Text(
                line,
                fontFamily = Sans,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
