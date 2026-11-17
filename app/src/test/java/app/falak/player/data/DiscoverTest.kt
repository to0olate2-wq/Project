package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 8.34: one poster per title, collections found in names, and the sports hub's matches. */
class DiscoverTest {
    private fun film(id: String, name: String, group: String = "Films") = Channel(id = id, name = name, group = group, url = "http://x/$id", kind = Kind.MOVIE)

    @Test
    fun copiesOfOneTitleGoTogether() {
        val l = listOf(
            film("1", "EN - Dune (2021)", "EN Films"),
            film("2", "4K - Dune (2021) 4K", "4K Films"),
            film("3", "Dune (1984)"),
            film("4", "MULTI-SUB | Dune (2021)"),
            film("5", "Heat"),
        )
        val keys = Versions.keys(l, 2026)
        assertTrue(keys["1"] != null && keys["1"] == keys["2"] && keys["2"] == keys["4"])
        assertNull(keys["3"]) // another year: another film
        assertEquals(listOf("1", "3", "5"), Versions.oneEach(l, keys).map { it.id })
        assertEquals(listOf("EN", "4K", "MULTI · SUB"), Versions.labels(listOf(l[0], l[1], l[3]), 2026))
        // Series by their folders; episodes never.
        val s1 = Channel(id = "s1", name = "EN - Breaking Bad", group = "EN", url = "", kind = Kind.SERIES, seriesId = "11")
        val s2 = Channel(id = "s2", name = "AR - Breaking Bad", group = "AR", url = "", kind = Kind.SERIES, seriesId = "12")
        val ep = Channel(id = "e1", name = "Breaking Bad S01E01", group = "S1", url = "http://e", kind = Kind.SERIES)
        assertEquals(setOf("s1", "s2"), Versions.keys(listOf(s1, s2, ep), 2026).keys)
        // A title in capitals isn't taken for a tag.
        assertEquals("Mission: Impossible (1996)", Versions.withoutTags("Mission: Impossible (1996)"))
    }

    @Test
    fun findsCollectionsInNames() {
        assertEquals("John Wick" to true, Collections.stem("John Wick: Chapter 3 - Parabellum"))
        assertEquals("The Godfather" to true, Collections.stem("The Godfather Part II"))
        assertEquals("Heat" to false, Collections.stem("Heat"))
        assertEquals("2012" to false, Collections.stem("2012"))
        val films = listOf(
            "Toy Story (1995)", "Toy Story 2 (1999)", "Toy Story 3 (2010)", "John Wick (2014)", "John Wick: Chapter 2 (2017)",
            "Mission: Impossible (1996)", "Mission: Impossible - Fallout (2018)", "Mission: Impossible 2 (2000)",
            "Avengers: Endgame (2019)", "Avengers: Infinity War (2018)", "Alien (1979)", "Aliens (1986)",
            "الفيل الأزرق (2014)", "الفيل الأزرق 2 (2019)", "Dune (2021)", "Dune (1984)",
        ).mapIndexed { i, n -> film("c$i", n) }
        val found = Collections.find(films, 2026).associate { c -> c.name to c.films.map { it.name } }
        assertEquals(listOf("Toy Story (1995)", "Toy Story 2 (1999)", "Toy Story 3 (2010)"), found["Toy Story"])
        assertEquals(listOf("Mission: Impossible (1996)", "Mission: Impossible 2 (2000)", "Mission: Impossible - Fallout (2018)"), found["Mission: Impossible"])
        assertEquals(listOf("Avengers: Infinity War (2018)", "Avengers: Endgame (2019)"), found["Avengers"])
        assertEquals(2, found["الفيل الأزرق"]?.size)
        assertTrue("John Wick" in found)
        // Same name, no sequel mark: two films, not a collection.
        assertFalse("Dune" in found)
        assertFalse(found.keys.any { it.startsWith("Alien") })
    }

    @Test
    fun findsMatchesInTheGuide() {
        assertTrue(Sports.isMatch("Real Madrid v Barcelona"))
        assertTrue(Sports.isMatch("LIVE: Arsenal vs. Chelsea"))
        assertTrue(Sports.isMatch("Formula 1: Abu Dhabi Grand Prix - Race"))
        assertTrue(Sports.isMatch("مباشر: الهلال ضد النصر"))
        assertFalse(Sports.isMatch("Premier League Highlights"))
        assertFalse(Sports.isMatch("ملخص مباراة الهلال والنصر"))
        assertFalse(Sports.isMatch("Football Classics: Liverpool v Milan"))
        assertFalse(Sports.isMatch("Sky Sports News"))
        assertEquals("Arsenal vs. Chelsea", Sports.display("LIVE: Arsenal vs. Chelsea"))

        val now = 1_000_000_000_000L
        val b1 = Channel(id = "l1", name = "beIN Sports 1 HD", group = "AR | Sports", url = "u1", tvgId = "b1")
        val b2 = Channel(id = "l2", name = "beIN Sports 1 FHD", group = "AR | Sports", url = "u2", tvgId = "b1b")
        val other = Channel(id = "l3", name = "MBC 1", group = "AR | General", url = "u3", tvgId = "m1")
        val epg = mapOf(
            b1.epgKey to listOf(
                Program(now - 3_600_000, now + 1_800_000, "LIVE: Real Madrid v Barcelona", ""),
                Program(now + 7_200_000, now + 13_000_000, "Arsenal v Chelsea", ""),
            ),
            b2.epgKey to listOf(Program(now - 3_600_000, now + 1_800_000, "Real Madrid v Barcelona", "")),
            other.epgKey to listOf(Program(now, now + 60_000, "Tom v Jerry", "")),
        )
        val ms = Sports.find(listOf(b1, b2, other), epg, now)
        assertEquals(listOf("Real Madrid v Barcelona", "Arsenal v Chelsea"), ms.map { it.title })
        assertEquals(listOf("l1", "l2"), ms[0].channels.map { it.id })
        assertTrue(ms[0].live(now))
        assertFalse(ms[1].live(now))
    }
}
