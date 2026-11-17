package app.falak.player.screens

import app.falak.player.data.Kind
import app.falak.player.ui.Section
import app.falak.player.ui.discoverForTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 8.34: Home with the sports row, a pinned group, popular now and "Because you watched"; the
 * Films page with its collections; a film listed three times (its versions) and its collection.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneDiscoverScreens : ScreenshotBase() {
    @Test @Config(qualifiers = ScreenshotBase.PHONE_TALL)
    fun phoneDiscover() {
        discoverSample = true
        discoverForTest = mapOf(
            "popular-MOVIE" to listOf("Glass Orchard", "Salt Road", "Paper Kites"),
            "popular-SERIES" to listOf("Signal Point", "Harbour Lights"),
            "because" to listOf("Midnight Ferry", "The Quiet Year", "Copper Valley"),
        )
        try {
            mainScreens("phone", watching = true) { vm, shoot ->
                vm.togglePin(Kind.LIVE, "Music")
                shoot("phone-98-home-discover")
                shoot("phone-98-home-discover")
                vm.section = Section.MOVIES
                shoot("phone-98b-films-discover")
                shoot("phone-98b-films-discover")
                val harbour = vm.index.movies.first { it.name == "The Long Harbour" }
                vm.openDetails(harbour)
                shoot("phone-98c-film-versions")
                shoot("phone-98c-film-versions")
                vm.leaveTitle()
                vm.openDetails(vm.index.all.first { it.name == "Copper Valley 2" })
                shoot("phone-98d-film-collection")
                shoot("phone-98d-film-collection")
            }
        } finally {
            discoverSample = false
            discoverForTest = null
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvDiscoverScreens : ScreenshotBase() {
    @Test @Config(qualifiers = ScreenshotBase.TV)
    fun tvDiscover() {
        discoverSample = true
        try {
            mainScreens("tv") { vm, shoot ->
                vm.section = Section.LIVE
                shoot("tv-98-live-sports")
                shoot("tv-98-live-sports")
            }
        } finally {
            discoverSample = false
        }
    }
}
