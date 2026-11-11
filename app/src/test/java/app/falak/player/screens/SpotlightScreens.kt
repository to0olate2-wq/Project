package app.falak.player.screens

import app.falak.player.ui.Section
import app.falak.player.ui.railOpenForTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Spotlight look on a TV (8.14): the top of the page shows the card the remote is on (set here
 * as the remote would), and the rail opens with names while the remote is on it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvSpotlightScreens : ScreenshotBase() {
    @Test @Config(qualifiers = ScreenshotBase.TV)
    fun tvSpotlight() = mainScreens("tv", watching = true) { vm, shoot ->
        try {
            shoot("tv-70-spotlight-featured")
            vm.spotlight[Section.HOME] = vm.index.live.first()
            shoot("tv-71-spotlight-channel")
            vm.spotlight[Section.HOME] = vm.index.movies.first()
            shoot("tv-72-spotlight-film")
            railOpenForTest = true
            shoot("tv-73-rail-open")
            railOpenForTest = false
            vm.section = Section.SERIES
            shoot("tv-74-spotlight-series")
        } finally {
            railOpenForTest = false
        }
    }
}
