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
import app.falak.player.ProgramAlerts
import app.falak.player.data.ProgramAlert
import app.falak.player.data.Habits
import app.falak.player.data.Account
import app.falak.player.ui.MiniPlayerState
import app.falak.player.data.Reminder
import app.falak.player.data.Channel
import app.falak.player.data.Kind
import app.falak.player.data.Notice
import app.falak.player.data.NoticeGroup
import app.falak.player.data.LibraryIndex
import app.falak.player.data.Playlist
import app.falak.player.data.Program
import app.falak.player.data.SourceType
import app.falak.player.data.Store
import app.falak.player.data.PlaylistCache
import app.falak.player.data.SyncMeta
import app.falak.player.data.KIDS_PROFILE
import app.falak.player.data.Profile
import app.falak.player.data.SyncPeer
import app.falak.player.data.Watch
import app.falak.player.data.Settings
import app.falak.player.player.CaptionLayout
import app.falak.player.data.progressKey
import app.falak.player.data.stableId
import app.falak.player.RamadanNow
import app.falak.player.data.KidsClock
import app.falak.player.data.KidsTime
import app.falak.player.data.Ramadan
import app.falak.player.data.ServiceLogStore
import app.falak.player.data.ServiceReport
import app.falak.player.player.SleepTimer
import app.falak.player.ui.forceVoiceForTest
import app.falak.player.ui.LibraryViewModel
import app.falak.player.OfflineCenter
import app.falak.player.data.OfflineItem
import app.falak.player.data.OfflineState
import app.falak.player.data.Season
import app.falak.player.data.SeriesInfo
import app.falak.player.ui.ALL_FILTER
import app.falak.player.ui.MainActivity
import app.falak.player.ui.PlayerActivity
import app.falak.player.ui.Screen
import app.falak.player.ui.Section
import app.falak.player.ui.SettingsPage
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
        prayerMode: String = "off",
        liveOnly: Boolean = false,
        reminder: Boolean = false,
        /** Channels watched around this time on earlier days, for Home's "For this hour". */
        habits: Boolean = false,
        /** Provider-style names and duplicates, with Tidy channel list on. */
        messy: Boolean = false,
        /** An Xtream account as the provider would report it (Subscription watch). */
        account: Account? = null,
        /** A group of this year's Ramadan series (Ramadan mode's shelf). */
        ramadan: Boolean = false,
        block: (LibraryViewModel, (String) -> Unit) -> Unit,
    ) = watched {
        seed(layout, theme, hidden, look, watching, kids, kidsOn, linked, profiles, prayerMode, liveOnly, reminder, habits, messy, ramadan)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val vm = ViewModelProvider(activity)[LibraryViewModel::class.java]
        waitFor { vm.index.all.isNotEmpty() }
        app.repo.showGuideForTest(sampleGuide(vm.index.live))
        if (account != null) vm.showAccountForTest(account)
        block(vm) { name -> capture(activity, name) }
    }

    protected fun player(
        tv: Boolean,
        live: Boolean,
        browser: Boolean = false,
        groups: Boolean = false,
        menu: Boolean = false,
        upNext: Boolean = false,
        ambient: Boolean = false,
        prayer: Boolean = false,
        dual: Boolean = false,
        heardSecond: Boolean = false,
        alert: Boolean = false,
        /** "offer": "From the start" for the show on now; "replay": watching it from the start. */
        startOver: String? = null,
        /** Auto captions showing these words. */
        autoCaption: String? = null,
        block: ((String) -> Unit) -> Unit,
    ) = watched {
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
        if (dual) {
            activity.previewDual(StandIn(live, queue[2].name), StandIn(live, queue[6].name), queue[6], heardSecond)
        } else {
            activity.previewControls(StandIn(live, queue[2].name), browser, groups, menu)
        }
        if (alert) {
            val now = System.currentTimeMillis()
            ProgramAlerts.showForTest(
                ProgramAlert(
                    playlistId = "sample", channelId = queue[8].id, channelName = queue[8].name,
                    title = "The Big Match", start = now + 2 * 60_000L, stop = now + 2 * 3_600_000L,
                    word = "Big Match", alsoOn = 2,
                ),
            )
        }
        if (upNext) activity.previewUpNext(queue[3])
        if (ambient) activity.previewAmbient()
        if (prayer) activity.previewPrayer()
        if (startOver != null) activity.previewStartOver(queue[2], replaying = startOver == "replay")
        if (autoCaption != null) activity.previewAutoCaptions(autoCaption)
        block { name -> capture(activity, name) }
    }

    /**
     * The player on a live channel with a stand-in picture, for tests that take it step by step
     * ([block] gets the player and a camera); [settings]: changed before it opens.
     */
    protected fun playerSteps(
        tv: Boolean,
        settings: ((Settings) -> Settings)? = null,
        block: (PlayerActivity, (String) -> Unit) -> Unit,
    ) = watched {
        seed(if (tv) "tv" else "phone")
        if (settings != null) app.repo.updateSettings(settings)
        val channels = sampleChannels()
        val queue = channels.filter { it.kind == Kind.LIVE }
        val repo = app.repo
        repo.playQueue = queue
        repo.currentPlaylist = repo.state.value.playlists.first()
        repo.currentIndex = LibraryIndex.build(channels)
        val intent = Intent(app, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_INDEX, 2)
        val activity = Robolectric.buildActivity(PlayerActivity::class.java, intent).setup().get()
        idle(500)
        activity.previewControls(StandIn(true, queue[2].name))
        idle(300)
        block(activity) { name -> capture(activity, name) }
    }

    /** Lets the player lay itself out (after the picture's shape changed). */
    protected fun settle() = idle(400)

    /**
     * A screen test that hangs (it happens now and then on CI) writes where every thread is after
     * two and a half minutes, as hang-<test>.txt beside the pictures, to find out why.
     */
    private fun watched(block: () -> Unit) {
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val name = javaClass.simpleName
        Thread {
            try {
                Thread.sleep(150_000)
            } catch (e: InterruptedException) {
                return@Thread
            }
            if (done.get()) return@Thread
            val dir = File(System.getProperty("screens.dir") ?: "build/screens").apply { mkdirs() }
            val text = Thread.getAllStackTraces().entries.joinToString("\n\n") { (t, stack) ->
                "${t.name} (${t.state})\n" + stack.joinToString("\n") { "    at $it" }
            }
            runCatching { File(dir, "hang-$name.txt").writeText(text) }
        }.apply { isDaemon = true }.start()
        try {
            block()
        } finally {
            done.set(true)
        }
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
        prayerMode: String = "off",
        liveOnly: Boolean = false,
        reminder: Boolean = false,
        habits: Boolean = false,
        messy: Boolean = false,
        ramadan: Boolean = false,
    ): Playlist {
        // Pictures decoded to ordinary bitmaps: the test's canvas can't draw hardware ones.
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(app).allowHardware(false).build())
        val dir = File(app.filesDir, "sample").apply { mkdirs() }
        val m3u = File(dir, "sample.m3u")
        m3u.writeText(buildM3u(dir, liveOnly, messy))
        val p = Playlist("sample", "Home", SourceType.M3U_FILE, m3u.path)
        writeCache(dir, liveOnly, messy, ramadan)
        if (habits) {
            // The same three channels at this time of day on each of the last three days.
            val now = System.currentTimeMillis()
            for (d in 1..3) for (name in listOf("Wild Earth", "Echo Hits", "Little Comet")) {
                Habits.record(app.filesDir, p.id, liveId(name), now - d * 86_400_000L)
            }
        }
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
                    prayerMode = prayerMode,
                    cleanNames = messy, mergeDuplicates = messy,
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
                // A reminder starting in a minute (the card comes up), and an alert word for the guide's shows.
                reminders = if (!reminder) emptyList() else listOf(
                    Reminder(
                        p.id, liveId("Pulse Sports 1"), "Pulse Sports 1", "The Big Match: Final",
                        System.currentTimeMillis() + 60_000L, System.currentTimeMillis() + 2 * 3_600_000L,
                    ),
                ),
                alertWords = if (!reminder) emptyList() else listOf("Nature Hour"),
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
            // State changed while a frame was being made (a page putting up its picture, say) is
            // passed on at once: the test's main thread doesn't always get round to it by itself.
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            looper.idleFor(Duration.ofMillis(50))
            Thread.sleep(10)
            left -= 50
        }
        looper.idle()
    }

    protected fun waitFor(condition: () -> Boolean) {
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
    /** Tidy channel list's test: one channel listed three ways, another in two qualities and a language. */
    private val messyChannels = mapOf(
        "News" to listOf("UK: Lantern News HD" to "lantern", "UK: Lantern News FHD ᴴᴰ" to "lantern", "|UK| Lantern News (Backup)" to "lantern"),
        "Sports" to listOf(
            "AR: Crescent Sports 4K" to "crescent", "AR: Crescent Sports SD" to "crescent", "AR: Crescent Sports (English)" to "crescent.en",
        ),
    )

    private val films = listOf(
        "The Long Harbour", "Northern Lights", "Paper Kites", "Glass Orchard",
        "Midnight Ferry", "Copper Valley", "Salt Road", "The Quiet Year",
    )
    private val palette = intArrayOf(
        0xFF2F5D8A.toInt(), 0xFF8A3B2F.toInt(), 0xFF2F7A5A.toInt(), 0xFF6B4C9A.toInt(),
        0xFFB07A2A.toInt(), 0xFF3A6E7A.toInt(), 0xFF7A2F5D.toInt(), 0xFF4D6B2F.toInt(),
    )

    private val seriesTitles = listOf("Harbour Lights", "The Orchard Years", "Signal Point", "Winter Court")
    private val ramadanSeries = listOf("Desert Lanterns", "قمر الحارة", "The Spice Road", "دروب الرمال", "Night Market")

    /**
     * The same library as the M3U, plus what an Xtream provider sends (when each film and series
     * was added, ratings, series), saved as the app's playlist cache: the app opens that copy.
     */
    private fun writeCache(dir: File, liveOnly: Boolean, messy: Boolean = false, ramadan: Boolean = false) {
        val now = System.currentTimeMillis() / 1000
        val out = ArrayList<Channel>()
        for ((group, names) in liveGroups) for (name in names) {
            val id = slug(name)
            val url = "http://127.0.0.1:1/live/$id.m3u8"
            out += Channel(
                id = stableId("$name|$url"), name = name, group = group, url = url,
                logo = "file://" + File(dir, "logo-$id.png").path, tvgId = id,
            )
            // A provider's way of listing one channel several times, after the group's first.
            if (messy && name == names.first()) for ((messyName, key) in messyChannels[group].orEmpty()) {
                val u = "http://127.0.0.1:1/live/${slug(messyName)}.m3u8"
                out += Channel(
                    id = stableId("$messyName|$u"), name = messyName, group = group, url = u,
                    logo = "file://" + File(dir, "logo-$key.png").path, tvgId = key,
                )
            }
        }
        if (!liveOnly) {
            val ratings = listOf("7.8", "8.4", "6.9", "7.2", "8.1", "5.9", "7.5", "6.4")
            for ((i, title) in films.withIndex()) {
                val url = "http://127.0.0.1:1/movie/u/p/$i.mp4"
                out += Channel(
                    id = stableId("$title|$url"), name = title, group = filmGroup(i), url = url,
                    logo = "file://" + File(dir, "poster-$i.png").path, kind = Kind.MOVIE,
                    added = now - (2 + i * 6) * 86_400L, rating = ratings[i],
                )
            }
            val seriesRatings = listOf("8.2", "7.4", "7.9", "6.8")
            for ((i, title) in seriesTitles.withIndex()) {
                val poster = image(dir, "series-$i.png", 300, 450, palette[(i + 5) % palette.size], title, round = false)
                out += Channel(
                    id = "series-$i", name = title, group = if (i % 2 == 0) "Drama Series" else "Family", url = "",
                    logo = "file://${poster.path}", kind = Kind.SERIES, seriesId = "${100 + i}",
                    added = now - (1 + i * 5) * 86_400L, rating = seriesRatings[i],
                )
            }
            // Ramadan mode: a provider's group of this year's Ramadan series (made-up titles).
            if (ramadan) for ((i, title) in ramadanSeries.withIndex()) {
                val poster = image(dir, "ramadan-$i.png", 300, 450, palette[(i + 2) % palette.size], title, round = false)
                out += Channel(
                    id = "ramadan-$i", name = title, group = "مسلسلات رمضان 2027", url = "",
                    logo = "file://${poster.path}", kind = Kind.SERIES, seriesId = "${200 + i}",
                    added = now - i * 3_600L,
                )
            }
        }
        val cache = File(app.filesDir, "cache").apply { mkdirs() }
        Store.writePlaylist(File(cache, "sample.bin"), PlaylistCache(out, savedAt = System.currentTimeMillis()))
    }

    // A long provider-style group name: shelf and chip titles must stay on one line.
    private fun filmGroup(i: Int) = if (i % 2 == 0) "Drama" else "TOP MOVIES 4K 3840P DOLBY AUDIO (MULTI-SUBS)"

    private fun buildM3u(dir: File, liveOnly: Boolean = false, messy: Boolean = false): String = buildString {
        append("#EXTM3U\n")
        var n = 0
        for ((group, names) in liveGroups) {
            for (name in names) {
                val id = slug(name)
                val logo = image(dir, "logo-$id.png", 240, 240, palette[n % palette.size], initials(name), round = true)
                append("#EXTINF:-1 tvg-id=\"$id\" tvg-logo=\"file://${logo.path}\" group-title=\"$group\",$name\n")
                append("http://127.0.0.1:1/live/$id.m3u8\n")
                n++
                if (messy && name == names.first()) for ((messyName, key) in messyChannels[group].orEmpty()) {
                    val l = image(dir, "logo-$key.png", 240, 240, palette[(n + 3) % palette.size], initials(key.replaceFirstChar { it.uppercase() } + " TV"), round = true)
                    append("#EXTINF:-1 tvg-id=\"$key\" tvg-logo=\"file://${l.path}\" group-title=\"$group\",$messyName\n")
                    append("http://127.0.0.1:1/live/${slug(messyName)}.m3u8\n")
                }
            }
        }
        if (!liveOnly) for ((i, title) in films.withIndex()) {
            val poster = image(dir, "poster-$i.png", 300, 450, palette[(i + 3) % palette.size], title, round = false)
            val group = filmGroup(i)
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
        // "All films": every film as a grid, as the Films page was before.
        vm.group = ALL_FILTER
        shoot("tv-03b-films-all")
        vm.group = null
        vm.section = Section.SERIES
        shoot("tv-03c-series")
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
class TvHomeLiveOnlyScreens : ScreenshotBase() {
    // Live channels only and nothing watched yet: Home says what will appear there.
    @Test @Config(qualifiers = TV)
    fun tvHomeLiveOnly() = mainScreens("tv", liveOnly = true) { _, shoot -> shoot("tv-28-home-live-only") }
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
    // A TV device (not only the TV layout): Settings shows Match frame rate.
    @Test @Config(qualifiers = "w960dp-h2400dp-port-television-xhdpi")
    fun tvSettings() = mainScreens("tv") { vm, shoot ->
        vm.screen = Screen.SETTINGS
        shoot("tv-06-settings")
        // Each page of Settings.
        SettingsPage.entries.forEachIndexed { i, page ->
            vm.settingsPage = page
            shoot("tv-06" + ('a' + i) + "-settings-" + page.name.lowercase())
        }
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
        // A phone on its side has room for the Films page's picture behind the bars.
        vm.section = Section.MOVIES
        shoot("phone-15b-sidebar-films-landscape-light")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneLandscapeScreens : ScreenshotBase() {
    // Every page turns with the phone: the classic look on its side.
    @Test @Config(qualifiers = PHONE_LAND)
    fun phoneLandscape() = mainScreens("phone", watching = true) { vm, shoot ->
        shoot("phone-42-home-landscape")
        vm.section = Section.SERIES
        shoot("phone-43-series-landscape")
        vm.openDetails(vm.index.movies.first())
        shoot("phone-44-film-details-landscape")
        vm.screen = Screen.SETTINGS
        shoot("phone-45-settings-landscape")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneDownloadsScreens : ScreenshotBase() {
    // Downloads in each state: a film's page with its saved copy, then the Downloads page.
    @Test @Config(qualifiers = PHONE_TALL)
    fun phoneDownloads() = mainScreens("phone", watching = true) { vm, shoot ->
        val films = vm.index.movies
        val dir = java.io.File(app.filesDir, "dl").apply { mkdirs() }
        fun saved(ch: Channel, size: Long) = java.io.File(dir, ch.id + ".mp4").apply { writeBytes(ByteArray(16)) }.path.let { path ->
            OfflineItem(
                id = ch.id, playlistId = "sample", name = ch.name, kind = ch.kind, logo = ch.logo, url = ch.url,
                file = path, size = size, done = size, state = OfflineState.DONE, addedAt = 1,
            )
        }
        OfflineCenter.showForTest(
            listOf(
                saved(films[0], 1_610_612_736L),
                saved(films[1], 943_718_400L),
                OfflineItem(
                    id = films[2].id, playlistId = "sample", name = films[2].name, kind = films[2].kind, logo = films[2].logo, url = films[2].url,
                    size = 2_147_483_648L, done = 773_094_113L, state = OfflineState.RUNNING, addedAt = 2,
                ),
                OfflineItem(
                    id = films[3].id, playlistId = "sample", name = films[3].name, kind = films[3].kind, logo = films[3].logo, url = films[3].url,
                    size = 1_288_490_188L, done = 128_849_018L, state = OfflineState.FAILED,
                    note = "Not enough space: it needs 1.1 GB", addedAt = 3,
                ),
            ),
        )
        vm.openDetails(films[0])
        shoot("phone-46-film-downloaded")
        vm.openDetails(films[2])
        shoot("phone-47-film-downloading")
        vm.screen = Screen.DOWNLOADS
        shoot("phone-48-downloads")
        // A series: one episode saved, one on its way, one waiting, one not downloaded.
        val show = vm.index.series.first()
        val eps = (1..4).map { n ->
            Channel(
                id = "e90$n", name = "Episode $n", group = "Season 1", url = "http://example.invalid/series/u/p/90$n.mp4",
                kind = Kind.SERIES, info = "S1 · E$n · 45 min",
            )
        }
        fun episode(ch: Channel, state: OfflineState, size: Long, done: Long) = OfflineItem(
            id = ch.id, playlistId = "sample", name = ch.name, kind = ch.kind, url = ch.url, info = ch.info,
            size = size, done = done, state = state, addedAt = 4, show = show.name, showPoster = show.logo,
        )
        OfflineCenter.showForTest(
            OfflineCenter.items.value + listOf(
                saved(eps[0], 734_003_200L).copy(info = eps[0].info, show = show.name, showPoster = show.logo, addedAt = 5),
                episode(eps[1], OfflineState.RUNNING, 712_000_000L, 213_600_000L),
                episode(eps[2], OfflineState.QUEUED, 0L, 0L),
            ),
        )
        vm.showSeriesForTest(
            show,
            SeriesInfo(plot = "A harbour town and the families who keep its lights burning.", cover = show.logo, seasons = listOf(Season(1, eps))),
        )
        shoot("phone-49-series-downloads")
        vm.screen = Screen.DOWNLOADS
        shoot("phone-50-downloads-episodes")
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
        vm.section = Section.MOVIES
        shoot("phone-04-films")
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
    // A very tall phone shows every Home row: Recently watched, Continue watching, Trending now,
    // New films, New series, then favorites.
    @Test @Config(qualifiers = PHONE_TALL)
    fun phoneShelves() = mainScreens("phone", watching = true, habits = true) { vm, shoot ->
        shoot("phone-11-home-all-shelves")
        // The Films page's rows: Continue watching, the Top 10, new films, favorites, each group.
        vm.section = Section.MOVIES
        shoot("phone-11b-films-all-rows")
    }
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
class TvRemoteScreens : ScreenshotBase() {
    @Test @Config(qualifiers = TV)
    fun tvRemote() = mainScreens("tv") { vm, shoot ->
        vm.screen = Screen.REMOTE
        shoot("tv-25-phone-remote")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhonePrayerScreens : ScreenshotBase() {
    @Test @Config(qualifiers = PHONE_TALL)
    fun phonePrayer() = mainScreens("phone", prayerMode = "pause") { vm, shoot ->
        vm.screen = Screen.PRAYER
        shoot("phone-21-prayer-times")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvAmbientScreens : ScreenshotBase() {
    @Test @Config(qualifiers = TV)
    fun tvAmbient() = player(tv = true, live = false, ambient = true) { shoot -> shoot("tv-26-ambient") }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvPrayerBannerScreens : ScreenshotBase() {
    @Test @Config(qualifiers = TV)
    fun tvPrayer() = player(tv = true, live = true, prayer = true) { shoot -> shoot("tv-27-prayer-paused") }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneSettingsScreens : ScreenshotBase() {
    @Test @Config(qualifiers = PHONE_TALL)
    fun phoneSettings() = mainScreens("phone") { vm, shoot ->
        vm.screen = Screen.SETTINGS
        shoot("phone-06-settings")
        // Each page of Settings.
        SettingsPage.entries.forEachIndexed { i, page ->
            vm.settingsPage = page
            shoot("phone-06" + ('a' + i) + "-settings-" + page.name.lowercase())
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhonePlayerScreens : ScreenshotBase() {
    @Test @Config(qualifiers = PHONE_LAND)
    fun phonePlayerFilm() = player(tv = false, live = false) { shoot -> shoot("phone-10-player-film-landscape") }
}

// ---------- Search ----------

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvSearchScreens : ScreenshotBase() {
    // Search from Home: films, series and channels grouped, best match first ("Harbor News" by a
    // forgiven typo), the top five under the box; then a section's own results; then recent searches.
    @Test @Config(qualifiers = TV)
    fun tvSearch() = mainScreens("tv") { vm, shoot ->
        vm.query = "harbour"
        shoot("tv-29-search-home")
        vm.section = Section.MOVIES
        shoot("tv-30-search-films")
        vm.section = Section.HOME
        vm.rememberSearch("harbour")
        vm.rememberSearch("pulse sports")
        vm.query = ""
        shoot("tv-31-search-recent")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvSidebarSearchScreens : ScreenshotBase() {
    @Test @Config(qualifiers = TV)
    fun tvSidebarSearch() = mainScreens("tv", look = "sidebar", watching = true) { vm, shoot ->
        vm.query = "news"
        shoot("tv-32-sidebar-search")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneSearchScreens : ScreenshotBase() {
    @Test @Config(qualifiers = PHONE)
    fun phoneSearch() = mainScreens("phone", look = "sidebar", watching = true) { vm, shoot ->
        vm.query = "harb"
        shoot("phone-22-search-home")
        vm.query = "zzzz"
        shoot("phone-23-search-nothing")
    }
}

// ---- Reminders and two screens ----

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvReminderScreens : ScreenshotBase() {
    // A reminder set in the guide comes up over Home a minute before; Settings lists what's coming up.
    @Test @Config(qualifiers = TV)
    fun tvReminder() = mainScreens("tv", reminder = true) { vm, shoot ->
        waitFor { ProgramAlerts.current.value != null }
        shoot("tv-33-reminder-home")
        ProgramAlerts.dismiss()
        vm.screen = Screen.SETTINGS
        vm.settingsPage = SettingsPage.REMINDERS
        waitFor { vm.upcomingAlerts.size > 1 }
        shoot("tv-34-settings-reminders")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneReminderScreens : ScreenshotBase() {
    @Test @Config(qualifiers = PHONE)
    fun phoneReminder() = mainScreens("phone", look = "sidebar", reminder = true) { _, shoot ->
        waitFor { ProgramAlerts.current.value != null }
        shoot("phone-24-reminder-home")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvTwoScreens : ScreenshotBase() {
    @Test @Config(qualifiers = TV)
    fun tvTwoScreens() = player(tv = true, live = true, dual = true) { shoot -> shoot("tv-35-two-screens") }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvTwoScreensSecondHeard : ScreenshotBase() {
    // The second picture heard, and a reminder coming up in the player.
    @Test @Config(qualifiers = TV)
    fun tvTwoScreensSecond() = player(tv = true, live = true, dual = true, heardSecond = true, alert = true) { shoot ->
        shoot("tv-36-two-screens-second-reminder")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneTwoScreens : ScreenshotBase() {
    @Test @Config(qualifiers = PHONE)
    fun phoneTwoScreens() = player(tv = false, live = true, dual = true) { shoot -> shoot("phone-25-two-screens") }
}

// ---- 7.0: start over, For this hour, tidy list, mini player ----

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvForThisHourScreens : ScreenshotBase() {
    @Test @Config(qualifiers = TV_TALL)
    fun tvForThisHour() = mainScreens("tv", watching = true, habits = true) { _, shoot -> shoot("tv-39-home-for-this-hour") }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvMiniPlayerScreens : ScreenshotBase() {
    // Back from a live channel: it carries on small over the lists.
    @Test @Config(qualifiers = TV)
    fun tvMiniPlayer() = mainScreens("tv") { vm, shoot ->
        val live = vm.index.live
        MiniPlayerState.show(live, live.indexOfFirst { it.name == "Atlas News" }, "", "sample")
        shoot("tv-40-mini-player")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneMiniPlayerScreens : ScreenshotBase() {
    @Test @Config(qualifiers = PHONE)
    fun phoneMiniPlayer() = mainScreens("phone") { vm, shoot ->
        vm.section = Section.LIVE
        val live = vm.index.live
        MiniPlayerState.show(live, live.indexOfFirst { it.name == "Pulse Sports 1" }, "", "sample")
        shoot("phone-28-mini-player")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvStartOverScreens : ScreenshotBase() {
    @Test @Config(qualifiers = TV)
    fun tvStartOver() = player(tv = true, live = true, startOver = "offer") { shoot -> shoot("tv-41-start-over") }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvBackToLiveScreens : ScreenshotBase() {
    @Test @Config(qualifiers = TV)
    fun tvBackToLive() = player(tv = true, live = false, startOver = "replay") { shoot -> shoot("tv-42-back-to-live") }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneStartOverScreens : ScreenshotBase() {
    @Test @Config(qualifiers = PHONE_LAND)
    fun phoneStartOver() = player(tv = false, live = true, startOver = "offer") { shoot -> shoot("phone-29-start-over") }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvTidyScreens : ScreenshotBase() {
    // Tidy channel list on: clean names with a quality mark, duplicates once.
    @Test @Config(qualifiers = TV)
    fun tvTidy() = mainScreens("tv", messy = true) { vm, shoot ->
        vm.section = Section.LIVE
        shoot("tv-43-tidy-live")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneTidyScreens : ScreenshotBase() {
    @Test @Config(qualifiers = PHONE_TALL)
    fun phoneTidy() = mainScreens("phone", messy = true) { vm, shoot ->
        vm.section = Section.LIVE
        shoot("phone-30-tidy-live")
    }
}

// ---- 7.2: Subscription watch ----

/** Ends in 5 days, both connections in use (another device watching). */
private fun endingSoon(): Account {
    val now = System.currentTimeMillis()
    return Account(status = "Active", expires = now + 5 * 86_400_000L + 3_600_000L, active = 2, max = 2, checkedAt = now)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvSubscriptionScreens : ScreenshotBase() {
    // The reminder over the lists, then the account in Settings (tall, so the whole page shows).
    @Test @Config(qualifiers = TV_TALL)
    fun tvSubscription() = mainScreens("tv", account = endingSoon()) { vm, shoot ->
        shoot("tv-44-subscription-ending")
        vm.screen = Screen.SETTINGS
        vm.settingsPage = SettingsPage.PLAYLISTS
        shoot("tv-45-settings-subscription")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneSubscriptionScreens : ScreenshotBase() {
    @Test @Config(qualifiers = PHONE)
    fun phoneSubscription() = mainScreens("phone", account = endingSoon()) { _, shoot ->
        shoot("phone-31-subscription-ending")
    }
}

// ---- 7.3: auto captions ----

private val captionWords = app.falak.player.player.CaptionText.rollUp(
    "Good evening, and welcome. We're going live now to the stadium, where the teams are coming out for the second half.",
    42,
    2,
)!!

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvAutoCaptionsScreens : ScreenshotBase() {
    @Test @Config(qualifiers = TV)
    fun tvAutoCaptions() = player(tv = true, live = true, autoCaption = captionWords) { shoot -> shoot("tv-46-auto-captions") }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneAutoCaptionsScreens : ScreenshotBase() {
    @Test @Config(qualifiers = PHONE_LAND)
    fun phoneAutoCaptions() = player(tv = false, live = true, autoCaption = captionWords) { shoot -> shoot("phone-32-auto-captions") }
}

// ---- 7.5: search across scripts, Ramadan mode, kids screen time, service report, sleep timer ----

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvArabicSearchScreens : ScreenshotBase() {
    // Typed (or said) in Arabic, found in Latin letters; the microphone and its language in the box.
    @Test @Config(qualifiers = TV)
    fun tvArabicSearch() {
        forceVoiceForTest = true
        mainScreens("tv", look = "sidebar", watching = true) { vm, shoot ->
            vm.query = "بولس سبورت"
            shoot("tv-47-search-arabic")
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneArabicSearchScreens : ScreenshotBase() {
    @Test @Config(qualifiers = PHONE)
    fun phoneArabicSearch() {
        forceVoiceForTest = true
        mainScreens("phone", look = "sidebar", watching = true) { vm, shoot ->
            vm.query = "وايلد ايرث"
            shoot("phone-33-search-arabic")
        }
    }
}

/** An afternoon in Ramadan 2027 (the 12th day), where the test runs. */
private fun ramadanAfternoon(): Long = java.util.Calendar.getInstance().apply {
    clear()
    set(2027, java.util.Calendar.FEBRUARY, 19, 13, 0)
}.timeInMillis

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvRamadanScreens : ScreenshotBase() {
    @Test @Config(qualifiers = TV_TALL)
    fun tvRamadan() {
        RamadanNow.nowForTest = ramadanAfternoon()
        mainScreens("tv", watching = true, ramadan = true) { vm, shoot ->
            shoot("tv-48-home-ramadan")
            vm.screen = Screen.PRAYER
            // The first day moved a day earlier by hand (the moon seen the night before).
            vm.updateSettings { it.copy(ramadanDates = mapOf("1448" to Ramadan.encode(20270207, 20270308))) }
            shoot("tv-49-prayer-ramadan")
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneRamadanScreens : ScreenshotBase() {
    @Test @Config(qualifiers = PHONE_TALL)
    fun phoneRamadan() {
        RamadanNow.nowForTest = ramadanAfternoon()
        mainScreens("phone", look = "sidebar", ramadan = true) { _, shoot -> shoot("phone-34-home-ramadan") }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvRamadanAmbientScreens : ScreenshotBase() {
    @Test @Config(qualifiers = TV)
    fun tvRamadanAmbient() {
        RamadanNow.nowForTest = ramadanAfternoon()
        player(tv = true, live = false, ambient = true) { shoot -> shoot("tv-50-ambient-ramadan") }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvKidsTimeScreens : ScreenshotBase() {
    // Time used up, then bedtime: the page over the kids' lists (a grown-up's PIN adds time).
    @Test @Config(qualifiers = TV)
    fun tvKidsTime() = mainScreens("tv", kids = listOf("Kids"), kidsOn = true) { vm, shoot ->
        vm.updateSettings { it.copy(kidsDailyMinutes = 90, kidsBedtime = 20 * 60) }
        vm.chooseProfile(KIDS_PROFILE)
        KidsClock.stop(KidsTime.Block.TIME_UP)
        shoot("tv-51-kids-time-up")
        KidsClock.stop(KidsTime.Block.BEDTIME)
        shoot("tv-52-kids-bedtime")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneKidsTimeScreens : ScreenshotBase() {
    @Test @Config(qualifiers = PHONE_TALL)
    fun phoneKidsTime() = mainScreens("phone", kids = listOf("Kids"), kidsOn = true) { vm, shoot ->
        vm.updateSettings { it.copy(kidsDailyMinutes = 90, kidsBedtime = 20 * 60) }
        vm.chooseProfile("")
        // Settings → Profiles & parental control, with the screen-time rows.
        vm.unlocked = true
        vm.screen = Screen.SETTINGS
        vm.settingsPage = SettingsPage.FAMILY
        shoot("phone-35-settings-kids-time")
        vm.screen = Screen.LIBRARY
        vm.chooseProfile(KIDS_PROFILE)
        KidsClock.stop(KidsTime.Block.TIME_UP)
        shoot("phone-36-kids-time-up")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvServiceReportScreens : ScreenshotBase() {
    // A week of live TV on this device: the line in Settings, then the report in full.
    @Test @Config(qualifiers = TV_TALL)
    fun tvServiceReport() = mainScreens("tv") { vm, shoot ->
        val r = ServiceReport
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        val evening = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 21); set(java.util.Calendar.MINUTE, 10) }.timeInMillis
        val afternoon = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 15); set(java.util.Calendar.MINUTE, 10) }.timeInMillis
        ServiceLogStore.update(app.filesDir, "sample") { log0 ->
            var log = log0
            for (d in 1..5) {
                log = r.add(log, afternoon - d * day, watchedMs = 90 * 60_000L, stalls = if (d == 3) 1 else 0)
                repeat(6) { log = r.add(log, afternoon - d * day, startMs = 1_100L + it * 120L) }
                log = r.add(log, evening - d * day, watchedMs = 60 * 60_000L, stalls = 5)
            }
            log = r.add(log, evening - day, failed = "Racing Line")
            log = r.add(log, evening - 2 * day, failed = "Racing Line")
            r.add(log, now - 3 * day, failed = "Deep Blue")
        }
        vm.screen = Screen.SETTINGS
        vm.settingsPage = SettingsPage.PLAYLISTS
        shoot("tv-53-settings-service-report")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvSleepTimerScreens : ScreenshotBase() {
    // The player's Sleep button with a timer running.
    @Test @Config(qualifiers = TV)
    fun tvSleepTimer() {
        SleepTimer.after(30, android.os.SystemClock.elapsedRealtime())
        player(tv = true, live = true) { shoot -> shoot("tv-54-player-sleep-timer") }
    }
}

// ---- 7.6: caption style (look, auto captions' place and lines), in the same space as the stream's own ----

/** Both caption views: in the picture's box, with the same bounds and padding (so the same space). */
private fun assertSameSpace(activity: PlayerActivity) {
    val (stream, auto) = activity.captionViews()
    checkNotNull(stream) { "No stream captions view" }
    check(stream.parent === auto.parent) { "Auto captions aren't in the picture's box" }
    check(stream.left == auto.left && stream.top == auto.top && stream.width == auto.width && stream.height == auto.height) {
        "Different boxes: stream ${stream.left},${stream.top} ${stream.width}x${stream.height}, auto ${auto.left},${auto.top} ${auto.width}x${auto.height}"
    }
    check(
        stream.paddingLeft == auto.paddingLeft && stream.paddingTop == auto.paddingTop &&
            stream.paddingRight == auto.paddingRight && stream.paddingBottom == auto.paddingBottom,
    ) { "Different padding" }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvCaptionStyleScreens : ScreenshotBase() {
    // Subtitles → Caption style over auto captions: the panel at the top, captions at the bottom;
    // with auto captions at the top (three lines), the panel moves to the bottom.
    @Test @Config(qualifiers = TV)
    fun tvCaptionStyle() = playerSteps(tv = true) { activity, shoot ->
        activity.previewPicture(16f / 9f)
        settle()
        activity.previewAutoCaptions(CaptionLayout.SAMPLE)
        activity.previewCaptionStyle()
        shoot("tv-55-caption-style")
        activity.previewCaptionChange { it.copy(autoCapsPosition = "top", autoCapsLines = 3, subColor = "yellow") }
        activity.previewAutoCaptions(CaptionLayout.SAMPLE)
        shoot("tv-56-caption-style-top")
    }

    // A 4:3 picture (pillarboxed): the stream's own captions, then auto captions, in the same space.
    @Test @Config(qualifiers = TV)
    fun tvCaptionsSameSpace() = playerSteps(tv = true) { activity, shoot ->
        activity.previewPicture(4f / 3f)
        settle()
        activity.previewStreamCaptions("We're going live now to the stadium,\nwhere the teams are coming out.")
        assertSameSpace(activity)
        shoot("tv-57-captions-4x3-stream")
        activity.previewStreamCaptions("")
        activity.previewAutoCaptions("We're going live now to the stadium, where the teams are coming out.")
        assertSameSpace(activity)
        shoot("tv-58-captions-4x3-auto")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneCaptionStyleScreens : ScreenshotBase() {
    // The most room captions can take (Raised, three lines, Extra large): still clear of the panel.
    @Test @Config(qualifiers = PHONE_LAND)
    fun phoneCaptionStyle() = playerSteps(
        tv = false,
        settings = { it.copy(autoCapsPosition = "raised", autoCapsLines = 3, subSize = 3) },
    ) { activity, shoot ->
        activity.previewPicture(16f / 9f)
        settle()
        activity.previewAutoCaptions(CaptionLayout.SAMPLE)
        activity.previewCaptionStyle()
        shoot("phone-37-caption-style")
    }

    // A small phone on its side (640 × 360): the same, the panel two rows high.
    @Test @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun smallPhoneCaptionStyle() = playerSteps(
        tv = false,
        settings = { it.copy(autoCapsPosition = "raised", autoCapsLines = 3, subSize = 3) },
    ) { activity, shoot ->
        activity.previewPicture(16f / 9f)
        settle()
        activity.previewAutoCaptions(CaptionLayout.SAMPLE)
        activity.previewCaptionStyle()
        shoot("phone-41-caption-style-small")
    }

    // Held upright: the panel fits above the picture.
    @Test @Config(qualifiers = PHONE)
    fun phonePortraitCaptionStyle() = playerSteps(tv = false) { activity, shoot ->
        activity.previewPicture(16f / 9f)
        settle()
        activity.previewAutoCaptions(CaptionLayout.SAMPLE)
        activity.previewCaptionStyle()
        shoot("phone-38-caption-style-portrait")
    }

    // Zoom fills the screen and crops the picture: captions stay on the part that's on screen.
    @Test @Config(qualifiers = PHONE_LAND)
    fun phoneZoomCaptions() = playerSteps(tv = false) { activity, shoot ->
        activity.previewPicture(16f / 9f, aspectMode = 2)
        settle()
        activity.previewStreamCaptions("We're going live now to the stadium,\nwhere the teams are coming out.")
        assertSameSpace(activity)
        val (stream, _) = activity.captionViews()
        check(stream!!.paddingBottom > 0) { "Zoom: captions not kept on screen" }
        shoot("phone-39-captions-zoom")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneCaptionSettingsScreens : ScreenshotBase() {
    // Settings → Subtitle style, with auto captions' place and lines.
    @Test @Config(qualifiers = PHONE_TALL)
    fun phoneCaptionSettings() = mainScreens("phone") { vm, shoot ->
        vm.updateSettings { it.copy(autoCapsPosition = "raised", autoCapsLines = 3) }
        vm.screen = Screen.SETTINGS
        vm.settingsPage = SettingsPage.SUBTITLE_STYLE
        shoot("phone-40-settings-caption-style")
    }
}
