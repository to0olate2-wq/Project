package app.falak.player.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import app.falak.player.FalakApp
import app.falak.player.data.Channel
import app.falak.player.data.Kind
import app.falak.player.data.Notice
import app.falak.player.data.NoticeGroup
import app.falak.player.data.LibraryIndex
import app.falak.player.data.Playlist
import app.falak.player.data.Program
import app.falak.player.data.SourceType
import app.falak.player.data.SyncMeta
import app.falak.player.data.KIDS_PROFILE
import app.falak.player.data.Profile
import app.falak.player.data.SyncPeer
import app.falak.player.data.Watch
import app.falak.player.data.progressKey
import app.falak.player.data.stableId
import app.falak.player.ui.LibraryViewModel
import app.falak.player.ui.MainActivity
import app.falak.player.ui.PlayerActivity
import app.falak.player.ui.Screen
import app.falak.player.ui.Section
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import app.falak.player.screens.ScreenshotBase.Companion.PHONE
import app.falak.player.screens.ScreenshotBase.Companion.PHONE_LAND
import app.falak.player.screens.ScreenshotBase.Companion.PHONE_TALL
import app.falak.player.screens.ScreenshotBase.Companion.TV
import app.falak.player.screens.ScreenshotBase.Companion.TV_TALL

/**
 * Draws the app's main screens with made-up sample channels (no network, no real broadcasters)
 * into PNGs under build/screens, for checking layout and spacing on TV and phone sizes.
 * One small class per screen group: Gradle starts a fresh JVM per test class (forkEvery = 1),
 * which Compose needs between Robolectric runs.
 * Run: ./gradlew testPlayDebugUnitTest --tests 'app.falak.player.screens.*'
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
abstract class ScreenshotBase {

    protected val app: FalakApp get() = ApplicationProvider.getApplicationContext()

    // ---------- helpers ----------

    protected fun mainScreens(
        layout: String,
        theme: String = "dark",
        hidden: List<String> = emptyList(),
        look: String = "classic",
        watching: Boolean = false,
        kids: List<String>? = null,
        kidsOn: Boolean = false,
        linked: Boolean = false,
        profiles: List<String> = emptyList(),
        block: (LibraryViewModel, (String) -> Unit) -> Unit,
    ) {
        seed(layout, theme, hidden, look, watching, kids, kidsOn, linked, profiles)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val vm = ViewModelProvider(activity)[LibraryViewModel::class.java]
        waitFor { vm.index.all.isNotEmpty() }
        app.repo.showGuideForTest(sampleGuide(vm.index.live))
        block(vm) { name -> capture(activity, name) }
    }

    protected fun player(
        tv: Boolean,
        live: Boolean,
        browser: Boolean = false,
        groups: Boolean = false,
        menu: Boolean = false,
        upNext: Boolean = false,
        block: ((String) -> Unit) -> Unit,
    ) {
        seed(if (tv) "tv" else "phone")
        val channels = sampleChannels()
        val queue = channels.filter { it.kind == (if (live) Kind.LIVE else Kind.MOVIE) }
        val repo = app.repo
        repo.playQueue = queue
        repo.currentPlaylist = repo.state.value.playlists.first()
        repo.currentIndex = LibraryIndex.build(channels)
        val intent = Intent(app, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_INDEX, 2)
        val activity = Robolectric.buildActivity(PlayerActivity::class.java, intent).setup().get()
        idle(500)
        activity.previewControls(StandIn(live, queue[2].name), browser, groups, menu)
        if (upNext) activity.previewUpNext(queue[3])
        block { name -> capture(activity, name) }
    }

    /** Saves sample playlist + settings, as if the user had set the app up. */
    private fun seed(
        layout: String,
        theme: String = "dark",
        hidden: List<String> = emptyList(),
        look: String = "classic",
        watching: Boolean = false,
        kids: List<String>? = null,
        kidsOn: Boolean = false,
        linked: Boolean = false,
        profiles: List<String> = emptyList(),
    ): Playlist {
        val dir = File(app.filesDir, "sample").apply { mkdirs() }
        val m3u = File(dir, "sample.m3u")
        m3u.writeText(buildM3u(dir))
        val p = Playlist("sample", "Home", SourceType.M3U_FILE, m3u.path)
        app.repo.update { s ->
            s.copy(
                playlists = listOf(p),
                lastPlaylistId = p.id,
                settings = s.settings.copy(
                    layout = layout, player = "exo", openLast = true, themeMode = theme,
                    hiddenGroups = mapOf(p.id to hidden), look = look,
                    kidsGroups = if (kids == null) emptyMap() else mapOf(p.id to kids),
                    kidsMode = kidsOn, pin = if (kidsOn) "1234" else "",
                    profilesOn = profiles.isNotEmpty(),
                ),
                profiles = profiles.mapIndexed { i, name -> Profile("p$i", name, color = i) },
                // Linked with two devices, sync paused (so nothing starts listening during the test).
                sync = if (!linked) SyncMeta() else SyncMeta(
                    deviceId = "this-device",
                    key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                    peers = mapOf(
                        "tv" to SyncPeer("Living room TV", System.currentTimeMillis() - 3 * 60_000L),
                        "phone" to SyncPeer("Pixel 8", System.currentTimeMillis() - 2 * 3_600_000L),
                    ),
                ),
                // Two films part-watched, for "Continue watching".
                progress = if (!watching) emptyMap() else films.take(2).withIndex().associate { (i, title) ->
                    val id = stableId("$title|http://127.0.0.1:1/movie/u/p/$i.mp4")
                    progressKey(p.id, id) to Watch(pos = (20 + i * 25) * 60_000L, dur = 110 * 60_000L)
                },
                favorites = if (!watching) emptyMap() else mapOf(p.id to listOf(liveId("Atlas News"))),
                recents = if (!watching) emptyMap() else mapOf(p.id to listOf(liveId("Atlas News"), liveId("Pulse Sports 1"))),
                // What's new: one fresh entry and one seen earlier.
                notices = if (!watching) emptyList() else listOf(
                    Notice(
                        id = System.currentTimeMillis() - 20 * 60_000L, playlistId = p.id, playlistName = p.name,
                        live = 2, films = 3, series = 0,
                        groups = listOf(
                            NoticeGroup(Kind.MOVIE, "TOP MOVIES 4K 3840P DOLBY AUDIO (MULTI-SUBS)", 2),
                            NoticeGroup(Kind.MOVIE, "Drama", 1),
                            NoticeGroup(Kind.LIVE, "Sports", 2),
                        ),
                        items = films.take(3).withIndex().map { (i, t) -> stableId("$t|http://127.0.0.1:1/movie/u/p/$i.mp4") } +
                            listOf(liveId("Arena Live"), liveId("Fairway Golf")),
                    ),
                    Notice(
                        id = System.currentTimeMillis() - 26 * 3_600_000L, playlistId = p.id, playlistName = p.name,
                        live = 0, films = 0, series = 4, read = true,
                    ),
                ),
            )
        }
        return p
    }

    private fun capture(activity: Activity, name: String) {
        // Pictures load on background threads: give them real time as well as frames.
        repeat(6) {
            idle(400)
            Thread.sleep(150)
        }
        val root = activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("screens.dir") ?: "build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun idle(ms: Long) {
        val looper = shadowOf(Looper.getMainLooper())
        var left = ms
        while (left > 0) {
            looper.idleFor(Duration.ofMillis(50))
            Thread.sleep(10)
            left -= 50
        }
        looper.idle()
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 20_000
        while (!condition() && System.currentTimeMillis() < until) idle(100)
        check(condition()) { "Sample playlist didn't load" }
    }

    // ---------- sample content (all names invented) ----------

    private val liveGroups = linkedMapOf(
        "News" to listOf("Atlas News", "Meridian 24", "Harbor News", "Northwind World", "Civic Report"),
        "Sports" to listOf("Pulse Sports 1", "Pulse Sports 2", "Arena Live", "Fairway Golf", "Racing Line"),
        "Kids" to listOf("Little Comet", "Doodle TV", "Paper Planes"),
        "Documentary" to listOf("Wild Earth", "Deep Blue", "Timeline", "Workshop"),
        "Music" to listOf("Echo Hits", "Velvet Lounge", "Cedar Classics"),
    )
    private val films = listOf(
        "The Long Harbour", "Northern Lights", "Paper Kites", "Glass Orchard",
        "Midnight Ferry", "Copper Valley", "Salt Road", "The Quiet Year",
    )
    private val palette = intArrayOf(
        0xFF2F5D8A.toInt(), 0xFF8A3B2F.toInt(), 0xFF2F7A5A.toInt(), 0xFF6B4C9A.toInt(),
        0xFFB07A2A.toInt(), 0xFF3A6E7A.toInt(), 0xFF7A2F5D.toInt(), 0xFF4D6B2F.toInt(),
    )

    private fun buildM3u(dir: File): String = buildString {
        append("#EXTM3U\n")
        var n = 0
        for ((group, names) in liveGroups) {
            for (name in names) {
                val id = slug(name)
                val logo = image(dir, "logo-$id.png", 240, 240, palette[n % palette.size], initials(name), round = true)
                append("#EXTINF:-1 tvg-id=\"$id\" tvg-logo=\"file://${logo.path}\" group-title=\"$group\",$name\n")
                append("http://127.0.0.1:1/live/$id.m3u8\n")
                n++
            }
        }
        for ((i, title) in films.withIndex()) {
            val poster = image(dir, "poster-$i.png", 300, 450, palette[(i + 3) % palette.size], title, round = false)
            // A long provider-style group name: the shelf title must stay on one line.
            val group = if (i % 2 == 0) "Drama" else "TOP MOVIES 4K 3840P DOLBY AUDIO (MULTI-SUBS)"
            append("#EXTINF:-1 tvg-logo=\"file://${poster.path}\" group-title=\"$group\",$title\n")
            append("http://127.0.0.1:1/movie/u/p/$i.mp4\n")
        }
    }

    private fun sampleChannels(): List<Channel> {
        val out = ArrayList<Channel>()
        for ((group, names) in liveGroups) for (name in names) {
            val logo = File(app.filesDir, "sample/logo-${slug(name)}.png")
            out += Channel(
                id = slug(name), name = name, group = group, url = "http://127.0.0.1:1/live/${slug(name)}.m3u8",
                tvgId = slug(name), logo = if (logo.exists()) "file://${logo.path}" else "",
            )
        }
        for ((i, title) in films.withIndex()) {
            out += Channel(id = "film$i", name = title, group = "Drama", url = "http://127.0.0.1:1/movie/u/p/$i.mp4", kind = Kind.MOVIE)
        }
        return out
    }

    private fun sampleGuide(live: List<Channel>): Map<String, List<Program>> {
        val shows = listOf(
            "Morning Edition", "World Today", "The Big Match", "Highlights", "Nature Hour", "Ocean Life",
            "Studio Session", "Evening Report", "Cartoon Club", "Inside Story", "Late Review", "Weekend Live",
        )
        val now = System.currentTimeMillis()
        val start0 = now - now % (30 * 60_000L) - 60 * 60_000L
        return live.withIndex().associate { (i, ch) ->
            var t = start0 - (i % 3) * 15 * 60_000L
            val list = ArrayList<Program>()
            var k = i
            while (t < now + 6 * 3_600_000L) {
                val len = (30 + (k % 3) * 30) * 60_000L
                list += Program(t, t + len, shows[k % shows.size], "A made-up programme for screenshots.")
                t += len
                k++
            }
            ch.epgKey to list
        }
    }

    /** The id the M3U parser gives a sample live channel. */
    private fun liveId(name: String) = stableId("$name|http://127.0.0.1:1/live/${slug(name)}.m3u8")

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), ".").trim('.')
    private fun initials(s: String) = s.split(' ').filter { it.isNotEmpty() && it[0].isLetter() }.take(2).joinToString("") { it.take(1) }

    /** A simple generated logo (round = rounded square with initials) or poster (gradient + title). */
    private fun image(dir: File, name: String, w: Int, h: Int, color: Int, text: String, round: Boolean): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (round) {
            paint.color = color
            c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), w * 0.22f, w * 0.22f, paint)
            paint.color = Color.WHITE
            paint.textSize = w * 0.36f
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER
            c.drawText(text, w / 2f, h / 2f + paint.textSize * 0.35f, paint)
        } else {
            paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), color, Color.BLACK, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            paint.shader = null
            paint.color = Color.WHITE
            paint.textSize = w * 0.11f
            paint.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            var y = h * 0.72f
            for (word in text.split(' ')) {
                c.drawText(word, w * 0.08f, y, paint)
                y += paint.textSize * 1.15f
            }
        }
        val f = File(dir, name)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    /** A player that pretends to be playing a film (or a live channel), so the controls look real. */
    private class StandIn(private val live: Boolean, private val title: String) : SimpleBasePlayer(Looper.getMainLooper()) {
        override fun getState(): State {
            val item = MediaItem.Builder().setMediaId(title)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()
            val data = MediaItemData.Builder("x")
                .setMediaItem(item)
                .setDurationUs(if (live) androidx.media3.common.C.TIME_UNSET else 5_520_000_000L)
                .setIsSeekable(!live)
                .setIsDynamic(live)
                .setLiveConfiguration(if (live) MediaItem.LiveConfiguration.UNSET else null)
                .build()
            return State.Builder()
                .setAvailableCommands(
                    Player.Commands.Builder().addAll(
                        Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SET_SPEED_AND_PITCH,
                    ).build(),
                )
                .setPlaylist(listOf(data))
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(Player.STATE_READY)
                .setContentPositionMs(if (live) 0 else 1_394_000)
                .build()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = Futures.immediateVoidFuture()
    }

    companion object {
        const val TV = "w960dp-h540dp-land-xhdpi"
        const val TV_TALL = "w960dp-h2400dp-port-xhdpi"
        const val PHONE = "w411dp-h891dp-port-xxhdpi"
        const val PHONE_TALL = "w411dp-h2600dp-port-xxhdpi"
        const val PHONE_LAND = "w891dp-h411dp-land-xxhdpi"
    }
}

// ---------- TV (1920 × 1080) ----------

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvHomeScreens : ScreenshotBase() {
    @Test @Config(qualifiers = TV)
    fun tvHome() = mainScreens("tv") { vm, shoot ->
        shoot("tv-01-home")
        vm.section = Section.LIVE
        shoot("tv-02-live")
        vm.section = Section.MOVIES
        shoot("tv-03-films")
        vm.openDetails(vm.index.movies.first())
        shoot("tv-13-film-details")
        vm.screen = Screen.GUIDE
        shoot("tv-04-guide")
        vm.screen = Screen.PLAYLISTS
        shoot("tv-05-playlists")
        vm.screen = Screen.SYNC
        shoot("tv-09-devices")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvLightScreens : ScreenshotBase() {
    @Test @Config(qualifiers = TV)
    fun tvLight() = mainScreens("tv", theme = "light") { vm, shoot ->
        shoot("tv-07-home-light")
        vm.section = Section.LIVE
        shoot("tv-08-live-light")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvSettingsScreens : ScreenshotBase() {
    @Test @Config(qualifiers = TV_TALL)
    fun tvSettings() = mainScreens("tv") { vm, shoot ->
        vm.screen = Screen.SETTINGS
        shoot("tv-06-settings-full")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvPlayerFilmScreens : ScreenshotBase() {
    @Test @Config(qualifiers = TV)
    fun tvPlayerFilm() = player(tv = true, live = false) { shoot -> shoot("tv-10-player-film") }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvPlayerLiveScreens : ScreenshotBase() {
    @Test @Config(qualifiers = TV)
    fun tvPlayerLive() = player(tv = true, live = true) { shoot -> shoot("tv-11-player-live") }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvChannelListScreens : ScreenshotBase() {
    @Test @Config(qualifiers = TV)
    fun tvChannelList() = player(tv = true, live = true, browser = true) { shoot -> shoot("tv-12-channel-list") }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvChannelGroupsScreens : ScreenshotBase() {
    @Test @Config(qualifiers = TV)
    fun tvChannelGroups() = player(tv = true, live = true, browser = true, groups = true) { shoot -> shoot("tv-14-channel-list-groups") }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvPlayerMenuScreens : ScreenshotBase() {
    @Test @Config(qualifiers = TV)
    fun tvPlayerMenu() = player(tv = true, live = true, menu = true) { shoot -> shoot("tv-18-player-menu") }
}

// ---------- Sidebar look ----------

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvSidebarScreens : ScreenshotBase() {
    @Test @Config(qualifiers = TV)
    fun tvSidebar() = mainScreens("tv", look = "sidebar", watching = true) { vm, shoot ->
        shoot("tv-15-sidebar-home")
        vm.section = Section.MOVIES
        shoot("tv-16-sidebar-films")
        vm.section = Section.LIVE
        shoot("tv-17-sidebar-live")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneSidebarScreens : ScreenshotBase() {
    @Test @Config(qualifiers = PHONE)
    fun phoneSidebar() = mainScreens("phone", look = "sidebar", watching = true) { vm, shoot ->
        shoot("phone-13-sidebar-home")
        vm.section = Section.MOVIES
        shoot("phone-14-sidebar-films")
        vm.screen = Screen.NOTICES
        shoot("phone-16-whats-new")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneSidebarLightScreens : ScreenshotBase() {
    @Test @Config(qualifiers = PHONE_LAND)
    fun phoneSidebarLand() = mainScreens("phone", theme = "light", look = "sidebar", watching = true) { vm, shoot ->
        vm.section = Section.LIVE
        shoot("phone-15-sidebar-live-landscape-light")
    }
}

// ---------- Phone (1080 × 2340) ----------

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneHomeScreens : ScreenshotBase() {
    @Test @Config(qualifiers = PHONE)
    fun phoneHome() = mainScreens("phone") { vm, shoot ->
        shoot("phone-01-home")
        vm.section = Section.LIVE
        shoot("phone-02-live")
        vm.openDetails(vm.index.movies.first())
        shoot("phone-03-film-details")
        vm.screen = Screen.PLAYLISTS
        shoot("phone-05-playlists")
        vm.screen = Screen.SYNC
        shoot("phone-09-devices")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneLightScreens : ScreenshotBase() {
    @Test @Config(qualifiers = PHONE)
    fun phoneLight() = mainScreens("phone", theme = "light") { vm, shoot ->
        shoot("phone-07-home-light")
        vm.screen = Screen.GUIDE
        shoot("phone-08-guide-light")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneShelfScreens : ScreenshotBase() {
    // A very tall phone shows every Home shelf, including the films group with a long
    // provider-style name: its title must stay on one line.
    @Test @Config(qualifiers = PHONE_TALL)
    fun phoneShelves() = mainScreens("phone") { _, shoot -> shoot("phone-11-home-all-shelves") }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneHiddenGroupsScreens : ScreenshotBase() {
    // Hidden groups leave Home entirely, slideshow included. (Set before the screen opens:
    // changing Home after it is drawn stalls Robolectric.)
    @Test @Config(qualifiers = PHONE_TALL)
    fun phoneHidden() = mainScreens("phone", hidden = listOf("Drama", "News")) { _, shoot ->
        shoot("phone-12-home-drama-news-hidden")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneKidsScreens : ScreenshotBase() {
    // Kids mode: only the chosen groups, and a Kids badge in place of the bell.
    @Test @Config(qualifiers = PHONE_TALL)
    fun phoneKids() = mainScreens("phone", look = "sidebar", kids = listOf("Kids", "Music"), kidsOn = true) { vm, shoot ->
        // Kids mode on: the app opens on "Who's watching?".
        shoot("phone-19-profiles")
        vm.chooseProfile(KIDS_PROFILE)
        shoot("phone-13-kids-home")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvProfilesScreens : ScreenshotBase() {
    @Test @Config(qualifiers = TV)
    fun tvProfiles() = mainScreens("tv", kids = listOf("Kids"), kidsOn = true) { _, shoot ->
        shoot("tv-21-profiles")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvKidsGroupsScreens : ScreenshotBase() {
    @Test @Config(qualifiers = TV)
    fun tvKidsGroups() = mainScreens("tv", kids = listOf("Kids")) { vm, shoot ->
        vm.screen = Screen.KIDS
        shoot("tv-14-kids-groups")
        // Locked groups: the adult lock sits on this page, not on Settings.
        vm.screen = Screen.LOCKS
        shoot("tv-22-locked-groups")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvGuidePreviewScreens : ScreenshotBase() {
    // A channel picked once in the guide plays small above it.
    @Test @Config(qualifiers = TV)
    fun tvGuidePreview() = mainScreens("tv") { vm, shoot ->
        vm.guidePreview = vm.index.live.first { it.name == "Atlas News" }
        vm.screen = Screen.GUIDE
        shoot("tv-19-guide-preview")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvGuideSearchScreens : ScreenshotBase() {
    @Test @Config(qualifiers = TV)
    fun tvGuideSearch() = mainScreens("tv") { vm, shoot ->
        vm.guideSearch = "Match"
        vm.screen = Screen.GUIDE
        shoot("tv-20-guide-search")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneGuidePreviewScreens : ScreenshotBase() {
    @Test @Config(qualifiers = PHONE_TALL)
    fun phoneGuidePreview() = mainScreens("phone") { vm, shoot ->
        vm.guidePreview = vm.index.live.first { it.name == "Pulse Sports 1" }
        vm.screen = Screen.GUIDE
        shoot("phone-17-guide-preview")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneLinkedScreens : ScreenshotBase() {
    // Your devices once linked: who with, when it last synced.
    @Test @Config(qualifiers = PHONE_TALL)
    fun phoneLinked() = mainScreens("phone", linked = true) { vm, shoot ->
        vm.screen = Screen.SYNC
        shoot("phone-18-devices-linked")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvUpNextScreens : ScreenshotBase() {
    // The next episode in the corner while the credits roll.
    @Test @Config(qualifiers = TV)
    fun tvUpNext() = player(tv = true, live = false, upNext = true) { shoot -> shoot("tv-23-up-next") }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvMoreProfilesScreens : ScreenshotBase() {
    // Extra profiles and kids mode: everyone in "Who's watching?".
    @Test @Config(qualifiers = TV)
    fun tvMoreProfiles() = mainScreens("tv", kids = listOf("Kids"), kidsOn = true, profiles = listOf("Sara", "Omar")) { _, shoot ->
        shoot("tv-24-profiles-more")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneProfilesPageScreens : ScreenshotBase() {
    @Test @Config(qualifiers = PHONE_TALL)
    fun phoneProfilesPage() = mainScreens("phone", profiles = listOf("Sara", "Omar")) { vm, shoot ->
        vm.screen = Screen.PROFILES
        vm.chooseProfile("")
        shoot("phone-20-profiles-page")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneSettingsScreens : ScreenshotBase() {
    @Test @Config(qualifiers = PHONE_TALL)
    fun phoneSettings() = mainScreens("phone") { vm, shoot ->
        vm.screen = Screen.SETTINGS
        shoot("phone-06-settings-full")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhonePlayerScreens : ScreenshotBase() {
    @Test @Config(qualifiers = PHONE_LAND)
    fun phonePlayerFilm() = player(tv = false, live = false) { shoot -> shoot("phone-10-player-film-landscape") }
}
