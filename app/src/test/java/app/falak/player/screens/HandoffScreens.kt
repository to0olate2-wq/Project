package app.falak.player.screens

import app.falak.player.data.Channel
import app.falak.player.data.HandItem
import app.falak.player.data.HandProgram
import app.falak.player.data.Kind
import app.falak.player.data.LinkAnswer
import app.falak.player.data.NowState
import app.falak.player.screens.ScreenshotBase.Companion.PHONE
import app.falak.player.screens.ScreenshotBase.Companion.PHONE_LAND
import app.falak.player.screens.ScreenshotBase.Companion.TV
import app.falak.player.ui.Screen
import app.falak.player.ui.SecondScreenTest
import app.falak.player.ui.finishOpenForTest
import app.falak.player.ui.finishTrace
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 8.42: the player's buttons (phone: one row of labelled buttons, the tools grouped at the top
 * right), "Back in…" counting down with "On TV" beside it, and "Still watching?".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneHopScreens : ScreenshotBase() {
    @Test @Config(qualifiers = PHONE_LAND)
    fun phoneHop() {
        playerSteps(tv = false) { player, shoot ->
            shoot("phone-player-controls")
            val (hop, hand) = player.previewHop()
            settle()
            check(hop.startsWith("Back in 12 min")) { "The hop button says \"$hop\"" }
            check(hand == "On TV") { "The hand-off button says \"$hand\"" }
            shoot("phone-player-hop")
            check(player.askStillWatchingForTest()) { "\"Still watching?\" didn't ask" }
            settle()
            shoot("phone-player-still-watching")
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvHopScreens : ScreenshotBase() {
    @Test @Config(qualifiers = TV)
    fun tvHop() {
        playerSteps(tv = true) { player, shoot ->
            val (hop, hand) = player.previewHop()
            settle()
            check(hop.startsWith("Back in")) { "The hop button says \"$hop\"" }
            check(hand == "On phone") { "The hand-off button says \"$hand\"" }
            shoot("tv-player-hop")
        }
    }
}

/** "What can I finish?" with an hour and a half: the two films started, longest first, and when each ends. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneFinishScreens : ScreenshotBase() {
    @Test @Config(qualifiers = PHONE)
    fun phoneFinish() {
        finishOpenForTest = 90
        try {
            mainScreens("phone", watching = true) { _, shoot ->
                // (The dialog opens with the row, in the frame after the first one.)
                settle()
                settle()
                val d = org.robolectric.shadows.ShadowDialog.getLatestDialog()
                check(d != null && d.isShowing) { "\"What can I finish?\" didn't open (latest dialog: $d; ${finishTrace})" }
                val dv = d.window?.decorView
                check(dv != null && dv.width > 0 && dv.height > 0) { "The dialog isn't laid out: ${dv?.width} × ${dv?.height}" }
                shoot("phone-home-what-can-i-finish")
            }
        } finally {
            finishOpenForTest = null
        }
    }
}

/** The phone as the linked TV's second screen: a live channel with now and next, and the remote. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneSecondScreenScreens : ScreenshotBase() {
    @Test @Config(qualifiers = PHONE)
    fun phoneSecondScreen() {
        val now = System.currentTimeMillis()
        SecondScreenTest.devices = listOf("Living room TV" to "192.168.1.20:4000")
        SecondScreenTest.answer = LinkAnswer(
            ok = true,
            device = "Living room TV",
            now = NowState(
                item = HandItem("sample", Channel("live-1", "Pulse Sports 1", "Sports", "http://example.invalid/1.ts", kind = Kind.LIVE)),
                now = HandProgram(now - 50 * 60_000L, now + 40 * 60_000L, "City v United", "The second half of the derby, live from the City Ground."),
                next = HandProgram(now + 40 * 60_000L, now + 100 * 60_000L, "Match of the Week", "Goals and talk from every game."),
            ),
        )
        try {
            mainScreens("phone", linked = true) { vm, shoot ->
                vm.screen = Screen.MORE
                waitFor { vm.screen == Screen.MORE }
                shoot("phone-second-screen")
            }
        } finally {
            SecondScreenTest.answer = null
            SecondScreenTest.devices = emptyList()
        }
    }
}
