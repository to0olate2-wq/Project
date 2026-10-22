package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BackupTest {
    private fun live(name: String, group: String) = Channel(id = "$group/$name", name = name, group = group, url = "http://x/$group/$name")

    @Test
    fun sameChannelIgnoresQualityAndGroup() {
        assertEquals(sameChannelKey(live("Atlas News FHD", "News")), sameChannelKey(live("Atlas News HD", "Backup")))
        assertNotEquals(sameChannelKey(live("Pulse Sports 1", "Sports")), sameChannelKey(live("Pulse Sports 2", "Sports")))
    }

    @Test
    fun qualityOrder() {
        assertEquals(4, qualityRank(live("Atlas News 4K", "News")))
        assertEquals(3, qualityRank(live("Atlas News FHD", "News")))
        assertEquals(2, qualityRank(live("Atlas News", "News")))
        assertEquals(1, qualityRank(live("Atlas News SD", "News")))
    }
}
