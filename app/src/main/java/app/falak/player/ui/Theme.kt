package app.falak.player.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.falak.player.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

private val FalakColors = darkColorScheme(
    primary = Color(0xFF9D8CFF),
    onPrimary = Color(0xFF140C45),
    secondary = Color(0xFFFFC857), // the "planet" gold
    onSecondary = Color(0xFF2A1B00),
    tertiary = Color(0xFF3FC6D6),
    background = Color(0xFF0B0A14),
    onBackground = Color(0xFFECEAF6),
    surface = Color(0xFF14121F),
    onSurface = Color(0xFFECEAF6),
    surfaceVariant = Color(0xFF1E1B2E),
    onSurfaceVariant = Color(0xFFA9A5C0),
    error = Color(0xFFFF6B6B),
)

/** Brand typeface for titles; body text stays on the system font for readability. */
val Poppins = FontFamily(
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_bold, FontWeight.Bold),
)

private val FalakType = Typography().let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontFamily = Poppins, fontWeight = FontWeight.Bold),
        headlineMedium = t.headlineMedium.copy(fontFamily = Poppins, fontWeight = FontWeight.Bold),
        headlineSmall = t.headlineSmall.copy(fontFamily = Poppins, fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontFamily = Poppins, fontWeight = FontWeight.Medium),
        titleMedium = t.titleMedium.copy(fontFamily = Poppins, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun FalakTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FalakColors, typography = FalakType, content = content)
}

/** Clear focus outline so items are easy to follow with a TV remote. */
@Composable
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(12.dp)): Modifier {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    return this
        .onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(2.dp, color, shape) else Modifier)
}
