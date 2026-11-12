package app.falak.player.screens

import app.falak.player.data.Channel
import app.falak.player.data.Kind
import app.falak.player.data.Season
import app.falak.player.data.SeriesInfo
import app.falak.player.data.mine
import app.falak.player.data.progressKey
import app.falak.player.ui.LibraryViewModel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

abstract class TitlePageShots : ScreenshotBase() {
/** A series with two seasons, the second episode half watched (so the page offers to resume it). */
protected fun openSampleSeries(vm: LibraryViewModel) {
    val show = vm.index.series.first()
    fun episodes(season: Int) = (1..5).map { n ->
        Channel(
            id = "e$season$n", name = "Episode $n: The Tide Comes In", group = "Season $season",
            url = "http://example.invalid/series/u/p/$season$n.mp4", kind = Kind.SERIES, info = "S$season · E$n · 45 min",
        )
    }
    val first = episodes(1)
    val me = app.repo.state.value.mine(vm.playlist!!.id)
    app.repo.saveProgress(progressKey(me, first[1].id), 20 * 60_000L, 45 * 60_000L, first[1])
    vm.showSeriesForTest(
        show,
        SeriesInfo(
            plot = "A harbour town and the families who keep its lights burning, through one long winter of storms, debts and a ship that should not have come back.",
            cover = show.logo,
            seasons = listOf(Season(1, first), Season(2, episodes(2))),
            genre = "Drama",
            cast = "Mara Lindqvist, Tom Adeyemi, Ines Varga",
            year = "2023",
            rating = "8.1",
        ),
    )
}
}

/** A film's page and a series' page on a TV (8.19): the picture behind, the name over it, the rows under. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvTitlePageScreens : TitlePageShots() {
    @Test @Config(qualifiers = ScreenshotBase.TV)
    fun tvTitlePages() = mainScreens("tv", watching = true) { vm, shoot ->
        vm.openDetails(vm.index.movies.first())
        shoot("tv-title-1-film")
        openSampleSeries(vm)
        shoot("tv-title-2-series")
    }
}

/** The same on a phone held upright: the poster edge to edge, the name over its lower part. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneTitlePageScreens : TitlePageShots() {
    @Test @Config(qualifiers = ScreenshotBase.PHONE)
    fun phoneTitlePages() = mainScreens("phone", watching = true) { vm, shoot ->
        vm.openDetails(vm.index.movies.first())
        shoot("phone-title-1-film")
        openSampleSeries(vm)
        shoot("phone-title-2-series")
    }
}
