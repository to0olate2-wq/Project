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
import app.falak.player.ui.SettingsPage
import app.falak.player.ui.addAddonForTest
import app.falak.player.torrent.TorrentSource
import app.falak.player.torrent.Torrents
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Add-ons page (GitHub version; the tests run the Play one, so it's switched on here).
 * One test per class: in a second test of the same class the screen stopped being redrawn
 * (every picture showed the Home it opened on).
 */
abstract class AddonShots : ScreenshotBase() {
    protected val base = "https://films.example.com"
    protected val addon = Addon(
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
    protected val films = listOf("Night of the Living Dead", "His Girl Friday", "Charade", "The General", "Nosferatu", "Metropolis")
        .mapIndexed { i, n -> AddonItem(base, "f$i", "movie", n, "", (1922 + i * 7).toString(), "7.${i + 1}") }
    protected val shows = listOf("The Lucy Show", "Bonanza", "Dragnet", "The Cisco Kid")
        .mapIndexed { i, n -> AddonItem(base, "s$i", "series", n, "", (1951 + i * 4).toString(), "") }
    protected val rows = mapOf(
        "$base|movie|classics" to films,
        "$base|series|shows" to shows,
    )
    protected val film = films[2]
    protected val filmMeta = AddonMeta(
        id = film.id, type = "movie", name = film.name, poster = "", background = "",
        description = "Romance and suspense in Paris as a woman is pursued by several men who want a fortune her murdered husband had stolen.",
        year = "1963", runtime = "113 min", genres = listOf("Comedy", "Mystery", "Romance"), rating = "7.8", videos = emptyList(),
    )
    protected val filmLinks = AddonStreams(
        playable = listOf(
            AddonStream("Example Films", "https://cdn.example.com/charade-1080.mp4", "1080p", "Charade.1963.1080p.mp4\n2.4 GB", "", ""),
            AddonStream("Example Films", "https://cdn.example.com/charade-720.mp4", "720p", "Charade.1963.720p.mp4\n1.1 GB", "", ""),
        ),
        torrents = 6,
        others = 1,
        failed = emptyList(),
    )
    protected val show = shows[1]
    protected val showMeta = AddonMeta(
        id = show.id, type = "series", name = show.name, poster = "", background = "",
        description = "The Cartwright family runs a ranch near Virginia City, Nevada.",
        year = "1959–1973", runtime = "", genres = listOf("Western"), rating = "", videos =
        (1..6).map { e -> AddonVideo("${show.id}:1:$e", "Episode title $e", 1, e, "1959-09-${10 + e}", "") } +
            (1..3).map { e -> AddonVideo("${show.id}:2:$e", "Second season $e", 2, e, "1960-09-${10 + e}", "") },
    )

    protected fun LibraryViewModel.showAddons() {
        updateSettings { it.copy(addons = listOf(base)) }
        addons.showForTest(listOf(addon), rows)
        screen = Screen.ADDONS
    }

    /** A second add-on, for the lists of add-ons (Playlists page, Settings). */
    protected val oldTv = addon.copy(
        url = "https://oldtv.example.net/abc123",
        id = "org.example.oldtv",
        name = "Old TV",
        description = "Series from the fifties",
        types = listOf("series", "tv"),
        catalogs = emptyList(),
    )

    protected fun LibraryViewModel.showTwoAddons() {
        updateSettings { it.copy(addons = listOf(base, oldTv.url)) }
        addons.showForTest(listOf(addon, oldTv), rows)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneAddonScreens : AddonShots() {
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
}

// A phone on its side: the rail, with Add-ons on it.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneLandAddonScreens : AddonShots() {
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
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvAddonScreens : AddonShots() {
    @Test @Config(qualifiers = ScreenshotBase.TV)
    fun tvAddons() = mainScreens("tv", look = "sidebar", watching = true) { vm, shoot ->
        Addons.enabledForTest = true
        try {
            vm.screen = Screen.ADDONS
            shoot("tv-90-addons-none")
            vm.showAddons()
            shoot("tv-91-addons")
            check(vm.screen == Screen.ADDONS) { "Went back to ${vm.screen}" }
            vm.addons.showForTest(listOf(addon), rows, open = film, meta = filmMeta, streams = filmLinks, streamsTitle = film.name)
            shoot("tv-92-addon-film")
            // Added with the playlists: listed under them.
            vm.addons.closeItem()
            vm.showTwoAddons()
            vm.screen = Screen.PLAYLISTS
            shoot("tv-93-playlists-addons")
        } finally {
            Addons.enabledForTest = false
        }
    }
}

// The Playlists page: add-ons listed under the playlists, and the Add window on its Add-on choice.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhonePlaylistsAddonScreens : AddonShots() {
    @Test @Config(qualifiers = ScreenshotBase.PHONE)
    fun phonePlaylistsAddons() = mainScreens("phone") { vm, shoot ->
        Addons.enabledForTest = true
        try {
            vm.showTwoAddons()
            vm.screen = Screen.PLAYLISTS
            shoot("phone-95-playlists-addons")
            addAddonForTest = true
            shoot("phone-96-add-addon")
        } finally {
            addAddonForTest = false
            Addons.enabledForTest = false
        }
    }
}

// Settings → Playlists: the Add-ons part (tall, so the whole page shows).
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneSettingsAddonScreens : AddonShots() {
    @Test @Config(qualifiers = ScreenshotBase.PHONE_TALL)
    fun phoneSettingsAddons() = mainScreens("phone") { vm, shoot ->
        Addons.enabledForTest = true
        try {
            vm.showTwoAddons()
            vm.screen = Screen.SETTINGS
            vm.settingsPage = SettingsPage.PLAYLISTS
            shoot("phone-97-settings-addons")
        } finally {
            Addons.enabledForTest = false
        }
    }
}

// Torrent links (8.16, GitHub version): listed with the other links, and the window while one gets ready.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneTorrentScreens : AddonShots() {
    @Test @Config(qualifiers = ScreenshotBase.PHONE)
    fun phoneTorrents() = mainScreens("phone", look = "sidebar") { vm, shoot ->
        Addons.enabledForTest = true
        Torrents.availableForTest = true
        try {
            vm.showAddons()
            val big = TorrentSource("a".repeat(40), 0)
            val links = AddonStreams(
                playable = emptyList(),
                torrents = 2,
                others = 0,
                failed = emptyList(),
                torrentLinks = listOf(
                    AddonStream("Torrentio", "", "Torrentio\n4k HDR", "Charade.1963.2160p.HDR.mkv\n45 sharing · 15.2 GB", "", "", big),
                    AddonStream("Torrentio", "", "Torrentio\n1080p", "Charade.1963.1080p.BluRay.mkv\n120 sharing · 2.4 GB", "", "", TorrentSource("b".repeat(40), 0)),
                ),
            )
            vm.addons.showForTest(listOf(addon), rows, open = film, meta = filmMeta, streams = links, streamsTitle = film.name)
            shoot("phone-98-addon-torrent-links")
            Torrents.state.value = Torrents.State(
                Torrents.Stage.BUFFERING, Torrents.keyOf(big),
                name = "Charade.1963.2160p.HDR.mkv", size = 15_200_000_000L, peers = 45, bytesPerSecond = 2_400_000, progress = 0.6f,
            )
            vm.addons.showPreparingForTest(links.torrentLinks[0])
            shoot("phone-99-torrent-getting-ready")
        } finally {
            Torrents.state.value = Torrents.State()
            Torrents.availableForTest = false
            Addons.enabledForTest = false
        }
    }
}
