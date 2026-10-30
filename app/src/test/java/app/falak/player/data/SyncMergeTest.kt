package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {
    private val t0 = 1_700_000_000_000L
    private fun pl(id: String, url: String = "http://host/$id.m3u", name: String = id) = Playlist(id, name, SourceType.M3U_URL, url)
    private fun device(id: String, vararg playlists: Playlist) =
        AppState(playlists = playlists.toList(), sync = SyncMeta(deviceId = id, key = "k"))

    /** One round as the app does it: a sends, b merges and answers, a merges the answer. */
    private fun round(a: AppState, b: AppState, now: Long): Pair<AppState, AppState> {
        val b2 = SyncMerge.merge(b, SyncMerge.snapshot(a, "A"), now).first
        val a2 = SyncMerge.merge(a, SyncMerge.snapshot(b2, "B"), now).first
        return a2 to b2
    }

    @Test
    fun newPlaylistArrivesWithItsFavorites() {
        val a = device("A", pl("p1")).copy(favorites = mapOf("p1" to listOf("x", "y")))
        val b = device("B")
        val (_, b2) = round(a, b, t0)
        assertEquals(listOf("p1"), b2.playlists.map { it.id })
        assertEquals(listOf("x", "y"), b2.favorites["p1"])
    }

    @Test
    fun newerChangeWinsBothWays() {
        var a = device("A", pl("p1")).copy(favorites = mapOf("p1" to listOf("x")))
        var b = device("B", pl("p1")).copy(favorites = mapOf("p1" to listOf("x")))
        // A removes a favorite later than B last touched them.
        val before = a
        a = SyncMerge.stamp(before, a.copy(favorites = mapOf("p1" to emptyList())), t0 + 10)
        val r = round(a, b, t0 + 20)
        a = r.first
        b = r.second
        assertEquals(emptyList<String>(), b.favorites["p1"])
        // Then B renames the playlist: A takes the new name.
        b = SyncMerge.stamp(b, b.copy(playlists = listOf(pl("p1", name = "Home"))), t0 + 30)
        val r2 = round(b, a, t0 + 40)
        assertEquals("Home", r2.second.playlists.single().name)
    }

    @Test
    fun removedPlaylistStaysRemoved() {
        var a = device("A", pl("p1"), pl("p2"))
        var b = device("B", pl("p1"), pl("p2"))
        a = SyncMerge.stamp(a, SyncMerge.withoutPlaylist(a, "p2"), t0 + 5)
        val (a2, b2) = round(a, b, t0 + 10)
        assertEquals(listOf("p1"), b2.playlists.map { it.id })
        assertEquals(listOf("p1"), a2.playlists.map { it.id })
    }

    @Test
    fun samePlaylistAddedOnBothBecomesOne() {
        val a = device("A", pl("zzz", url = "http://host/list.m3u"))
        val b = device("B", pl("aaa", url = "http://host/list.m3u"))
        val (a2, b2) = round(a, b, t0)
        assertEquals(1, a2.playlists.size)
        assertEquals(1, b2.playlists.size)
        // Both now know it by the same id.
        assertEquals(SyncMerge.syncId(a2, a2.playlists[0].id), SyncMerge.syncId(b2, b2.playlists[0].id))
    }

    @Test
    fun continueWatchingLatestWinsAndClearedGoes() {
        val key = progressKey("p1", "film")
        var a = device("A", pl("p1")).copy(progress = mapOf(key to Watch(pos = 60_000, dur = 100_000, at = t0)))
        var b = device("B", pl("p1")).copy(progress = mapOf(key to Watch(pos = 90_000, dur = 100_000, at = t0 + 1)))
        var r = round(a, b, t0 + 2)
        assertEquals(90_000L, r.first.progress[key]?.pos)
        // Finished on B: gone from A too.
        b = r.second.copy(progress = emptyMap(), sync = r.second.sync.copy(progressGone = mapOf("p1|film" to t0 + 3)))
        a = r.first
        r = round(b, a, t0 + 4)
        assertNull(r.second.progress[key])
        assertFalse(SyncMerge.sameSynced(a, r.second))
        assertTrue(SyncMerge.sameSynced(r.first, r.second))
    }

    @Test
    fun settingsNewestWinsOneByOne() {
        var a = device("A", pl("p1"))
        var b = device("B", pl("p1"))
        a = SyncMerge.stamp(a, a.copy(settings = a.settings.copy(subSize = 3)), t0 + 1)
        b = SyncMerge.stamp(b, b.copy(settings = b.settings.copy(audioLang = "ar", layout = "tv")), t0 + 2)
        val (a2, b2) = round(a, b, t0 + 3)
        // Each change goes both ways; neither overwrites the other's.
        assertEquals(3, a2.settings.subSize)
        assertEquals(3, b2.settings.subSize)
        assertEquals("ar", a2.settings.audioLang)
        assertEquals("ar", b2.settings.audioLang)
        // The screen layout belongs to each device.
        assertEquals("auto", a2.settings.layout)
        assertEquals("tv", b2.settings.layout)
    }

    @Test
    fun newDeviceTakesTheOthersSettingsProfilesAndWords() {
        // Set up before linking (nothing dated yet): the new TV still gets it all from the phone.
        val phone = device("A", pl("p1")).copy(
            settings = Settings(subSize = 2, pin = "1234", kidsDailyMinutes = 60, layout = "phone"),
            profiles = listOf(Profile("x", "Sara")),
            alertWords = listOf("final"),
        )
        val (phone2, tv) = round(phone, device("B"), t0)
        assertEquals(2, tv.settings.subSize)
        assertEquals("1234", tv.settings.pin)
        assertEquals(60, tv.settings.kidsDailyMinutes)
        assertEquals("auto", tv.settings.layout)
        assertEquals(listOf("Sara"), tv.profiles.map { it.name })
        assertEquals(listOf("final"), tv.alertWords)
        // And the TV's untouched defaults didn't come back to the phone.
        assertEquals(2, phone2.settings.subSize)
        assertEquals("1234", phone2.settings.pin)
    }

    @Test
    fun profilesKeepTheirOwnFavoritesAndProgress() {
        val film = progressKey("p1@x", "film")
        val a = device("A", pl("p1")).copy(
            profiles = listOf(Profile("x", "Sara")),
            favorites = mapOf("p1" to listOf("m1"), "p1@x" to listOf("m2")),
            recents = mapOf("p1@x" to listOf("c1")),
            progress = mapOf(film to Watch(pos = 1_000, dur = 5_000, at = t0)),
        )
        val (_, b) = round(a, device("B", pl("p1")), t0 + 1)
        assertEquals(listOf("m1"), b.favorites["p1"])
        assertEquals(listOf("m2"), b.favorites["p1@x"])
        assertEquals(listOf("c1"), b.recents["p1@x"])
        assertEquals(1_000L, b.progress[film]?.pos)
        // Sara removed on A: her favorites and the rest go on B too.
        val a3 = SyncMerge.stamp(a, a.copy(profiles = emptyList()), t0 + 5)
        val b3 = SyncMerge.merge(b, SyncMerge.snapshot(a3, "A"), t0 + 6).first
        assertTrue(b3.profiles.isEmpty())
        assertNull(b3.favorites["p1@x"])
        assertNull(b3.recents["p1@x"])
        assertNull(b3.progress[film])
        assertEquals(listOf("m1"), b3.favorites["p1"])
    }

    @Test
    fun followedIntrosLockedGroupsRemindersAndRecentsSync() {
        val start = device("A", pl("p1"))
        val a = SyncMerge.stamp(
            start,
            start.copy(
                followed = mapOf("p1" to listOf(FollowedSeries("s1", "Show", episodes = 8))),
                intros = mapOf("p1|Show" to Intro(10_000, 90_000)),
                recents = mapOf("p1" to listOf("c9")),
                settings = start.settings.copy(lockedGroups = mapOf("p1" to listOf("Adults")), kidsGroups = mapOf("p1" to listOf("Cartoons"))),
                reminders = listOf(Reminder("p1", "c1", "News", "Evening news", t0 + 100, t0 + 200)),
            ),
            t0 + 1,
        )
        val (_, b) = round(a, device("B", pl("p1")), t0 + 2)
        assertEquals(8, b.followed["p1"]?.single()?.episodes)
        assertEquals(Intro(10_000, 90_000), b.intros["p1|Show"])
        assertEquals(listOf("c9"), b.recents["p1"])
        assertEquals(listOf("Adults"), b.settings.lockedGroups["p1"])
        assertEquals(listOf("Cartoons"), b.settings.kidsGroups["p1"])
        assertEquals("Evening news", b.reminders.single().title)
    }

    @Test
    fun anOlderVersionWipesNothingNew() {
        val b = device("B", pl("p1")).copy(
            settings = Settings(subSize = 3),
            profiles = listOf(Profile("x", "Sara")),
            recents = mapOf("p1" to listOf("c1")),
            alertWords = listOf("final"),
        )
        // What 8.1 sends: only the playlist parts it knows.
        val full = SyncMerge.snapshot(device("A", pl("p1")), "A")
        val old = full.copy(parts = full.parts.filterKeys { it.substringBefore('|') in setOf("fav", "lists", "hidden", "guide") })
        val b2 = SyncMerge.merge(b, old, t0).first
        assertEquals(3, b2.settings.subSize)
        assertEquals(listOf("Sara"), b2.profiles.map { it.name })
        assertEquals(listOf("c1"), b2.recents["p1"])
        assertEquals(listOf("final"), b2.alertWords)
    }
}
