package app.falak.player.ui

import android.content.res.Configuration
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.falak.player.R

// ---- Themes: every colour on screen comes from the active palette ----
@Immutable
class Palette(
    val name: String,
    val dark: Boolean,
    /** page background */
    val ink: Color,
    /** raised surface */
    val ink2: Color,
    /** cards, tiles */
    val ink3: Color,
    val hairline: Color,
    /** main text */
    val paper: Color,
    /** secondary text */
    val muted: Color,
    /** tertiary text */
    val faint: Color,
    /** accent: live, progress, selection */
    val signal: Color,
    /** ratings */
    val amber: Color,
    /** film grain strength 0..1 */
    val grain: Float,
)

/**
 * The accent colour (8.45; the owner picked "colour themes" of what premium apps have): what's live,
 * progress and the selected item. Orange is the app's own; the dark and light pages each get a shade
 * that reads on them. Kept as ARGB numbers too, for the player's own (non-Compose) views.
 */
class Accent(val key: String, val name: String, val darkArgb: Long, val lightArgb: Long) {
    val dark: Color get() = Color(darkArgb)
    val light: Color get() = Color(lightArgb)
}

object Accents {
    val ALL = listOf(
        Accent("orange", "Orange", 0xFFFF5A3C, 0xFFDC3A1C),
        Accent("red", "Red", 0xFFFF4D5E, 0xFFD01F35),
        Accent("pink", "Pink", 0xFFFF5FA2, 0xFFCC2A72),
        Accent("purple", "Purple", 0xFFB384FF, 0xFF7446D1),
        Accent("blue", "Blue", 0xFF4FA3FF, 0xFF1B68CF),
        Accent("teal", "Teal", 0xFF2CCBC0, 0xFF0B857D),
        Accent("green", "Green", 0xFF4CD787, 0xFF168A4C),
    )

    fun of(key: String): Accent = ALL.firstOrNull { it.key == key } ?: ALL[0]
}

/** The accent of the theme around (for a theme drawn inside another, e.g. an alert's buttons). */
@Composable
fun currentAccentKey(): String {
    val signal = LocalPalette.current.signal
    return Accents.ALL.firstOrNull { it.dark == signal || it.light == signal }?.key ?: "orange"
}

/** This palette with [a] as its accent (the same palette for Orange). */
fun Palette.withAccent(a: Accent): Palette =
    if (a.key == "orange") this else Palette(name, dark, ink, ink2, ink3, hairline, paper, muted, faint, if (dark) a.dark else a.light, amber, grain)

/** Evenings and night: warm near-black, like a screening room. */
val DarkPalette = Palette(
    "Dark", true,
    ink = Color(0xFF121110), ink2 = Color(0xFF1B1918), ink3 = Color(0xFF282523), hairline = Color(0x29FFFFFF),
    paper = Color(0xFFF7F3EC), muted = Color(0xFFD2CBC1), faint = Color(0xFFA0988E),
    signal = Color(0xFFFF5A3C), amber = Color(0xFFF4BF55), grain = 0.35f,
)

/** Daytime: warm paper white with ink text. */
val LightPalette = Palette(
    "Light", false,
    ink = Color(0xFFF6F2EA), ink2 = Color(0xFFFFFFFF), ink3 = Color(0xFFEBE5DA), hairline = Color(0x22000000),
    paper = Color(0xFF16130F), muted = Color(0xFF4A423A), faint = Color(0xFF756C62),
    signal = Color(0xFFDC3A1C), amber = Color(0xFFA86F12), grain = 0.10f,
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

val Ink: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.ink
val Ink2: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.ink2
val Ink3: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.ink3
val Hairline: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.hairline
val Paper: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.paper
val Muted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.muted
val Faint: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.faint
val Signal: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.signal
val Amber: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.amber

/**
 * Text on the accent colour (badges such as REC): dark on the dark look's bright orange (6.1:1),
 * white on the light look's deeper red (4.5:1). White on the bright orange was only 3.1:1.
 */
val OnSignal: Color @Composable @ReadOnlyComposable get() =
    LocalPalette.current.let { if (it.dark) it.ink else Color.White }

val Serif = FontFamily(
    Font(R.font.lora_semibold, FontWeight.SemiBold),
    Font(R.font.lora_bold, FontWeight.Bold),
    Font(R.font.lora_italic, FontWeight.Medium, FontStyle.Italic),
)

val Sans = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_semibold, FontWeight.Bold),
)

/** Tabular numerals so clocks and channel numbers don't jitter. */
val Tabular = TextStyle(fontFamily = Sans, fontFeatureSettings = "tnum")

/**
 * Every Material role mapped to the palette, so built-in parts (chips, switches, dialogs, text
 * fields) never fall back to Material's default purple or to black text on a dark page.
 */
private fun schemeFor(p: Palette): androidx.compose.material3.ColorScheme {
    val selected = p.signal.copy(alpha = if (p.dark) 0.26f else 0.16f).compositeOver(p.ink2)
    val base = if (p.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.paper, onPrimary = p.ink,
        primaryContainer = selected, onPrimaryContainer = p.paper,
        inversePrimary = p.ink,
        secondary = p.signal, onSecondary = Color.White,
        secondaryContainer = selected, onSecondaryContainer = p.paper,
        tertiary = p.amber, onTertiary = p.ink,
        tertiaryContainer = p.ink3, onTertiaryContainer = p.paper,
        background = p.ink, onBackground = p.paper,
        surface = p.ink2, onSurface = p.paper,
        surfaceVariant = p.ink3, onSurfaceVariant = p.muted,
        surfaceTint = Color.Transparent,
        inverseSurface = p.paper, inverseOnSurface = p.ink,
        surfaceBright = p.ink3, surfaceDim = p.ink,
        surfaceContainerLowest = p.ink, surfaceContainerLow = p.ink2,
        surfaceContainer = p.ink2, surfaceContainerHigh = p.ink3, surfaceContainerHighest = p.ink3,
        outline = p.faint, outlineVariant = p.hairline,
        scrim = Color.Black,
        error = if (p.dark) Color(0xFFFF8A75) else Color(0xFFC62828),
        onError = if (p.dark) Color.Black else Color.White,
    )
}

private val Type = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        headlineLarge = t.headlineLarge.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        headlineMedium = t.headlineMedium.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 21.sp),
        titleMedium = t.titleMedium.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontFamily = Sans, fontWeight = FontWeight.Medium),
        bodyLarge = t.bodyLarge.copy(fontFamily = Sans),
        bodyMedium = t.bodyMedium.copy(fontFamily = Sans),
        bodySmall = t.bodySmall.copy(fontFamily = Sans),
        labelLarge = t.labelLarge.copy(fontFamily = Sans, fontWeight = FontWeight.Medium),
        labelMedium = t.labelMedium.copy(fontFamily = Sans, fontWeight = FontWeight.Medium),
        labelSmall = t.labelSmall.copy(fontFamily = Sans, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun FalakTheme(dark: Boolean = true, layout: String = "auto", cinema: Boolean = false, accent: String = "orange", content: @Composable () -> Unit) {
    // (Remembered: a new palette each time would redraw every page.)
    val palette = remember(dark, accent) { (if (dark) DarkPalette else LightPalette).withAccent(Accents.of(accent)) }
    val scheme = remember(palette) { schemeFor(palette) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val tv = when (layout) {
        "tv" -> true
        "phone" -> false
        else -> remember(context) { detectTv(context) }
    }
    CompositionLocalProvider(LocalPalette provides palette, LocalIsTv provides tv, LocalCinema provides cinema) {
        MaterialTheme(colorScheme = scheme, typography = Type) {
            // Text and icons without an explicit colour use the theme's text colour
            // (Compose's default is black, which disappeared on the dark themes).
            CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides palette.paper,
                content = content,
            )
        }
    }
}

/**
 * Dark or light right now. "auto": light from sunrise, dark from sunset, for where the device is
 * (worked out from its time zone, see [app.falak.player.data.SunTimes]). Checked every minute, so
 * it switches by itself, and follows a change of time zone.
 */
@Composable
fun rememberDarkTheme(mode: String): Boolean {
    if (mode == "dark") return true
    if (mode == "light") return false
    val context = androidx.compose.ui.platform.LocalContext.current
    val dark by androidx.compose.runtime.produceState(app.falak.player.data.SunTimes.isDark(context)) {
        while (true) {
            value = app.falak.player.data.SunTimes.isDark(context)
            val now = System.currentTimeMillis()
            kotlinx.coroutines.delay(60_000L - now % 60_000L + 50)
        }
    }
    return dark
}

/** TV layout (remote control, big screen) or phone/tablet layout; see Settings → Appearance. */
val LocalIsTv = staticCompositionLocalOf { false }

/** True when the TV layout is in use (detected, or chosen in Settings). */
@Composable
@ReadOnlyComposable
fun isTv(): Boolean = LocalIsTv.current

/** The Cinema look is chosen (Settings → Appearance → Look), rather than Spotlight; see Cinema.kt. */
val LocalCinema = staticCompositionLocalOf { false }

@Composable
@ReadOnlyComposable
fun isCinema(): Boolean = LocalCinema.current

/**
 * Many Android TV boxes don't report themselves as televisions, so also look for the TV
 * launcher feature and for the absence of a touch screen.
 */
fun detectTv(context: android.content.Context): Boolean {
    val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    if (mode == Configuration.UI_MODE_TYPE_TELEVISION) return true
    val pm = context.packageManager
    return pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
        pm.hasSystemFeature("android.software.leanback_only") ||
        !pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
}

/** Quiet surface: a step lighter than the page, with a hairline edge. */
@Composable
fun Modifier.panel(shape: Shape, raised: Boolean = false): Modifier = this
    .clip(shape)
    .background(if (raised) Ink3 else Ink2)
    .border(1.dp, Hairline, shape)

/**
 * Remote-control focus for rows and buttons: a clean paper outline. On a TV (8.48) the outline
 * fades in and the element grows a little (8 dp across at most, so wide rows barely move). Only
 * the drawing follows the animation (graphicsLayer / draw phase): nothing is measured or composed
 * again while it runs, and it runs only when the focus moves, never on its own.
 */
@Composable
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(8.dp)): Modifier {
    var focused by remember { mutableStateOf(false) }
    val tv = isTv()
    val lit by animateFloatAsState(if (focused) 1f else 0f, tween(if (focused) FOCUS_IN_MS else FOCUS_OUT_MS), label = "focusRing")
    val grow = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.toPx() }
    // (The same kinds of modifiers whatever the focus: a changing chain can lose the focus.)
    return this
        .zIndex(if (focused) 1f else 0f)
        .then(
            if (tv) {
                Modifier.graphicsLayer {
                    val s = 1f + lit * minOf(0.05f, if (size.width > 0f) grow / size.width else 0f)
                    scaleX = s
                    scaleY = s
                }
            } else {
                Modifier
            },
        )
        .onFocusChanged { focused = it.isFocused }
        .focusOutline(shape, Paper) { if (tv) lit else if (focused) 1f else 0f }
}

/** Remote-control focus for cards: lifts with a quick spring, the outline and shadow fading in with it. */
@Composable
fun Modifier.cardFocus(shape: Shape): Modifier {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (focused) 1.06f else 1f,
        spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
        label = "cardFocus",
    )
    return this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            shadowElevation = 18f * ((scale - 1f) / 0.06f).coerceIn(0f, 1f)
            this.shape = shape
            clip = false
        }
        .onFocusChanged { focused = it.isFocused }
        .focusOutline(shape, Paper) { ((scale - 1f) / 0.06f).coerceIn(0f, 1f) }
}

/** How long the remote rests on a tab before it opens (TV, 8.49): passing over one opens nothing. */
internal const val OPEN_ON_REST_MS = 400L

/**
 * TV (8.49, the owner: Live, Films, Series… "only hover it should open its content"): the remote
 * resting on this tab for [OPEN_ON_REST_MS] opens it, no OK needed; [enabled] off (where it is open
 * already) does nothing.
 */
@Composable
fun Modifier.openOnRest(enabled: Boolean, onOpen: () -> Unit): Modifier {
    // Moved here by Left / Right (8.50): focus that only lands here (a page or a window closing and
    // the focus falling back to the menu) opens nothing. In 8.49 it did, and some Settings went Home.
    var arrived by remember { mutableStateOf(false) }
    val open = androidx.compose.runtime.rememberUpdatedState(onOpen)
    androidx.compose.runtime.LaunchedEffect(arrived, enabled) {
        if (arrived && enabled) {
            kotlinx.coroutines.delay(OPEN_ON_REST_MS)
            open.value()
        }
    }
    return this.onFocusChanged { arrived = it.hasFocus && NavKeys.sideways() }
}

/**
 * When Left or Right was last pressed in a row of tabs (8.53: the rows note it themselves with
 * [notesSideways]; nothing app-wide listens to the keys): tells a move along the row from focus just landing.
 */
object NavKeys {
    @Volatile private var at = 0L

    fun sidewaysNow() {
        at = android.os.SystemClock.uptimeMillis()
    }

    /** Left or Right pressed in such a row a moment ago. */
    fun sideways(): Boolean = android.os.SystemClock.uptimeMillis() - at < 500
}

/** A row of tabs that open on rest: notes Left / Right pressed in it (and lets the key go on). */
fun Modifier.notesSideways(): Modifier = onPreviewKeyEvent { e ->
    if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
        (e.key == androidx.compose.ui.input.key.Key.DirectionLeft || e.key == androidx.compose.ui.input.key.Key.DirectionRight)
    ) {
        NavKeys.sidewaysNow()
    }
    false
}

/**
 * Glass (8.55, the owner picked "C"): a see-through surface, a light tint of the text colour with a hairline,
 * so the picture behind shows through. Android can't blur what is behind a view on these TVs, so it is tint
 * only. [GLASS_LIT_FILL] = the tint under the remote.
 */
internal const val GLASS_FILL = 0.10f
internal const val GLASS_EDGE = 0.22f
internal const val GLASS_LIT_FILL = 0.26f

@Composable
fun Modifier.glass(shape: Shape): Modifier {
    val paper = Paper
    return this.clip(shape).background(paper.copy(alpha = GLASS_FILL), shape).border(1.dp, paper.copy(alpha = GLASS_EDGE), shape)
}

/** How long the focus takes to show and to leave (TV, 8.48): quick, so the remote never waits for it. */
internal const val FOCUS_IN_MS = 140
internal const val FOCUS_OUT_MS = 110

/**
 * A 2 dp paper outline inside the edge, as `border` drew it, at the strength [alpha] gives (read
 * while drawing, so an animated value only redraws).
 */
internal fun Modifier.focusOutline(shape: Shape, color: Color, alpha: () -> Float): Modifier = drawWithContent {
    drawContent()
    val a = alpha()
    if (a > 0.01f) {
        val w = 2.dp.toPx()
        inset(w / 2f) {
            drawOutline(shape.createOutline(size, layoutDirection, this), color.copy(alpha = a.coerceAtMost(1f)), style = Stroke(w))
        }
    }
}
