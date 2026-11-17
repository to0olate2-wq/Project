package app.falak.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.roundToInt

/**
 * The mini player (Settings → Picture & playback, on unless turned off): Back from a live channel
 * keeps it playing small in a corner of the lists. OK on it (TVs) or a tap (phones) goes back to
 * full screen; Back on it, or ×, closes it. It closes by itself when anything else starts playing
 * or the app leaves the screen.
 */
object MiniPlayerState {
    class Request(
        val list: List<Channel>,
        val index: Int,
        val userAgent: String,
        val playlistId: String?,
        /** TVs: the remote goes onto it (not when the player was left for a page such as Settings). */
        val takeFocus: Boolean = true,
    ) {
        val channel: Channel get() = list[index]
    }

    val request = MutableStateFlow<Request?>(null)

    /** The mini's own player while it plays (main thread only), so closing it lets go of the stream at once. */
    internal var player: Player? = null

    fun show(list: List<Channel>, index: Int, userAgent: String, playlistId: String?, takeFocus: Boolean = true) {
        if (index in list.indices) request.value = Request(list, index, userAgent, playlistId, takeFocus)
    }

    /** Stops the stream now (many providers allow one connection at a time) and closes the mini player. */
    fun close() {
        player?.stop()
        player = null
        request.value = null
    }
}

/**
 * Where the mini player sits: anywhere on the screen (the owner, 8.32), dragged on a phone, moved
 * with the arrows on a TV (hold OK on it first). Kept as fractions of the free room (0 = left/top,
 * 1 = right/bottom), so it stays in its place when the phone turns; on this device only.
 */
internal object MiniSpot {
    private const val FILE = "mini_player"

    fun read(context: android.content.Context): Pair<Float, Float> {
        val p = context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)
        return p.getFloat("x", 1f).coerceIn(0f, 1f) to p.getFloat("y", 1f).coerceIn(0f, 1f)
    }

    fun save(context: android.content.Context, x: Float, y: Float) {
        context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).edit()
            .putFloat("x", x.coerceIn(0f, 1f)).putFloat("y", y.coerceIn(0f, 1f)).apply()
    }

    /** [at] (a fraction of [span] px) moved by [by] px, kept inside. */
    fun moved(at: Float, by: Float, span: Float): Float =
        if (span <= 0f) at else ((at * span + by) / span).coerceIn(0f, 1f)
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
    // The player's sleep timer: when it's time, the small picture goes too.
    LaunchedEffect(r) {
        while (true) {
            delay(1_000)
            if (app.falak.player.player.SleepTimer.due(android.os.SystemClock.elapsedRealtime())) {
                app.falak.player.player.SleepTimer.cancel()
                close()
                break
            }
        }
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
    if (tv && r.takeFocus) LaunchedEffect(r) {
        delay(700)
        runCatching { focus.requestFocus() }
    }
    val shape = RoundedCornerShape(12.dp)
    val density = LocalDensity.current
    val miniWidth = if (tv) 304.dp else 208.dp
    // Its place: where it was left last time (bottom right at first).
    var spot by remember { mutableStateOf(MiniSpot.read(context)) }
    // TVs: OK held on it = moving it with the arrows, until OK or Back.
    var moving by remember { mutableStateOf(false) }
    var okDown by remember { mutableStateOf(false) }
    // The room it can move in (px), as the last layout found it (the layout below places it).
    val span = remember { FloatArray(2) }
    val margin = with(density) { (if (tv) 24.dp else 12.dp).toPx() }
    val step = with(density) { 48.dp.toPx() }
    fun moveBy(dx: Float, dy: Float) {
        spot = MiniSpot.moved(spot.first, dx, span[0]) to MiniSpot.moved(spot.second, dy, span[1])
    }
    fun keep() = MiniSpot.save(context, spot.first, spot.second)
    Layout(modifier = Modifier.fillMaxSize(), content = {
    Column(
        Modifier
            .width(miniWidth)
            .clip(shape)
            .background(Color(0xF5141210))
            .border(
                if (focused || moving) 2.dp else 1.dp,
                when {
                    moving -> Signal
                    focused -> Color.White
                    else -> Color.White.copy(alpha = 0.14f)
                },
                shape,
            )
            .then(
                if (tv) {
                    Modifier
                        .focusRequester(focus)
                        .onFocusChanged {
                            focused = it.isFocused
                            if (!it.isFocused && moving) {
                                moving = false
                                keep()
                            }
                        }
                        .onPreviewKeyEvent { e ->
                            val down = e.type == KeyEventType.KeyDown
                            when (e.key) {
                                // OK: full screen; held: move it (then OK again when it's in place).
                                Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                                    if (down) {
                                        if (e.nativeKeyEvent.repeatCount == 0) {
                                            okDown = true
                                        } else if (okDown && !moving) {
                                            okDown = false
                                            moving = true
                                        }
                                    } else if (e.type == KeyEventType.KeyUp && okDown) {
                                        okDown = false
                                        if (moving) {
                                            moving = false
                                            keep()
                                        } else {
                                            onOpen()
                                        }
                                    }
                                    true
                                }
                                // Back: done moving; otherwise it closes the mini player.
                                Key.Back, Key.Escape -> {
                                    if (down) {
                                        if (moving) {
                                            moving = false
                                            keep()
                                        } else {
                                            close()
                                        }
                                    }
                                    true
                                }
                                Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> {
                                    if (!moving) return@onPreviewKeyEvent false
                                    if (down) {
                                        when (e.key) {
                                            Key.DirectionLeft -> moveBy(-step, 0f)
                                            Key.DirectionRight -> moveBy(step, 0f)
                                            Key.DirectionUp -> moveBy(0f, -step)
                                            else -> moveBy(0f, step)
                                        }
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                } else {
                    // Phones: dragged anywhere; a tap still opens it.
                    Modifier.pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { keep() },
                            onDragCancel = { keep() },
                        ) { change, drag ->
                            change.consume()
                            moveBy(drag.x, drag.y)
                        }
                    }
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
                tv && moving -> "Arrows  move it  ·  OK  done"
                tv && focused -> "OK  full screen  ·  Hold OK  move  ·  Back  close"
                onNow != null -> onNow
                tv -> "Mini player"
                else -> "Tap for full screen · drag to move"
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
    }) { measurables, constraints ->
        // Placed here (not by an offset) so its own size is known on the very first frame.
        val p = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        span[0] = (constraints.maxWidth - p.width - 2 * margin).coerceAtLeast(0f)
        span[1] = (constraints.maxHeight - p.height - 2 * margin).coerceAtLeast(0f)
        layout(constraints.maxWidth, constraints.maxHeight) {
            p.place((margin + spot.first * span[0]).roundToInt(), (margin + spot.second * span[1]).roundToInt())
        }
    }
}
