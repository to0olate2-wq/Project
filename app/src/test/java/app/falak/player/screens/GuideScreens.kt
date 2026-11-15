package app.falak.player.screens

import app.falak.player.BuildConfig
import app.falak.player.data.AppChanges
import app.falak.player.ui.Guide
import app.falak.player.ui.Screen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The welcome page, the guide over the main buttons and the update's guide (8.27). The guides
 * start by themselves here (Guide.auto), as in the app: the checks are on what started, and
 * where it points.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneGuideScreens : ScreenshotBase() {
    @Test @Config(qualifiers = ScreenshotBase.PHONE)
    fun phoneGuide(): Unit = run {
        mainScreens("phone", watching = true) { vm, shoot ->
            try {
                shoot("phone-guide-0-home")
                // After the welcome page: the guide to the buttons starts once the lists are on screen.
                Guide.auto = true
                vm.updateSettings { it.copy(tourDue = true, seenVersion = BuildConfig.VERSION_NAME) }
                shoot("phone-guide-1-home")
                val tour = Guide.steps ?: error("The guide didn't start")
                check(tour.first().title == "Home") { "It starts at ${tour.first().title}" }
                // The phone's bar: Home, Live, Films, Series, More; and the search box.
                val shown = tour.map { it.title }
                for (t in listOf("Home", "Live TV", "Films", "Series", "Search", "More")) check(t in shown) { "No $t in the guide: $shown" }
                Guide.next()
                shoot("phone-guide-2-live")
                repeat(3) { Guide.next() }
                shoot("phone-guide-5-search")
                while (Guide.steps != null && Guide.at < tour.lastIndex) Guide.next()
                shoot("phone-guide-9-player")
                Guide.next()
                shoot("phone-guide-done")
                check(Guide.steps == null) { "The guide is still on screen" }
                check(!vm.state.value.settings.tourDue) { "The guide would come again" }
                // After an update: where its big changes are, once.
                vm.updateSettings { it.copy(seenVersion = AppChanges.FIRST_NOTED) }
                shoot("phone-guide-update-1")
                val notes = Guide.steps ?: error("The update's guide didn't start")
                check(notes.first().title.startsWith("What's new in")) { "It starts at ${notes.first().title}" }
                repeat(notes.size - 1) { Guide.next() }
                shoot("phone-guide-update-last")
                Guide.next()
                shoot("phone-guide-update-done")
                check(vm.state.value.settings.seenVersion == BuildConfig.VERSION_NAME) { "The update's guide would come again" }
                // A new install: the welcome page, before any playlist.
                vm.screen = Screen.PLAYLISTS
                app.repo.update(stamp = false) { it.copy(playlists = emptyList(), settings = it.settings.copy(seenVersion = "")) }
                shoot("phone-welcome")
                shoot("phone-welcome")
            } finally {
                Guide.auto = false
                Guide.finish()
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvGuideScreens : ScreenshotBase() {
    @Test @Config(qualifiers = ScreenshotBase.TV)
    fun tvGuide(): Unit = run {
        mainScreens("tv", watching = true) { vm, shoot ->
            try {
                shoot("tv-guide-0-home")
                Guide.auto = true
                vm.updateSettings { it.copy(tourDue = true, seenVersion = BuildConfig.VERSION_NAME) }
                shoot("tv-guide-1-home")
                val tour = Guide.steps ?: error("The guide didn't start")
                val shown = tour.map { it.title }
                // The TV's menu at the left: the sections, the guide and Settings.
                for (t in listOf("Home", "Live TV", "Films", "Series", "TV guide", "Settings")) check(t in shown) { "No $t in the guide: $shown" }
                Guide.next()
                shoot("tv-guide-2-live")
                while (Guide.steps != null && tour[Guide.at].title != "Settings") Guide.next()
                shoot("tv-guide-settings")
                Guide.next()
                shoot("tv-guide-player")
                Guide.finish()
                vm.screen = Screen.PLAYLISTS
                app.repo.update(stamp = false) { it.copy(playlists = emptyList(), settings = it.settings.copy(seenVersion = "")) }
                shoot("tv-welcome")
                shoot("tv-welcome")
            } finally {
                Guide.auto = false
                Guide.finish()
            }
        }
    }
}
