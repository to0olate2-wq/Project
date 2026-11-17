package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Android TV home screen's row (8.35): which channels go there. */
class TvHomeRowTest {
    private fun ch(id: String, group: String = "News", kind: Kind = Kind.LIVE) =
        Channel(id = id, name = "Channel $id", group = group, url = "http://x/$id", kind = kind)

    @Test
    fun favoritesThenRecentsEachOnce() {
        val picked = TvHomeRow.pick(
            favorites = listOf(ch("1"), ch("2"), ch("m", kind = Kind.MOVIE)),
            recents = listOf(ch("3"), ch("1"), ch("4", group = "Locked"), ch("5", group = "XXX Night")),
            excluded = setOf("Locked"),
        )
        assertEquals(listOf("1", "2", "3"), picked.map { it.id })
    }

    @Test
    fun keepsTheRowShort() {
        val picked = TvHomeRow.pick((1..30).map { ch("$it") }, emptyList(), emptySet())
        assertEquals(12, picked.size)
    }
}
