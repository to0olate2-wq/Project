package app.falak.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalConfiguration

/**
 * More room on phones (8.38; the owner: "make full use of the space… push the buttons somewhere"):
 * the bars around a page (the top bar with the search, the bottom bar, the add-on portal's own
 * bars) slide away while you scroll down to read, and come back as soon as you scroll up a little.
 * One per page frame; the lists' scrolling reaches it through nested scrolling.
 */
class Chrome {
    /** The bars are out of the way. */
    var hidden by mutableStateOf(false)

    private var travel = 0f

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val dy = available.y
            if (dy == 0f) return Offset.Zero
            // A little travel first, so a slight wobble doesn't flick them.
            travel = if ((dy < 0) == (travel < 0)) travel + dy else dy
            if (travel < -THRESHOLD) hidden = true
            if (travel > THRESHOLD / 2) hidden = false
            return Offset.Zero
        }
    }

    fun show() {
        hidden = false
        travel = 0f
    }

    private companion object {
        const val THRESHOLD = 24f
    }
}

/** The page's bars; null where they never hide (TVs, tablets). */
val LocalChrome = staticCompositionLocalOf<Chrome?> { null }

/** A phone (not a TV, not a tablet): where the bars make way. */
@Composable
fun isPhone(): Boolean = !isTv() && LocalConfiguration.current.smallestScreenWidthDp < 600

/**
 * A key for remember / produceState that is the same only for the very same object (8.39). Compose
 * compares keys with equals(): for the TV guide (a map of 100,000+ programmes) that walked the
 * whole guide on the main thread each time a new one arrived.
 */
class Same(val value: Any?) {
    override fun equals(other: Any?): Boolean = other is Same && other.value === value
    override fun hashCode(): Int = System.identityHashCode(value)
}
