package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupFoldersTest {
    private fun shape(names: List<String>) = GroupFolders.build(names).entries.map { e ->
        when (e) {
            is GroupFolders.Leaf -> e.label
            is GroupFolders.Folder -> e.name + "{" + e.groups.joinToString(",") { it.label } + "}"
        }
    }

    @Test
    fun splitsAtTheMarksProvidersUse() {
        assertEquals("UK" to "Sports", GroupFolders.split("UK | Sports"))
        assertEquals("UK" to "Sports", GroupFolders.split("UK|Sports"))
        assertEquals("UK" to "Sports HD", GroupFolders.split("|UK| Sports HD"))
        assertEquals("AR" to "Kids", GroupFolders.split("AR: Kids"))
        assertEquals("FR" to "Cinéma", GroupFolders.split("[FR] Cinéma"))
        assertEquals("DE" to "News", GroupFolders.split("(DE) News"))
        assertEquals("US" to "Movies", GroupFolders.split("US - Movies"))
        assertEquals("US" to "Movies", GroupFolders.split("US ➤ Movies"))
        assertEquals("US" to "Movies", GroupFolders.split("US ★ Movies"))
        assertEquals("عربي" to "رياضة", GroupFolders.split("عربي | رياضة"))
        assertEquals("VIP" to "UK | Sports", GroupFolders.split("VIP | UK | Sports"))
    }

    @Test
    fun leavesOrdinaryNamesAlone() {
        for (name in listOf(
            "Sports", "Sci-Fi Movies", "News 24/7", "Movies & Series", "Rock + Pop", "Kids' TV", "TOP MOVIES 4K 3840P DOLBY AUDIO (MULTI-SUBS)",
            "مسلسلات رمضان 2027", "U.S. News", "4K", "| Sports", "UK |",
        )) assertNull(name, GroupFolders.split(name))
    }

    @Test
    fun foldsGroupsThatStartTheSame() {
        val names = listOf("UK | Sports", "Favourites of mine", "UK | News", "AR: Kids", "uk | Cinema", "AR: News", "FR | Seul", "Music")
        assertEquals(listOf("UK{Sports,News,Cinema}", "Favourites of mine", "AR{Kids,News}", "FR | Seul", "Music"), shape(names))
        val tree = GroupFolders.build(names)
        assertTrue(tree.hasFolders)
        assertEquals("UK", tree.folderOf("uk | Cinema")?.name)
        assertNull(tree.folderOf("Music"))
        assertEquals("UK · News", tree.title("UK | News"))
        assertEquals("Music", tree.title("Music"))
        // Every group is still there, once, under its real name.
        val all = tree.entries.flatMap { e -> if (e is GroupFolders.Folder) e.groups.map { it.group } else listOf((e as GroupFolders.Leaf).group) }
        assertEquals(names.sorted(), all.sorted())
    }

    @Test
    fun staysFlatWhenFoldingDoesNotHelp() {
        // No marks at all.
        assertFalse(GroupFolders.build(listOf("News", "Sports", "Kids", "Music")).hasFolders)
        // One start for everything ("IPTV | …"): a single folder would only add a step.
        assertFalse(GroupFolders.build(listOf("IPTV | News", "IPTV | Sports", "IPTV | Kids")).hasFolders)
        // Starts that no two groups share.
        assertFalse(GroupFolders.build(listOf("UK | News", "AR | News", "FR | News")).hasFolders)
        assertEquals(listOf("UK | News", "AR | News"), shape(listOf("UK | News", "AR | News")))
    }

    @Test
    fun findsGroupsByAnyWordsTyped() {
        val names = listOf("UK | Sports", "AR | SPORTS HD", "US | News", "FR | Sport", "Kids")
        assertEquals(listOf("UK | Sports", "AR | SPORTS HD", "FR | Sport"), GroupFolders.find(names, "spo"))
        assertEquals(listOf("AR | SPORTS HD"), GroupFolders.find(names, "hd ar"))
        assertEquals(emptyList<String>(), GroupFolders.find(names, "   "))
        assertEquals(emptyList<String>(), GroupFolders.find(names, "cinema"))
    }

    @Test
    fun tellsDividersFromChannels() {
        // Lines the provider puts between channels.
        for (n in listOf("##### 4K UHD #####", "#####", "  ===== UK SPORTS =====", "***** RELAX *****", "━━━━━━", "▃ ▅ ▆ ▇", "---- NEWS ----", "●●● KIDS ●●●")) {
            assertTrue(n, isDividerName(n))
        }
        // Channels, whatever is written round their names.
        for (n in listOf("BBC One", "4K| RELAX ᵁᴴᴰ ³⁸⁴⁰ᴾ ☼", "#1 Sports", "## Music", "[HD] Sky News", "🇬🇧 BBC One", "+18 Cinema", "★ VIP 1", "24/7 Cartoons", "", "  ", "-")) {
            assertFalse(n, isDividerName(n))
        }
    }
}
