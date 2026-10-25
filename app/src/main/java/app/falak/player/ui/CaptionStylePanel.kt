package app.falak.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.data.Settings
import app.falak.player.player.CaptionLayout
import app.falak.player.player.SubtitleStyle
import kotlinx.coroutines.delay

/**
 * The captions' look, changed while watching (the player's Subtitles → Caption style): size,
 * colour and background for every kind of captions, and for auto captions where they sit and how
 * many lines show. Each pick shows on the picture at once. The panel keeps out of the captions'
 * way: at the top, or at the bottom when auto captions are at the top.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CaptionStylePanel(
    open: Boolean,
    settings: Settings,
    autoCaptions: Boolean,
    note: String?,
    onChange: ((Settings) -> Settings) -> Unit,
    onClose: () -> Unit,
) {
    val tv = isTv()
    val atBottom = autoCaptions && CaptionLayout.position(settings.autoCapsPosition) == "top"
    AnimatedVisibility(visible = open, enter = fadeIn(tween(200)), exit = fadeOut(tween(160))) {
        val first = remember { FocusRequester() }
        // The remote starts on the size chosen now, as soon as the panel is on screen.
        LaunchedEffect(Unit) {
            repeat(8) {
                delay(45)
                if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
        // Tapping the picture outside the panel closes it.
        BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onClose() } }) {
            // A short screen (a phone on its side): a compact panel, so that even three lines of
            // extra large captions raised above the bottom stay clear of it.
            val compact = maxHeight < 480.dp
            val shape = RoundedCornerShape(if (compact) 14.dp else 18.dp)
            BoxWithConstraints(
                Modifier
                    .align(if (atBottom) Alignment.BottomCenter else Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = if (tv) 18.dp else if (compact) 6.dp else 10.dp)
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Ink2.copy(alpha = 0.94f))
                    .border(1.dp, Hairline, shape)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(horizontal = 16.dp, vertical = if (compact) 8.dp else 12.dp),
            ) {
                val wide = maxWidth >= 560.dp
                val rows = styleGroups(settings, autoCaptions, first, onChange)
                // Compact: room kept on the right for the close button.
                val roomForClose = Modifier.padding(end = if (compact) 30.dp else 0.dp)
                Column {
                    if (!compact) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { Kicker("Caption style", color = Faint) }
                            StyleChip("Done", selected = false, small = true) { onClose() }
                        }
                    }
                    if (note != null) {
                        Text(note, fontFamily = Sans, fontSize = 12.sp, lineHeight = 16.sp, color = Muted, modifier = roomForClose.padding(top = 2.dp, bottom = 4.dp))
                    }
                    if (!compact) Spacer(Modifier.height(8.dp))
                    if (wide) {
                        // Side by side, as many as fit on a line.
                        FlowRow(
                            roomForClose,
                            horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 22.dp),
                            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
                        ) {
                            for ((label, chips) in rows) {
                                Column {
                                    GroupLabel(label)
                                    Spacer(Modifier.height(4.dp))
                                    StyleChips(chips, compact)
                                }
                            }
                        }
                    } else {
                        // A narrow screen: one to a line, the name beside the choices.
                        Column(roomForClose, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((label, chips) in rows) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.width(92.dp)) { GroupLabel(label) }
                                    StyleChips(chips, compact)
                                }
                            }
                        }
                    }
                }
                if (compact) CloseButton(Modifier.align(Alignment.TopEnd)) { onClose() }
            }
        }
    }
}

/** The compact panel's way out (a phone on its side; tapping the picture closes it too). */
@Composable
private fun CloseButton(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(28.dp)
            .clip(CircleShape)
            .border(1.dp, Paper.copy(alpha = 0.28f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Paper, modifier = Modifier.size(16.dp))
    }
}

/** The name over (or beside) a group of choices. */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text.uppercase(),
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.2.sp,
        color = Faint,
        maxLines = 2,
    )
}

/** One choice: its name, whether it's chosen, the focus the panel starts on (if here), and what picking it does. */
private class StyleChoice(val label: String, val selected: Boolean, val focus: FocusRequester? = null, val pick: () -> Unit)

/** The panel's groups of choices: their names and chips. */
private fun styleGroups(
    s: Settings,
    autoCaptions: Boolean,
    first: FocusRequester,
    onChange: ((Settings) -> Settings) -> Unit,
): List<Pair<String, List<StyleChoice>>> {
    val size = s.subSize.coerceIn(0, 3)
    val out = arrayListOf(
        "Size" to SubtitleStyle.SIZES.mapIndexed { i, label ->
            StyleChoice(label, size == i, focus = if (size == i) first else null) { onChange { it.copy(subSize = i) } }
        },
        "Text" to listOf("white" to "White", "yellow" to "Yellow").map { (key, label) ->
            StyleChoice(label, (s.subColor == "yellow") == (key == "yellow")) { onChange { it.copy(subColor = key) } }
        },
        "Background" to listOf("box" to "Dark box", "outline" to "Outline").map { (key, label) ->
            StyleChoice(label, (s.subEdge == "outline") == (key == "outline")) { onChange { it.copy(subEdge = key) } }
        },
    )
    if (autoCaptions) {
        val position = CaptionLayout.position(s.autoCapsPosition)
        val lines = CaptionLayout.lines(s.autoCapsLines)
        out += "Auto captions" to CaptionLayout.POSITIONS.map { (key, label) ->
            StyleChoice(label, position == key) { onChange { it.copy(autoCapsPosition = key) } }
        }
        out += "Lines" to CaptionLayout.LINES.map { n ->
            StyleChoice("$n", lines == n) { onChange { it.copy(autoCapsLines = n) } }
        }
    }
    return out
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StyleChips(chips: List<StyleChoice>, compact: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (c in chips) {
            StyleChip(c.label, c.selected, modifier = c.focus?.let { Modifier.focusRequester(it) } ?: Modifier, compact = compact, onClick = c.pick)
        }
    }
}

/**
 * A choice chip whose remote-control focus shows on a chosen chip too: a ring just outside it
 * (the app's usual paper outline would vanish on a paper-coloured chosen chip).
 */
@Composable
private fun StyleChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) Paper else Color.Transparent, shape)
            .padding(if (compact) 2.dp else 3.dp)
            .clip(shape)
            .background(if (selected) Paper else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else Paper.copy(alpha = 0.28f), shape)
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (small || compact) 12.dp else 14.dp,
                vertical = if (small) 4.dp else if (compact) 5.dp else 7.dp,
            ),
    ) {
        Text(
            text,
            color = if (selected) Ink else Paper,
            fontFamily = Sans,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}
