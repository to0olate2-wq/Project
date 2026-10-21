package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/** A refresh must keep unchanged entries instead of holding a second copy of a big playlist. */
class ReuseTest {
    private fun m3u(titles: List<String>) = buildString {
        append("#EXTM3U\n")
        titles.forEachIndexed { i, t ->
            append("#EXTINF:-1 tvg-id=\"c$i\" tvg-logo=\"http://img.example/$i.jpg\" group-title=\"Films ${i % 7}\",$t\n")
            append("http://example.com/movie/u/p/$i.mkv\n")
        }
    }

    @Test
    fun refreshKeepsUnchangedEntries() {
        val first = M3uParser.parse(m3u(List(500) { "Film $it" }).reader().buffered()).channels
        val titles = List(500) { if (it == 7) "Film 7 (new cut)" else "Film $it" }
        val second = M3uParser.parse(m3u(titles).reader().buffered(), Reuse.from(first.associateBy { it.id })).channels

        assertEquals(500, second.size)
        for (i in second.indices) {
            if (i == 7) assertNotSame(first[i], second[i]) else assertSame(first[i], second[i])
        }
        assertEquals("Film 7 (new cut)", second[7].name)
    }

    @Test
    fun groupsAreStoredOnce() {
        val list = M3uParser.parse(m3u(List(700) { "Film $it" }).reader().buffered()).channels
        val distinct = list.map { System.identityHashCode(it.group) to it.group }.toSet()
        assertEquals(7, distinct.size)
    }

    @Test
    fun guideKeyIsWorkedOutOnceAndNotPartOfEquality() {
        val a = Channel(id = "1", name = "UK: BBC One HD", group = "UK", url = "u")
        val b = a.copy()
        val key = a.epgKey
        assertSame(key, a.epgKey)
        assertEquals(key, b.epgKey)
        assertEquals(a, b)
        assertEquals("bbc.one", Channel(id = "2", name = "x", group = "g", url = "u", tvgId = "BBC.One").epgKey)
    }
}
