package app.falak.player.screens

import app.falak.player.ui.Screen
import app.falak.player.ui.Section
import app.falak.player.ui.SettingsPage
import app.falak.player.ui.cinemaSearchOpen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Cinema look on a TV (8.18): the menu as words along the top, the picture behind everything,
 * the panel with its buttons, and "Up next" as the first row (the card the remote is on is set
 * here as the remote would).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvCinemaScreens : ScreenshotBase() {
    @Test @Config(qualifiers = ScreenshotBase.TV)
    fun tvCinema() = mainScreens("tv", watching = true) { vm, shoot ->
        try {
            app.repo.setLastLive(vm.playlist!!.id, vm.index.live.first().id)
            vm.updateSettings { it.copy(appLook = "cinema") }
            shoot("tv-cinema-1-home")
            vm.spotlight[Section.HOME] = vm.index.movies.first()
            shoot("tv-cinema-2-film")
            vm.spotlight[Section.HOME] = vm.index.live.first()
            shoot("tv-cinema-3-channel")
            vm.section = Section.MOVIES
            shoot("tv-cinema-4-films")
            vm.section = Section.LIVE
            shoot("tv-cinema-5-live")
            vm.section = Section.HOME
            cinemaSearchOpen = true
            shoot("tv-cinema-6-search")
            cinemaSearchOpen = false
            vm.settingsPage = SettingsPage.APPEARANCE
            vm.screen = Screen.SETTINGS
            shoot("tv-cinema-7-settings")
        } finally {
            cinemaSearchOpen = false
        }
    }
}

/** The Cinema look on a phone held upright: the taller picture, My list, and the big Up next cards. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneCinemaScreens : ScreenshotBase() {
    @Test @Config(qualifiers = ScreenshotBase.PHONE)
    fun phoneCinema() = mainScreens("phone", watching = true) { vm, shoot ->
        app.repo.setLastLive(vm.playlist!!.id, vm.index.live.first().id)
        vm.updateSettings { it.copy(appLook = "cinema") }
        shoot("phone-cinema-1-home")
        vm.section = Section.MOVIES
        shoot("phone-cinema-2-films")
        vm.settingsPage = SettingsPage.APPEARANCE
        vm.screen = Screen.SETTINGS
        shoot("phone-cinema-3-look")
    }
}
