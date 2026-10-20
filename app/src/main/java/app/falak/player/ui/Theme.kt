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

// ---- "Screening room" palette: warm ink, paper text, one signal-red accent ----
val Ink = Color(0xFF111010)
val Ink2 = Color(0xFF181615)
val Ink3 = Color(0xFF221F1D)
val Hairline = Color(0x1FFFFFFF)
val Paper = Color(0xFFF3EEE6)
val Muted = Color(0xFFA39C92)
val Faint = Color(0xFF6E6862)
val Signal = Color(0xFFFF4F2E)
val Amber = Color(0xFFF2B84B)

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

private val Colors = darkColorScheme(
    primary = Paper,
    onPrimary = Ink,
    secondary = Signal,
    onSecondary = Paper,
    tertiary = Amber,
    background = Ink,
    onBackground = Paper,
    surface = Ink2,
    onSurface = Paper,
    surfaceVariant = Ink3,
    onSurfaceVariant = Muted,
    surfaceContainerHigh = Color(0xFF1F1C1A),
    surfaceContainerHighest = Color(0xFF282422),
    surfaceContainer = Ink2,
    outline = Color(0x40FFFFFF),
    outlineVariant = Hairline,
    error = Color(0xFFFF7A66),
)

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
fun FalakTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Type, content = content)
}

/** True on Android TV / Google TV. */
@Composable
fun isTv(): Boolean =
    (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

/** Quiet surface: a step lighter than the page, with a hairline edge. */
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
