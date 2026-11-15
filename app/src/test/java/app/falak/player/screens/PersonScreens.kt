package app.falak.player.screens

import app.falak.player.data.Trakt
import app.falak.player.data.TitleMatch
import app.falak.player.ui.LibraryViewModel
import app.falak.player.ui.PersonForTest
import app.falak.player.ui.PersonPage
import app.falak.player.ui.Screen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * An actor's page (8.29): reached from the cast of a series' page. Trakt's answers are stood in for
 * (PersonForTest): two of their films are in the sample playlist, the rest are not.
 */
abstract class PersonShots : TitlePageShots() {
    protected fun standIn(vm: LibraryViewModel): PersonPage {
        val mine = vm.index.movies.take(2)
        val films = mine.map { ch ->
            val (name, year) = TitleMatch.clean(ch.name)
            Trakt.Title("movie", name, year, "tt01" + ch.id.filter { it.isDigit() }.padStart(5, '0'), "", "Captain Ros")
        } + listOf(
            Trakt.Title("movie", "The Long Pier", 2019, "tt0900001", "", "Harbour master"),
            Trakt.Title("movie", "Salt and Iron", 2016, "tt0900002", "", "Ines"),
        )
        val series = listOf(Trakt.Title("series", "Northern Lights", 2021, "tt0900003", "", "Mara"))
        val page = PersonPage(
            person = Trakt.Person("Mara Lindqvist", "mara-lindqvist", "", ""),
            about = Trakt.About(
                bio = "Mara Lindqvist is a Swedish actor known for coastal dramas and quiet, stubborn characters. She trained in Gothenburg and worked in theatre for ten years before her first film.",
                born = "1984-03-02", died = "", place = "Gothenburg, Sweden", photo = "", job = "Acting",
            ),
            films = films,
            series = series,
            yours = Trakt.inLibrary(films + series, vm.index.movies + vm.index.series),
        )
        check(page.yours.size == 2) { "The two sample films weren't found in the playlist: ${page.yours}" }
        PersonForTest.page = page
        return page
    }

    protected fun shots(prefix: String, vm: LibraryViewModel, shoot: (String) -> Unit) {
        try {
            standIn(vm)
            openSampleSeries(vm)
            shoot("$prefix-person-1-cast")
            shoot("$prefix-person-1-cast")
            vm.openPerson("Mara Lindqvist")
            check(vm.screen == Screen.PERSON)
            shoot("$prefix-person-2-page")
            shoot("$prefix-person-2-page")
            vm.closePerson()
            check(vm.screen == Screen.SERIES) { "Back from the actor's page went to ${vm.screen}" }
        } finally {
            PersonForTest.page = null
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhonePersonScreens : PersonShots() {
    @Test @Config(qualifiers = ScreenshotBase.PHONE)
    fun phonePerson() = mainScreens("phone", watching = true) { vm, shoot -> shots("phone", vm, shoot) }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvPersonScreens : PersonShots() {
    @Test @Config(qualifiers = ScreenshotBase.TV)
    fun tvPerson() = mainScreens("tv", watching = true) { vm, shoot -> shots("tv", vm, shoot) }
}
