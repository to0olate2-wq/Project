package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private val names = listOf(
        "AR: Harbour (2024) 4K", "Harbour Lights", "The Long Harbour", "Spider-Man: No Way Home", "SPIDER-MAN 2 [MULTI-SUB]",
        "Spiderman Returns", "beIN SPORTS 1 HD", "beIN Sports 2", "قناة الجزيرة", "الجزيرة مباشر", "MBC Drama ᴴᴰ",
        "Ocean's Eleven", "Amélie", "CSI: Miami", "Blade Runner 2049", "Sky News", "SkyNews Arabia", "إم بي سي ٤",
        "Batman Begins", "The Batman", "الأخبار", "Game of Thrones",
    )
    private val index = SearchIndex(names.mapIndexed { i, n -> Channel(id = "$i", name = n, group = "G", url = "") })

    private fun find(q: String, n: Int = 10) = index.find(q, n).map { it.name }

    @Test
    fun normalizesCaseAccentsArabicFormsAndDigits() {
        assertEquals("ar spider man 2024 hd", SearchText.normalize("AR: Spider-Man (2024) ᴴᴰ"))
        assertEquals("amelie oceans", SearchText.normalize("Amélie — Ocean's"))
        assertEquals("ااا ه ي و ي 24", SearchText.normalize("أإآ ة ى ؤ ئ ٢٤"))
        assertEquals("الجزيره", SearchText.normalize("الجَزِيـرة"))
    }

    @Test
    fun bareTitleDropsProviderLabels() {
        assertEquals("harbour", SearchText.core("AR: Harbour (2024) 4K"))
        assertEquals("the office", SearchText.core("|EN| The Office [MULTI-SUB] FHD"))
        assertEquals("1917", SearchText.core("1917"))
    }

    @Test
    fun exactTitleFirstThenStartsWithThenContains() {
        assertEquals(listOf("AR: Harbour (2024) 4K", "Harbour Lights", "The Long Harbour"), find("harbour"))
        assertEquals("Harbour Lights", find("harbour li").first())
        assertEquals("CSI: Miami", find("csi miami").single())
        assertEquals("Blade Runner 2049", find("blade runner 2049").single())
    }

    @Test
    fun wordsInAnyOrderAndJoinedOrSplit() {
        assertEquals(find("spider man"), find("man spider"))
        assertEquals(3, find("spiderman").size)
        assertEquals(3, find("spider man").size)
        assertEquals(listOf("beIN Sports 2", "beIN SPORTS 1 HD"), find("beinsports"))
        assertEquals(listOf("beIN SPORTS 1 HD"), find("bein 1"))
        assertEquals(listOf("Sky News", "SkyNews Arabia").toSet(), find("news").toSet())
    }

    @Test
    fun accentsApostrophesAndTagsDontMatter() {
        assertEquals(listOf("Amélie"), find("amelie"))
        assertEquals(listOf("Ocean's Eleven"), find("oceans"))
        assertEquals(listOf("Ocean's Eleven"), find("ocean's"))
        assertEquals(listOf("MBC Drama ᴴᴰ"), find("mbc hd"))
    }

    @Test
    fun arabicWithOrWithoutAlAndLetterForms() {
        assertEquals(2, find("الجزيرة").size)
        assertEquals(2, find("جزيرة").size)
        assertEquals(2, find("الجزيره").size)
        assertEquals(listOf("الأخبار"), find("اخبار"))
        assertTrue("إم بي سي ٤" in find("4"))
        assertTrue("إم بي سي ٤" in find("٤"))
    }

    @Test
    fun oneTypoForgivenOnLongerWordsOnly() {
        assertEquals(listOf("Harbour Lights", "The Long Harbour", "AR: Harbour (2024) 4K").toSet(), find("harbur").toSet())
        assertTrue("The Batman" in find("batmn"))
        assertTrue("Game of Thrones" in find("thornes"))
        // Short words must match as typed.
        assertTrue(find("mbd").isEmpty())
        assertTrue(find("nothing like it").isEmpty())
    }

    @Test
    fun typoMatchesRankBelowRealOnes() {
        val list = listOf("Harbor News", "The Long Harbour").mapIndexed { i, n -> Channel(id = "$i", name = n, group = "G", url = "") }
        assertEquals(listOf("The Long Harbour", "Harbor News"), SearchIndex(list).find("harbour").map { it.name })
    }

    @Test
    fun emptySearchFindsNothingAndFilterApplies() {
        assertTrue(find("").isEmpty())
        assertTrue(find("  -  ").isEmpty())
        assertEquals(listOf("Harbour Lights"), index.find("harbour") { it.name.endsWith("Lights") }.map { it.name })
        assertEquals(2, index.find("harbour", limit = 2).size)
    }

    @Test
    fun oneEditDistance() {
        assertTrue(SearchText.withinOneEdit("harbour", "harbor"))
        assertTrue(SearchText.withinOneEdit("thrones", "thornes"))
        assertTrue(SearchText.withinOneEdit("batman", "batmen"))
        assertFalse(SearchText.withinOneEdit("batman", "batmobile"))
        assertFalse(SearchText.withinOneEdit("abcd", "dcba"))
    }

    @Test
    fun programmeTitles() {
        val q = SearchQuery("big match")
        assertTrue(q.score("The Big Match") > 0)
        assertTrue(q.score("Match: The Big One") > 0)
        assertEquals(0, q.score("News"))
        assertTrue(SearchQuery("the big match").score("The Big Match") > SearchQuery("the big match").score("The Big Match Preview"))
    }

    @Test
    fun fastOnBigPlaylists() {
        val words = listOf("the", "man", "love", "war", "night", "city", "king", "dark", "AR:", "FHD", "حب", "الليل", "مدينة")
        val r = java.util.Random(1)
        val big = (0 until 150_000).map { i ->
            Channel(id = "$i", name = (0 until 3).joinToString(" ") { words[r.nextInt(words.size)] } + " $i", group = "G", url = "")
        }
        val idx = SearchIndex(big)
        repeat(3) { idx.find("kingg dark", 50) } // warm up
        val t = System.nanoTime()
        val hits = idx.find("kingg dark", 50)
        val ms = (System.nanoTime() - t) / 1_000_000
        println("150,000 names searched in $ms ms")
        assertEquals(50, hits.size)
        assertTrue("too slow: $ms ms", ms < 1_500)
    }
}
