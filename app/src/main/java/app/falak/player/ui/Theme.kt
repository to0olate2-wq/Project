package app.falak.player.ui

import android.content.res.Configuration
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
fun FalakTheme(dark: Boolean = true, layout: String = "auto", content: @Composable () -> Unit) {
    val palette = if (dark) DarkPalette else LightPalette
    val scheme = remember(palette) { schemeFor(palette) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val tv = when (layout) {
        "tv" -> true
        "phone" -> false
        else -> remember(context) { detectTv(context) }
    }
    CompositionLocalProvider(LocalPalette provides palette, LocalIsTv provides tv) {
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

/** Remote-control focus for rows and buttons: a clean paper outline. */
@Composable
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(8.dp)): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(2.dp, Paper, shape) else Modifier)
}

/** Remote-control focus for cards: lifts slightly with a spring, paper outline. */
@Composable
fun Modifier.cardFocus(shape: Shape): Modifier {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (focused) 1.06f else 1f,
        spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "cardFocus",
    )
    return this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            shadowElevation = if (focused) 18f else 0f
            this.shape = shape
            clip = false
        }
        .onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(2.dp, Paper, shape) else Modifier)
}
