package app.falak.player.screens

import app.falak.player.ui.Section
import app.falak.player.ui.groupFinderOpen
import app.falak.player.ui.groupFinderTyped
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Live TV for a big playlist (8.20) on a TV: the groups folded into folders in their column, your
 * own channels as tiles above the list, and the window for finding a group by typing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvLiveScreens : ScreenshotBase() {
    @Test @Config(qualifiers = ScreenshotBase.TV)
    fun tvLive(): Unit = run {
        groupPrefixes = true
        mainScreens("tv", watching = true, habits = true) { vm, shoot ->
            try {
                vm.section = Section.LIVE
                // (A picture asked for the first time isn't in the first frame drawn.)
                shoot("tv-live-1-your-channels")
                shoot("tv-live-1-your-channels")
                vm.group = "UK | Sports"
                shoot("tv-live-2-folder-open")
                groupFinderOpen = true
                shoot("tv-live-3-finder")
                groupFinderTyped = "s"
                shoot("tv-live-4-finder-typed")
            } finally {
                groupFinderOpen = false
                groupFinderTyped = ""
            }
        }
    }
}

/** The same on a phone: the short row in place of the chips, the tiles, and the finder. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneLiveScreens : ScreenshotBase() {
    @Test @Config(qualifiers = ScreenshotBase.PHONE)
    fun phoneLive(): Unit = run {
        groupPrefixes = true
        mainScreens("phone", watching = true, habits = true) { vm, shoot ->
            try {
                vm.section = Section.LIVE
                shoot("phone-live-1-your-channels")
                shoot("phone-live-1-your-channels")
                vm.group = "AR | Kids"
                shoot("phone-live-2-group")
                groupFinderOpen = true
                shoot("phone-live-3-finder")
                groupFinderTyped = "s"
                shoot("phone-live-4-finder-typed")
            } finally {
                groupFinderOpen = false
                groupFinderTyped = ""
            }
        }
    }
}
