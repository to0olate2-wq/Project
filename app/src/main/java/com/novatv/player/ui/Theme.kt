package com.novatv.player.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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

private val NovaColors = darkColorScheme(
    primary = Color(0xFF8B7CFF),
    onPrimary = Color(0xFF120B3D),
    secondary = Color(0xFF4FD1C5),
    background = Color(0xFF0E0F14),
    onBackground = Color(0xFFE8E8F0),
    surface = Color(0xFF151720),
    onSurface = Color(0xFFE8E8F0),
    surfaceVariant = Color(0xFF1F2230),
    onSurfaceVariant = Color(0xFFA3A6B8),
    error = Color(0xFFFF6B6B),
)

@Composable
fun NovaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NovaColors, content = content)
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
