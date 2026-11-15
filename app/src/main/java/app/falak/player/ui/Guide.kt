package app.falak.player.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import app.falak.player.R
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/*
 * The welcome page, the guide over the app's buttons, and what changed in an update (8.27; the
 * owner: "first time installing the app, a welcome screen and features on each button, like a
 * guide, for the first time only", and "whenever there is a new update with big changes in core
 * features, show where the update is").
 *
 * Buttons say where they are with Modifier.guideAnchor(name) (the menu's sections and tools, the
 * search box). A guide is a list of steps, each naming the buttons it may point at: the first one
 * on screen gets a ring with the rest of the screen dimmed, and a card beside it says what it
 * does; a step whose button isn't on this screen (another layout) is left out; a step with no
 * button is a card in the middle. A tap anywhere or Next goes on; Skip or Back ends it.
 */

/** One card of a guide: [keys], the buttons it may point at (the first on screen); none: a card in the middle. */
data class GuideStep(val keys: List<String>, val title: String, val text: String)

object Guide {
    /** Where the buttons are, in the window. */
    val anchors = mutableStateMapOf<String, Rect>()

    var steps by mutableStateOf<List<GuideStep>?>(null)
        private set
    var at by mutableIntStateOf(0)
        private set
    private var onDone: (() -> Unit)? = null

    /**
     * The welcome page and the guides start by themselves (true in the app). Screen tests leave
     * them off (every other picture would have a guide over it) and turn them on to draw them.
     */
    var auto = android.os.Build.FINGERPRINT != "robolectric"

    fun start(all: List<GuideStep>, done: () -> Unit = {}) {
        // (Only the steps whose button is on this screen, and those that point at nothing.)
        val shown = all.filter { s -> s.keys.isEmpty() || s.keys.any { anchors[it]?.let { r -> r.width > 0f && r.height > 0f } == true } }
        if (shown.isEmpty()) {
            done()
            return
        }
        onDone = done
        at = 0
        steps = shown
    }

    fun next() {
        val s = steps ?: return
        if (at + 1 < s.size) at++ else finish()
    }

    fun finish() {
        steps = null
        at = 0
        onDone?.invoke()
        onDone = null
    }

    /** The guide for a first start: the app's main buttons, then the player. */
    val TOUR = listOf(
        GuideStep(listOf("Home"), "Home", "What you were watching, what's new on your playlist, and your favourites, all in one place."),
        GuideStep(listOf("Live", "Live TV"), "Live TV", "Every channel, in rows by group. On a big playlist, the chips at the top fold groups into folders, and \"Find a group\" finds one by its name."),
        GuideStep(listOf("Films"), "Films", "Browse by group, or start with the Top 10 of what's new."),
        GuideStep(listOf("Series"), "Series", "Follow a show and you're told when a new episode arrives."),
        GuideStep(listOf("Search"), "Search", "Channels, films, series and what's on, all in one search. Or press the microphone and say it."),
        GuideStep(listOf("TV guide"), "TV guide", "What's on now and later. Set a reminder, or record a programme."),
        GuideStep(listOf("Add-ons"), "Add-ons", "Stremio add-ons like Torrentio open here, each as a portal of its own with Home, Films, Series and My list."),
        GuideStep(listOf("More"), "More", "Downloads, recordings, add-ons, settings, and linking your phone and TV over Wi-Fi."),
        GuideStep(listOf("Settings"), "Settings", "Playback, subtitles, the parental lock, and your playlists."),
        GuideStep(
            emptyList(),
            "While you watch",
            "The house at the top of the player takes you straight back to Home. \"Skip intro\" shows while an intro plays: press it to skip, or let it play. " +
                "You can see this guide again in Settings → Help and about.",
        ),
    )
}

/** Tells the guide where this button is (by [key]: its name, as in Guide.TOUR). */
fun Modifier.guideAnchor(key: String): Modifier = composed {
    DisposableEffect(key) { onDispose { Guide.anchors.remove(key) } }
    onGloballyPositioned { c ->
        if (!c.isAttached) return@onGloballyPositioned
        val r = c.boundsInRoot()
        if (Guide.anchors[key] != r) Guide.anchors[key] = r
    }
}

/** The guide running now, over everything (nothing when none is). */
@Composable
fun GuideOverlay() {
    val steps = Guide.steps ?: return
    val i = Guide.at.coerceIn(0, steps.lastIndex)
    val step = steps[i]
    val tv = isTv()
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var cardH by remember { mutableIntStateOf(0) }
    val next = remember { FocusRequester() }
    val skip = remember { FocusRequester() }
    BackHandler { Guide.finish() }
    LaunchedEffect(i) {
        delay(60)
        runCatching { next.requestFocus() }
    }
    val pad = with(density) { 6.dp.toPx() }
    val hole = step.keys.firstNotNullOfOrNull { Guide.anchors[it] }?.translate(-origin)?.inflate(pad)
    val ring = Signal
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(i) { detectTapGestures { Guide.next() } }
            // The remote stays in the card: Left is Skip, Right is Next, Up and Down do nothing.
            .onPreviewKeyEvent { e ->
                val k = e.nativeKeyEvent
                if (k.action != KeyEvent.ACTION_DOWN) {
                    k.keyCode == KeyEvent.KEYCODE_DPAD_UP || k.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                } else {
                    when (k.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> runCatching { skip.requestFocus() }.let { true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> runCatching { next.requestFocus() }.let { true }
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
                        else -> false
                    }
                }
            },
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            drawRect(Color.Black.copy(alpha = 0.74f))
            if (hole != null) {
                val corner = CornerRadius(14.dp.toPx())
                drawRoundRect(Color.Transparent, hole.topLeft, hole.size, corner, blendMode = BlendMode.Clear)
                drawRoundRect(ring, hole.topLeft, hole.size, corner, style = Stroke(2.5.dp.toPx()))
            }
        }
        val cardW = with(density) { minOf(380.dp.toPx(), w - 32.dp.toPx()) }
        val gap = with(density) { 14.dp.toPx() }
        val edge = with(density) { 16.dp.toPx() }
        // Beside a button at the left (the TV's menu), else under it, or over it near the bottom (the phone's bar).
        val pos: IntOffset = when {
            hole == null -> IntOffset(((w - cardW) / 2).toInt(), ((h - cardH) / 2).toInt().coerceAtLeast(0))
            hole.right < w / 3 && hole.width < w / 4 -> IntOffset(
                (hole.right + gap).toInt(),
                (hole.center.y - cardH / 2).coerceIn(edge, (h - cardH - edge).coerceAtLeast(edge)).toInt(),
            )
            h - hole.bottom >= cardH + gap + edge -> IntOffset(
                (hole.center.x - cardW / 2).coerceIn(edge, w - cardW - edge).toInt(),
                (hole.bottom + gap).toInt(),
            )
            else -> IntOffset(
                (hole.center.x - cardW / 2).coerceIn(edge, w - cardW - edge).toInt(),
                (hole.top - gap - cardH).coerceAtLeast(edge).toInt(),
            )
        }
        val shape = RoundedCornerShape(14.dp)
        Column(
            Modifier
                .offset { pos }
                .width(with(density) { cardW.toDp() })
                .onSizeChanged { cardH = it.height }
                .background(Ink2, shape)
                .border(1.dp, Hairline, shape)
                // (A tap on the card isn't a tap beside it.)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(18.dp),
        ) {
            if (steps.size > 1) Text("${i + 1} of ${steps.size}", fontFamily = Sans, fontSize = 12.sp, color = Faint)
            Spacer(Modifier.height(4.dp))
            Text(step.title, fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = if (tv) 22.sp else 20.sp, color = Paper)
            Spacer(Modifier.height(6.dp))
            Text(step.text, fontFamily = Sans, fontSize = if (tv) 15.sp else 14.sp, lineHeight = if (tv) 21.sp else 20.sp, color = Paper.copy(alpha = 0.86f))
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (i + 1 < steps.size) {
                    GhostButton("Skip", modifier = Modifier.focusRequester(skip)) { Guide.finish() }
                } else {
                    Spacer(Modifier.focusRequester(skip).size(1.dp))
                }
                Spacer(Modifier.weight(1f))
                PaperButton(if (i + 1 < steps.size) "Next" else "Done", modifier = Modifier.focusRequester(next)) { Guide.next() }
            }
        }
    }
}

/** The first page of a new install: what the app is, then "Get started" (adding a playlist). */
@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    val tv = isTv()
    val start = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { start.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandMark(if (tv) 72.dp else 64.dp)
            Spacer(Modifier.height(20.dp))
            Text("Welcome to The Player", fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = if (tv) 32.sp else 28.sp, color = Paper, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Your provider's live TV, films and series, in one place.",
                fontFamily = Sans,
                fontSize = 15.sp,
                color = Muted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(26.dp))
            WelcomeLine(navIcon(Section.LIVE), "Live TV with a TV guide", "Channels in rows by group, what's on now and later, reminders and recording.")
            WelcomeLine(navIcon(Section.MOVIES), "Films and series", "Pick up where you stopped, follow a show, skip the intro.")
            WelcomeLine(painterResource(R.drawable.ic_nav_addons), "Add-ons", "Stremio add-ons for even more films and series.")
            WelcomeLine(rememberVectorPainter(Icons.Outlined.Phone), "Phone and TV together", "Link them over Wi-Fi: your playlists, favourites and lists on both.")
            Spacer(Modifier.height(26.dp))
            PaperButton("Get started", modifier = Modifier.focusRequester(start), onClick = onStart)
            Spacer(Modifier.height(10.dp))
            Text(
                "Next: add your playlist. Once it opens, a short guide shows you around.",
                fontFamily = Sans,
                fontSize = 12.sp,
                color = Faint,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun WelcomeLine(icon: Painter, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.Start) {
        Box(Modifier.size(38.dp).background(Ink2, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Signal, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Paper)
            Text(text, fontFamily = Sans, fontSize = 13.sp, lineHeight = 18.sp, color = Muted)
        }
    }
}
