package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TidyTest {
    private fun live(id: String, name: String, group: String = "News", tvgId: String = "") =
        Channel(id = id, name = name, group = group, url = "http://x/$id.ts", tvgId = tvgId)

    @Test
    fun cleanNamesDropLabelsTagsAndDecoration() {
        assertEquals("MBC 1", Tidy.cleanName("AR: MBC 1 FHD ᴴᴰ"))
        assertEquals("BBC One", Tidy.cleanName("|UK| BBC One (Backup)"))
        assertEquals("beIN SPORTS 1", Tidy.cleanName("beIN SPORTS 1 HD"))
        assertEquals("MBC Drama", Tidy.cleanName("★ MBC Drama ★"))
        assertEquals("Al Jazeera", Tidy.cleanName("[AE] Al Jazeera 1080p"))
        // Not a provider label: lower-case words stay.
        assertEquals("Up: The Movie", Tidy.cleanName("Up: The Movie"))
        // Never empty.
        assertEquals("HD", Tidy.cleanName("HD"))
    }

    @Test
    fun qualityFromTheName() {
        assertEquals("FHD", Tidy.qualityLabel("AR: MBC 1 FHD ᴴᴰ"))
        assertEquals("HD", Tidy.qualityLabel("MBC 1 ᴴᴰ"))
        assertEquals("4K", Tidy.qualityLabel("Sky Sports 4K"))
        assertEquals("SD", Tidy.qualityLabel("Al Jazeera SD"))
        assertNull(Tidy.qualityLabel("Al Jazeera"))
    }

    @Test
    fun offChangesNothing() {
        val list = listOf(live("a", "AR: MBC 1 FHD"), live("b", "AR: MBC 1 HD"))
        val r = Tidy.apply(list, clean = false, merge = false)
        assertSame(list, r.shown)
        assertTrue(r.versions.isEmpty())
    }

    @Test
    fun cleanKeepsTheGuideAndLeavesFilmsAlone() {
        val ch = live("a", "AR: MBC 1 FHD")
        val film = Channel(id = "m", name = "AR: Some Film HD", group = "Films", url = "http://x/m.mp4", kind = Kind.MOVIE)
        val r = Tidy.apply(listOf(ch, film), clean = true, merge = false)
        assertEquals("MBC 1", r.shown[0].name)
        assertEquals("FHD", r.shown[0].tidyQuality)
        assertEquals(ch.epgKey, r.shown[0].epgKey)
        assertEquals(ch.id, r.shown[0].id)
        assertSame(film, r.shown[1])
    }

    @Test
    fun mergeShowsTheBestVersionOnceInTheFirstPlace() {
        val list = listOf(
            live("hd", "AR: Al Jazeera HD"),
            live("other", "AR: BBC Arabic"),
            live("fhd", "AR: Al Jazeera FHD"),
            live("sd", "AR: Al Jazeera SD"),
            live("backup", "AR: Al Jazeera (Backup)"),
            live("en", "AR: Al Jazeera (English)"),
            live("sport", "AR: Al Jazeera HD", group = "Sports"),
        )
        val r = Tidy.apply(list, clean = false, merge = true)
        assertEquals(listOf("fhd", "other", "en", "sport"), r.shown.map { it.id })
        // Best first, then in the playlist's order within the same quality.
        assertEquals(listOf("fhd", "hd", "backup", "sd"), r.versions["fhd"]?.map { it.id })
        // Every channel stays findable (favorites and recents point at merged-away ones).
        assertEquals(list.size, r.all.size)
        assertNull(r.versions["en"])
        assertNull(r.versions["sport"])
    }

    @Test
    fun cleanAndMergeTogether() {
        val list = listOf(live("hd", "AR: MBC 1 HD"), live("fhd", "AR: MBC 1 FHD ᴴᴰ"))
        val idx = LibraryIndex.build(list, cleanNames = true, mergeDuplicates = true)
        assertEquals(listOf("fhd"), idx.live.map { it.id })
        assertEquals("MBC 1", idx.live[0].name)
        assertEquals(listOf("fhd", "hd"), idx.versions["fhd"]?.map { it.id })
        assertEquals("MBC 1", idx.byId["hd"]?.name)
        assertSame(list, idx.raw)
        assertEquals(true to true, idx.tidy)
    }
}
