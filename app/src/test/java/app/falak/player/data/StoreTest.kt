package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The saved playlist and guide files: group listing for the phone page, and guide scope. */
class StoreTest {
    private fun tmp(name: String) = File.createTempFile(name, ".bin").apply { deleteOnExit() }

    @Test
    fun groupsAreListedPerKindInPlaylistOrder() {
        val list = listOf(
            Channel(id = "1", name = "One", group = "News", url = "u1", catchupDays = 2, catchupType = "xc", userAgent = "ua"),
            Channel(id = "2", name = "Two", group = "Sports", url = "u2", logo = "l", tvgId = "t"),
            Channel(id = "3", name = "Three", group = "News", url = "u3"),
            Channel(id = "m1", name = "Film", group = "TOP MOVIES 4K", url = "m", kind = Kind.MOVIE, rating = "7.1", added = 5),
            Channel(id = "s1", name = "Show", group = "Drama", url = "", kind = Kind.SERIES, seriesId = "9", info = "i"),
        )
        val f = tmp("playlist")
        Store.writePlaylist(f, PlaylistCache(list, "http://guide", "Asia/Dubai", 42))
        val groups = Store.readGroups(f)
        assertEquals(listOf("News" to 2, "Sports" to 1), groups.getValue(Kind.LIVE).toList())
        assertEquals(listOf("TOP MOVIES 4K" to 1), groups.getValue(Kind.MOVIE).toList())
        assertEquals(listOf("Drama" to 1), groups.getValue(Kind.SERIES).toList())
        // The full reader still agrees with the file.
        assertEquals(list, Store.readPlaylist(f).channels)
    }

    @Test
    fun guideSkipsChannelsOutsideTheWantedOnes() {
        fun p(t: Long) = Program(t, t + 1_800_000, "Show $t", "About $t")
        val programs = mapOf("a" to listOf(p(0), p(1_800_000)), "b" to listOf(p(0)), "c" to listOf(p(0)))
        val f = tmp("guide")
        Store.writeGuide(f, GuideCache(1_000, programs, 3, 3, listOf(GuideChannel("x", "X", "src")), scope = setOf("a", "b", "c")))

        val all = Store.readGuide(f)
        assertEquals(setOf("a", "b", "c"), all.programs.keys)
        assertEquals(setOf("a", "b", "c"), all.scope)

        val some = Store.readGuide(f, only = setOf("a", "d"))
        assertEquals(setOf("a"), some.programs.keys)
        assertEquals(programs.getValue("a"), some.programs.getValue("a"))
        assertEquals(1, some.matched)
        assertEquals(2, some.total)
        assertEquals("X", some.channels.single().name)
        // "d" was never looked for: a download is needed before it can have a guide.
        assertTrue("d" !in some.scope)
    }
}
