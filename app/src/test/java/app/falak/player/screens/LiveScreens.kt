package app.falak.player.screens

import app.falak.player.ui.ALL_FILTER
import app.falak.player.ui.Section
import app.falak.player.ui.groupFinderOpen
import app.falak.player.ui.groupFinderTyped
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Live TV on a TV. Its front page (8.21): rows, as on Films and Series (your channels, then a row
 * for each group, never the provider's divider lines), the folders as chips over them. Then the
 * list for a big playlist (8.20): the groups folded into folders in their column, your own
 * channels as tiles above the list, and the window for finding a group by typing.
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
                shoot("tv-live-0-rows")
                shoot("tv-live-0-rows")
                // The remote on a channel: the top of the page tells what's on it.
                vm.spotlight[Section.LIVE] = vm.index.live.first { it.name == "Pulse Sports 1" }
                shoot("tv-live-0b-rows-channel")
                vm.spotlight.remove(Section.LIVE)
                // A folder picked: only its groups' rows.
                vm.liveFolder = "UK"
                shoot("tv-live-0c-rows-folder")
                shoot("tv-live-0c-rows-folder")
                vm.liveFolder = null
                // "All channels": the list.
                vm.group = ALL_FILTER
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
                vm.liveFolder = null
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
                shoot("phone-live-0-rows")
                shoot("phone-live-0-rows")
                vm.liveFolder = "AR"
                shoot("phone-live-0c-rows-folder")
                shoot("phone-live-0c-rows-folder")
                vm.liveFolder = null
                vm.group = ALL_FILTER
                shoot("phone-live-1-your-channels")
                shoot("phone-live-1-your-channels")
                vm.group = "AR | Kids"
                shoot("phone-live-2-group")
                // A channel of the group that didn't work: out of the list, with a line to show it.
                val gone = vm.index.live.first { it.group == "AR | Kids" && !it.name.startsWith("#") }
                app.repo.update(stamp = false) { it.copy(dead = mapOf(vm.playlist!!.id to mapOf(gone.id to System.currentTimeMillis()))) }
                shoot("phone-live-2b-one-hidden")
                check(vm.index.live.any { it.id == gone.id }) { "The channel left the playlist" }
                app.repo.forgetDead()
                groupFinderOpen = true
                shoot("phone-live-3-finder")
                groupFinderTyped = "s"
                shoot("phone-live-4-finder-typed")
            } finally {
                groupFinderOpen = false
                groupFinderTyped = ""
                vm.liveFolder = null
            }
        }
    }
}
