package app.falak.player.screens

import app.falak.player.data.PlayerInfo
import app.falak.player.screens.ScreenshotBase.Companion.PHONE
import app.falak.player.screens.ScreenshotBase.Companion.TV
import app.falak.player.ui.Screen
import app.falak.player.ui.Section
import app.falak.player.ui.SettingsPage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 8.45: another accent colour all through the app, and the two new settings. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneAccentScreens : ScreenshotBase() {
    @Test @Config(qualifiers = PHONE)
    fun phoneAccent() = mainScreens("phone", watching = true) { vm, shoot ->
        vm.updateSettings { it.copy(accent = "blue") }
        shoot("phone-accent-1-home")
        shoot("phone-accent-1-home")
        vm.section = Section.LIVE
        shoot("phone-accent-2-live")
        vm.settingsPage = SettingsPage.APPEARANCE
        vm.screen = Screen.SETTINGS
        shoot("phone-accent-3-colour")
        vm.settingsPage = SettingsPage.PLAYBACK
        shoot("phone-accent-4-player-info")
    }
}

/** The channel banner with only some of its parts (here: the clock, no number, no next). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvPlayerInfoScreens : ScreenshotBase() {
    @Test @Config(qualifiers = TV)
    fun tvPlayerInfo() = playerSteps(tv = true) { player, shoot ->
        val before = player.previewBanner()
        check(before.firstOrNull()?.isDigit() == true) { "The banner had no channel number: \"$before\"" }
        settle()
        shoot("tv-info-1-banner-default")
        app.repo.updateSettings { it.copy(accent = "green", playerInfo = listOf(PlayerInfo.CLOCK, PlayerInfo.NOW, PlayerInfo.LEFT)) }
        val after = player.previewBanner()
        check(after.firstOrNull()?.isDigit() != true) { "The channel number still shows: \"$after\"" }
        check(Regex("\\d{1,2}:\\d{2}").containsMatchIn(after)) { "No clock in the banner: \"$after\"" }
        settle()
        shoot("tv-info-2-banner-clock")
    }
}
