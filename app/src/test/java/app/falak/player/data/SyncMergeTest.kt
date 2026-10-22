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
}
