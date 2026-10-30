package app.falak.player.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Channel lists that go round with the remote: down on the last channel comes back to the first
 * (and, where asked, up on the first goes to the last), instead of stopping.
 */
@Stable
class FocusLoop {
    /** The item the remote is on (its index), or -1. */
    var focused by mutableIntStateOf(-1)

    /** The item to give the remote to once it is on screen, or -1. */
    var target by mutableIntStateOf(-1)
}

@Composable
fun rememberFocusLoop(): FocusLoop = remember { FocusLoop() }

/** On a list: down on the last item goes to the first; with [up], up on the first goes to the last. */
@Composable
fun Modifier.focusLoop(loop: FocusLoop, state: LazyListState, count: Int, up: Boolean = false): Modifier {
    val scope = rememberCoroutineScope()
    return onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown || count < 2) return@onPreviewKeyEvent false
        val to = when {
            e.key == Key.DirectionDown && loop.focused == count - 1 -> 0
            up && e.key == Key.DirectionUp && loop.focused == 0 -> count - 1
            else -> return@onPreviewKeyEvent false
        }
        loop.target = to
        scope.launch { state.scrollToItem(to) }
        true
    }
}

/** On a grid: down on the last row goes back to the first item. */
@Composable
fun Modifier.focusLoop(loop: FocusLoop, state: LazyGridState, count: Int): Modifier {
    val scope = rememberCoroutineScope()
    return onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown || e.key != Key.DirectionDown || count < 2) return@onPreviewKeyEvent false
        val info = state.layoutInfo.visibleItemsInfo
        val last = info.lastOrNull() ?: return@onPreviewKeyEvent false
        // The last row is on screen and the remote is in it.
        if (last.index != count - 1) return@onPreviewKeyEvent false
        val lastRow = info.filter { it.row == last.row }.minOf { it.index }
        if (loop.focused < lastRow) return@onPreviewKeyEvent false
        loop.target = 0
        scope.launch { state.scrollToItem(0) }
        true
    }
}

/**
 * On each item (before its clickable): tells the loop when the remote is on it, and takes the
 * remote when the loop comes round to it. On a row with several stops it goes to the first.
 */
@Composable
fun Modifier.loopItem(loop: FocusLoop, index: Int): Modifier {
    val focus = remember { FocusRequester() }
    LaunchedEffect(loop.target, index) {
        if (loop.target != index) return@LaunchedEffect
        // Just scrolled into view: it may take a frame or two to be ready for the remote.
        repeat(10) {
            delay(16)
            if (runCatching { focus.requestFocus() }.isSuccess) {
                loop.target = -1
                return@LaunchedEffect
            }
        }
        loop.target = -1
    }
    return this
        .onFocusChanged { if (it.hasFocus) loop.focused = index }
        .focusRequester(focus)
}
