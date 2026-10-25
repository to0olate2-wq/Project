package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Searching in Arabic finds names written in Latin letters, and the other way round. */
class CrossScriptTest {
    private val names = listOf(
        "Al Jazeera HD", "AR: Al Arabiya", "MBC 1", "MBC 2 FHD", "Rotana Cinema", "Dubai One", "Sky News Arabia",
        "beIN Sports 1", "Cartoon Network Arabic", "Spacetoon", "Abu Dhabi TV", "Shahid VIP", "OSN Movies", "Al Hayat",
        "Spider-Man: No Way Home", "Jazz Radio", "CNN International", "BBC Arabic", "Mubasher Qatar",
        "الجزيرة", "العربية HD", "ام بي سي 4", "روتانا سينما", "دبي ون", "سكاي نيوز عربية", "بي ان سبورت 2", "سبيستون",
        "أبوظبي", "شاهد", "الحياة", "سي ان ان", "كرتون نتورك",
    )
    private val index = SearchIndex(names.mapIndexed { i, n -> Channel(id = "$i", name = n, group = "", url = "u$i") })

    private fun find(q: String) = index.find(q).map { it.name }

    private fun finds(q: String, want: String) {
        val got = find(q)
        assertTrue("\"$q\" should find \"$want\", found $got", want in got)
    }

    @Test
    fun arabicFindsLatin() {
        finds("الجزيرة", "Al Jazeera HD")
        finds("الجزيره", "Al Jazeera HD")
        finds("العربية", "AR: Al Arabiya")
        finds("ام بي سي", "MBC 1")
        finds("روتانا سينما", "Rotana Cinema")
        finds("دبي ون", "Dubai One")
        finds("سكاي نيوز", "Sky News Arabia")
        finds("بي ان سبورت", "beIN Sports 1")
        finds("سبيستون", "Spacetoon")
        finds("سبيس تون", "Spacetoon")
        finds("ابوظبي", "Abu Dhabi TV")
        finds("شاهد", "Shahid VIP")
        finds("سبايدر مان", "Spider-Man: No Way Home")
        finds("الحياة", "Al Hayat")
        finds("سي ان ان", "CNN International")
        finds("بي بي سي", "BBC Arabic")
        finds("كرتون نتورك", "Cartoon Network Arabic")
        finds("مباشر", "Mubasher Qatar")
    }

    @Test
    fun latinFindsArabic() {
        finds("aljazeera", "الجزيرة")
        finds("al jazeera", "الجزيرة")
        finds("jazira", "الجزيرة")
        finds("arabiya", "العربية HD")
        finds("mbc", "ام بي سي 4")
        finds("mbc 4", "ام بي سي 4")
        finds("rotana", "روتانا سينما")
        finds("dubai one", "دبي ون")
        finds("sky news", "سكاي نيوز عربية")
        finds("bein sports", "بي ان سبورت 2")
        finds("spacetoon", "سبيستون")
        finds("abu dhabi", "أبوظبي")
        finds("shahid", "شاهد")
        finds("al hayat", "الحياة")
        finds("cnn", "سي ان ان")
        finds("cartoon network", "كرتون نتورك")
    }

    @Test
    fun numbersMustAgree() {
        val got = find("ام بي سي 2")
        assertTrue(got.toString(), "MBC 2 FHD" in got)
        assertFalse(got.toString(), "MBC 1" in got)
    }

    @Test
    fun sameScriptComesFirstAndLooseOnesStayOut() {
        assertEquals("الجزيرة", find("الجزيرة").first())
        assertTrue(find("mbc").take(2).all { it.startsWith("MBC") })
        assertFalse("Jazz Radio" in find("الجزيرة"))
        assertFalse("Spacetoon" in find("الجزيرة"))
    }

    @Test
    fun guideTitlesToo() {
        val q = SearchQuery("الجزيرة")
        assertEquals(0, q.score("Al Jazeera Documentary"))
        assertEquals(CrossScript.WORDS, q.scoreAcross("Al Jazeera Documentary"))
        assertEquals(0, SearchQuery("كرة القدم").scoreAcross("Weather"))
    }
}
