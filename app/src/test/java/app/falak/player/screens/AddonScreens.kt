package app.falak.player.screens

import app.falak.player.data.Addon
import app.falak.player.data.AddonCatalog
import app.falak.player.data.AddonItem
import app.falak.player.data.AddonMeta
import app.falak.player.data.AddonResource
import app.falak.player.data.AddonStream
import app.falak.player.data.AddonStreams
import app.falak.player.data.AddonVideo
import app.falak.player.data.Addons
import app.falak.player.ui.LibraryViewModel
import app.falak.player.ui.Screen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Add-ons page (GitHub version; the tests run the Play one, so it's switched on here). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddonScreens : ScreenshotBase() {
    private val base = "https://films.example.com"
    private val addon = Addon(
        url = base,
        id = "example.films",
        name = "Example Films",
        description = "Old films in the public domain",
        logo = "",
        types = listOf("movie", "series"),
        catalogs = listOf(
            AddonCatalog("movie", "classics", "Classics", searchable = true, needsExtra = false),
            AddonCatalog("series", "shows", "Shows", searchable = true, needsExtra = false),
        ),
        resources = listOf(AddonResource("catalog", null, null), AddonResource("meta", null, null), AddonResource("stream", null, null)),
        idPrefixes = null,
        configurationRequired = false,
    )
    private val films = listOf("Night of the Living Dead", "His Girl Friday", "Charade", "The General", "Nosferatu", "Metropolis")
        .mapIndexed { i, n -> AddonItem(base, "f$i", "movie", n, "", (1922 + i * 7).toString(), "7.${i + 1}") }
    private val shows = listOf("The Lucy Show", "Bonanza", "Dragnet", "The Cisco Kid")
        .mapIndexed { i, n -> AddonItem(base, "s$i", "series", n, "", (1951 + i * 4).toString(), "") }
    private val rows = mapOf(
        "$base|movie|classics" to films,
        "$base|series|shows" to shows,
    )
    private val film = films[2]
    private val filmMeta = AddonMeta(
        id = film.id, type = "movie", name = film.name, poster = "", background = "",
        description = "Romance and suspense in Paris as a woman is pursued by several men who want a fortune her murdered husband had stolen.",
        year = "1963", runtime = "113 min", genres = listOf("Comedy", "Mystery", "Romance"), rating = "7.8", videos = emptyList(),
    )
    private val filmLinks = AddonStreams(
        playable = listOf(
            AddonStream("Example Films", "https://cdn.example.com/charade-1080.mp4", "1080p", "Charade.1963.1080p.mp4\n2.4 GB", "", ""),
            AddonStream("Example Films", "https://cdn.example.com/charade-720.mp4", "720p", "Charade.1963.720p.mp4\n1.1 GB", "", ""),
        ),
        torrents = 6,
        others = 1,
        failed = emptyList(),
    )
    private val show = shows[1]
    private val showMeta = AddonMeta(
        id = show.id, type = "series", name = show.name, poster = "", background = "",
        description = "The Cartwright family runs a ranch near Virginia City, Nevada.",
        year = "1959–1973", runtime = "", genres = listOf("Western"), rating = "", videos =
        (1..6).map { e -> AddonVideo("${show.id}:1:$e", "Episode title $e", 1, e, "1959-09-${10 + e}", "") } +
            (1..3).map { e -> AddonVideo("${show.id}:2:$e", "Second season $e", 2, e, "1960-09-${10 + e}", "") },
    )

    private fun LibraryViewModel.showAddons() {
        updateSettings { it.copy(addons = listOf(base)) }
        addons.showForTest(listOf(addon), rows)
        screen = Screen.ADDONS
    }

    @Test @Config(qualifiers = ScreenshotBase.PHONE)
    fun phoneAddons() = mainScreens("phone", look = "sidebar") { vm, shoot ->
        Addons.enabledForTest = true
        try {
            vm.screen = Screen.ADDONS
            shoot("phone-90-addons-none")
            vm.showAddons()
            shoot("phone-91-addons")
            vm.addons.showForTest(listOf(addon), rows, open = film, meta = filmMeta, streams = filmLinks, streamsTitle = film.name)
            shoot("phone-92-addon-film")
            vm.addons.showForTest(listOf(addon), rows, open = show, meta = showMeta)
            shoot("phone-93-addon-series")
        } finally {
            Addons.enabledForTest = false
        }
    }

    // A phone on its side: the rail, with Add-ons on it. (Like the other tests with the rail,
    // "watching": the Home behind it settles; without it the screen wasn't redrawn in tests.)
    @Test @Config(qualifiers = ScreenshotBase.PHONE_LAND)
    fun phoneLandAddons() = mainScreens("phone", look = "sidebar", watching = true) { vm, shoot ->
        Addons.enabledForTest = true
        try {
            vm.showAddons()
            shoot("phone-94-addons-landscape")
            check(vm.screen == Screen.ADDONS) { "Went back to ${vm.screen}" }
        } finally {
            Addons.enabledForTest = false
        }
    }

    @Test @Config(qualifiers = ScreenshotBase.TV)
    fun tvAddons() = mainScreens("tv", look = "sidebar", watching = true) { vm, shoot ->
        Addons.enabledForTest = true
        try {
            vm.showAddons()
            shoot("tv-91-addons")
            check(vm.screen == Screen.ADDONS) { "Went back to ${vm.screen}" }
            vm.addons.showForTest(listOf(addon), rows, open = film, meta = filmMeta, streams = filmLinks, streamsTitle = film.name)
            shoot("tv-92-addon-film")
        } finally {
            Addons.enabledForTest = false
        }
    }
}
