package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleMatchTest {
    @Test
    fun namesAsProvidersWriteThem() {
        val y = 2026
        assertEquals("The Matrix" to 1999, TitleMatch.clean("EN - The Matrix (1999) 4K", y))
        assertEquals("The Matrix" to 1999, TitleMatch.clean("The Matrix 1999", y))
        assertEquals("Breaking Bad" to null, TitleMatch.clean("|AR| Breaking Bad", y))
        assertEquals("Breaking Bad" to 2008, TitleMatch.clean("[MULTI] Breaking Bad (2008) [FHD]", y))
        assertEquals("Dune: Part Two" to 2024, TitleMatch.clean("4K - Dune: Part Two (2024) HEVC", y))
        assertEquals("1917" to 2019, TitleMatch.clean("1917 (2019)", y))
        assertEquals("Blade Runner 2049" to null, TitleMatch.clean("Blade Runner 2049", y))
        assertEquals("UFO: Robot Grendizer" to null, TitleMatch.clean("UFO: Robot Grendizer", y))
        assertEquals("الرسالة" to 1976, TitleMatch.clean("AR: الرسالة (1976)", y))
        assertEquals("Spider-Man: No Way Home" to 2021, TitleMatch.clean("ENG | Spider-Man: No Way Home (2021) 1080p", y))
    }

    // As Cinemeta answered (November 2026).
    private val matrix = """{"metas":[{"id":"tt0133093","imdb_id":"tt0133093","name":"The Matrix","releaseInfo":"1999"},
        {"id":"tt10838180","name":"The Matrix Resurrections","releaseInfo":"2021"},{"id":"tt0234215","name":"The Matrix Reloaded","releaseInfo":"2003"},
        {"id":"tt16409916","name":"Rifftrax: The Matrix","releaseInfo":"2006"}]}"""
    private val breaking = """{"metas":[{"id":"tt0903747","name":"Breaking Bad","releaseInfo":"2008–2013"},
        {"id":"tt2387761","name":"Breaking Bad: Original Minisodes","releaseInfo":"2009"},{"id":"tt4516518","name":"Fixing Breaking Bad","releaseInfo":"2013"}]}"""

    @Test
    fun onlyASureMatch() {
        val m = TitleMatch.parse(matrix)
        assertEquals(4, m.size)
        assertEquals("tt0133093", TitleMatch.pick(m, "The Matrix", 1999))
        assertEquals("tt0133093", TitleMatch.pick(m, "the matrix", null))
        // A year a little off (release dates differ by country), and not the same at all.
        assertEquals("tt0133093", TitleMatch.pick(m, "The Matrix", 2000))
        assertNull(TitleMatch.pick(m, "The Matrix", 2005))
        assertNull(TitleMatch.pick(m, "Matrix", 1999))
        assertEquals("tt0903747", TitleMatch.pick(TitleMatch.parse(breaking), "Breaking Bad", null))
        assertEquals("tt0903747", TitleMatch.pick(TitleMatch.parse(breaking), "Breaking Bad", 2008))
        // Two titles with the same name and no year: none.
        val two = TitleMatch.parse("""{"metas":[{"id":"tt0087182","name":"Dune","releaseInfo":"1984"},{"id":"tt1160419","name":"Dune","releaseInfo":"2021"}]}""")
        assertNull(TitleMatch.pick(two, "Dune", null))
        assertEquals("tt1160419", TitleMatch.pick(two, "Dune", 2021))
        // An Arabic name: Cinemeta's search answers with unrelated English titles; none is taken.
        val unrelated = TitleMatch.parse("""{"metas":[{"id":"tt1893273","name":"The Last Letter from Your Lover","releaseInfo":"2021"}]}""")
        assertNull(TitleMatch.pick(unrelated, "الرسالة", 1976))
        assertEquals("amelie and co", TitleMatch.norm("Amélie & Co."))
        assertEquals("https://v3-cinemeta.strem.io/catalog/series/top/search=Breaking%20Bad.json", TitleMatch.searchUrl("series", "Breaking Bad"))
    }
}
