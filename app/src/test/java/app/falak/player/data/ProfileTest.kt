package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTest {
    private val p = Playlist("p1", "Home", SourceType.M3U_URL, "http://host/list.m3u")

    @Test
    fun eachProfileKeepsItsOwnKeys() {
        val main = AppState(playlists = listOf(p))
        assertEquals("p1", main.mine("p1"))
        val sara = main.copy(activeProfile = "s1")
        assertEquals("p1@s1", sara.mine("p1"))
    }

    @Test
    fun everyProfileSyncsUnderItsOwnKeys() {
        val s = AppState(
            playlists = listOf(p),
            favorites = mapOf("p1" to listOf("a"), "p1@s1" to listOf("b")),
            progress = mapOf("p1|film" to Watch(1000, 5000, at = 1), "p1@s1|film2" to Watch(2000, 5000, at = 2)),
            sync = SyncMeta(deviceId = "A", key = "k"),
        )
        val snap = SyncMerge.snapshot(s, "A")
        assertEquals(listOf("a"), snap.parts["fav|p1"]?.ids)
        assertEquals(listOf("b"), snap.parts["fav|p1@s1"]?.ids)
        assertTrue("p1|film" in snap.progress)
        assertTrue("p1@s1|film2" in snap.progress)
        // Nothing of a playlist this device doesn't have.
        assertFalse(snap.progress.keys.any { it.startsWith("p2") })
    }

    @Test
    fun removingAPlaylistRemovesEveryProfilesData() {
        val s = AppState(
            playlists = listOf(p),
            favorites = mapOf("p1" to listOf("a"), "p1@s1" to listOf("b"), "p2" to listOf("c")),
        )
        assertEquals(setOf("p2"), SyncMerge.withoutPlaylist(s, "p1").favorites.keys)
    }
}
