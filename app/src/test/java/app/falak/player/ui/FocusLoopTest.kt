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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The remote going round channel lists (the player's channel list, the Live list and grid, the
 * guide all use FocusLoop): down on the last one back to the first, and up from the first to the last.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A plain application: only the lists under test run (not the app's own start-up and services).
@Config(qualifiers = "w960dp-h540dp-land-television", application = android.app.Application::class)
class FocusLoopTest {
    /** A test stuck for 45 seconds writes where every thread is, as hang-<test>.txt beside the pictures. */
    @get:Rule(order = 0)
    val watchdog = object : TestWatcher() {
        private var thread: Thread? = null

        override fun starting(description: Description) {
            thread = Thread {
                try {
                    Thread.sleep(45_000)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                val dir = File(System.getProperty("screens.dir") ?: "build/screens").apply { mkdirs() }
                val text = Thread.getAllStackTraces().entries.joinToString("\n\n") { (t, stack) ->
                    "${t.name} (${t.state})\n" + stack.joinToString("\n") { "    at $it" }
                }
                runCatching { File(dir, "hang-FocusLoopTest-${description.methodName}.txt").writeText(text) }
            }.apply {
                isDaemon = true
                start()
            }
        }

        override fun finished(description: Description) {
            thread?.interrupt()
        }
    }

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Test
    fun listGoesRoundBothWays() {
        val count = 5
        compose.setContent {
            val loop = rememberFocusLoop()
            val state = rememberLazyListState()
            LazyColumn(Modifier.size(300.dp, 400.dp).focusLoop(loop, state, count, up = true), state = state) {
                itemsIndexed((0 until count).toList()) { i, _ ->
                    Box(Modifier.size(300.dp, 40.dp).loopItem(loop, i).testTag("ch$i").focusable())
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("ch4").requestFocus()
        compose.waitForIdle()
        compose.onNodeWithTag("ch4").assertIsFocused()
        // Down on the last channel: the first.
        compose.onNodeWithTag("ch4").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithTag("ch0").assertIsFocused()
        // Up on the first: the last again.
        compose.onNodeWithTag("ch0").performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        compose.onNodeWithTag("ch4").assertIsFocused()
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

    /**
     * The Cinema look on a TV (8.18): the menu, then the panel's buttons, then the rows, which place
     * themselves at the top of their list (HomeScroller). Up from a row lower down must go to the
     * row above it (by then scrolled out of view), not jump to the buttons over the list; only
     * the first row leads up to the buttons, and they to the menu.
     */
    @Test
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    fun cinemaRowsButtonsAndMenu() {
        compose.setContent {
            val state = rememberLazyListState()
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val inherited = androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
            val scroller = androidx.compose.runtime.remember { HomeScroller(state, scope, inherited) }
            androidx.compose.foundation.layout.Column(Modifier.size(960.dp, 540.dp)) {
                Box(Modifier.size(80.dp, 40.dp).testTag("menu").focusable())
                androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp, 150.dp))
                Box(Modifier.size(120.dp, 40.dp).testTag("play").focusable())
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalHomeScroller provides scroller,
                    androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides scroller.manual,
                ) {
                    LazyColumn(
                        Modifier.size(960.dp, 310.dp).onGloballyPositioned { scroller.viewport = it },
                        state = state,
                    ) {
                        itemsIndexed((0 until 6).toList()) { r, _ ->
                            androidx.compose.foundation.layout.Column(Modifier.size(960.dp, 252.dp).pinToTop()) {
                                androidx.compose.runtime.CompositionLocalProvider(
                                    androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides scroller.inner,
                                ) {
                                    androidx.compose.foundation.lazy.LazyRow {
                                        itemsIndexed((0 until 8).toList()) { c, _ ->
                                            Box(Modifier.size(130.dp, 230.dp).testTag("r${r}c$c").focusable())
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        fun press(from: String, key: Key, to: String) {
            compose.onNodeWithTag(from).performKeyInput { pressKey(key) }
            compose.waitForIdle()
            compose.onNodeWithTag(to).assertIsFocused()
        }
        compose.onNodeWithTag("menu").requestFocus()
        compose.waitForIdle()
        press("menu", Key.DirectionDown, "play")
        press("play", Key.DirectionDown, "r0c0")
        press("r0c0", Key.DirectionDown, "r1c0")
        press("r1c0", Key.DirectionDown, "r2c0")
        press("r2c0", Key.DirectionUp, "r1c0")
        press("r1c0", Key.DirectionUp, "r0c0")
        press("r0c0", Key.DirectionUp, "play")
        press("play", Key.DirectionUp, "menu")
    }
}
