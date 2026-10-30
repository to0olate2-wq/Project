package app.falak.player.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import app.falak.player.data.Channel
import app.falak.player.data.Kind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The remote going round channel lists: down on the last one back to the first, and up from the first to the last. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-television")
class FocusLoopTest {
    @get:Rule
    val compose = createComposeRule()

    private val channels = (1..5).map { Channel(id = "c$it", name = "Channel $it", group = "Sports", url = "http://host/$it.ts", kind = Kind.LIVE) }

    @Test
    fun playerChannelListGoesRoundBothWays() {
        compose.setContent {
            CompositionLocalProvider(LocalIsTv provides true) {
                ChannelBrowser(
                    open = true,
                    showGroups = false,
                    groups = mapOf("Sports" to channels),
                    currentId = "c5",
                    epg = emptyMap(),
                    onShowGroups = {},
                    onPick = { _, _ -> },
                    onClose = {},
                )
            }
        }
        compose.waitForIdle()
        val last = compose.onNodeWithText("Channel 5")
        last.requestFocus()
        compose.waitForIdle()
        last.assertIsFocused()
        // Down on the last channel: the first.
        last.performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("Channel 1").assertIsFocused()
        // Up on the first: the last again.
        compose.onNodeWithText("Channel 1").performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        compose.onNodeWithText("Channel 5").assertIsFocused()
    }

    @Test
    fun longListComesBackToTheTop() {
        val many = 60
        compose.setContent {
            val loop = rememberFocusLoop()
            val state = rememberLazyListState()
            LazyColumn(Modifier.size(300.dp, 400.dp).focusLoop(loop, state, many), state = state) {
                itemsIndexed((0 until many).toList()) { i, _ ->
                    Box(Modifier.size(300.dp, 40.dp).loopItem(loop, i).testTag("row$i").focusable())
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("row0").requestFocus()
        // Walk to the end with the remote (the list scrolls along), then one more.
        val last = "row${many - 1}"
        for (i in 0 until many - 1) {
            compose.onNodeWithTag("row$i").performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
        }
        compose.onNodeWithTag(last).assertIsFocused()
        compose.onNodeWithTag(last).performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithTag("row0").assertIsFocused()
    }

    @Test
    fun gridComesBackToTheFirstFromTheLastRow() {
        compose.setContent {
            val loop = rememberFocusLoop()
            val state = rememberLazyGridState()
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.size(300.dp, 600.dp).focusLoop(loop, state, 7), state = state) {
                itemsIndexed((0 until 7).toList()) { i, _ ->
                    Box(Modifier.size(100.dp, 60.dp).loopItem(loop, i).testTag("cell$i").focusable())
                }
            }
        }
        compose.waitForIdle()
        // The last row holds just the 7th; the row above it doesn't go round.
        compose.onNodeWithTag("cell4").requestFocus()
        compose.onNodeWithTag("cell4").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithTag("cell6").assertIsFocused()
        compose.onNodeWithTag("cell6").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithTag("cell0").assertIsFocused()
    }
}
