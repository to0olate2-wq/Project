package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rename or change logo: shown everywhere, the guide stays the same, and it syncs. */
class ChannelEditTest {
    private val a = Channel(id = "1", name = "AR: MBC 1 FHD", group = "Arabic", url = "http://x/1")
    private val b = Channel(id = "2", name = "AR: MBC 2", group = "Arabic", url = "http://x/2", logo = "http://x/2.png")

    @Test
    fun aNameAndLogoChosenByHandShowAndKeepTheGuide() {
        val key = a.epgKey
        val idx = LibraryIndex.build(
            listOf(a, b),
            cleanNames = true,
            edits = mapOf("1" to ChannelEdit(name = "MBC One", logo = "http://my/logo.png"), "2" to ChannelEdit(logo = "http://my/2.png")),
        )
        val one = idx.byId.getValue("1")
        assertEquals("MBC One", one.name)
        assertEquals("http://my/logo.png", one.logo)
        assertEquals(key, one.epgKey)
        assertEquals("MBC One", idx.groupsOf(Kind.LIVE).getValue("Arabic").first().name)
        // Only the logo changed: Tidy channel list's name stays.
        val two = idx.byId.getValue("2")
        assertEquals("MBC 2", two.name)
        assertEquals("http://my/2.png", two.logo)
        // The playlist as it came is untouched.
        assertEquals("AR: MBC 1 FHD", idx.raw.first().name)
        assertTrue(ChannelEdit().isEmpty())
    }

    @Test
    fun editsSyncToLinkedDevices() {
        val p = Playlist("p1", "Mine", SourceType.M3U_URL, "http://x/list.m3u")
        val before = AppState(playlists = listOf(p), sync = SyncMeta(deviceId = "phone", key = "k"))
        val after = before.copy(channelEdits = mapOf("p1" to mapOf("1" to ChannelEdit(name = "MBC One"))))
        val stamped = SyncMerge.stamp(before, after, 1_000L)
        val tv = AppState(playlists = listOf(p), sync = SyncMeta(deviceId = "tv", key = "k"))
        val (merged, _) = SyncMerge.merge(tv, SyncMerge.snapshot(stamped, "Phone"), 2_000L)
        assertEquals("MBC One", merged.channelEdits["p1"]?.get("1")?.name)
        // Removing the playlist takes its edits with it.
        assertTrue(SyncMerge.withoutPlaylist(merged, "p1").channelEdits.isEmpty())
    }
}
