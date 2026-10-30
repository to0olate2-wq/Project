package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ArrangementTest {
    private fun live(id: String, group: String) = Channel(id = id, name = id, group = group, url = "http://host/$id.ts", kind = Kind.LIVE)

    private val list = listOf(
        live("a1", "News"), live("a2", "News"), live("b1", "Sports"), live("b2", "Sports"), live("b3", "Sports"), live("c1", "Kids"),
    )

    @Test
    fun groupsAndChannelsInTheOrderChosen() {
        val order = Arrangement(
            groups = mapOf("LIVE" to listOf("Sports")),
            channels = mapOf("LIVE|Sports" to listOf("b3")),
        )
        val index = LibraryIndex.build(list, arrangement = order)
        assertEquals(listOf("Sports", "News", "Kids"), index.groupsOf(Kind.LIVE).keys.toList())
        assertEquals(listOf("b3", "b1", "b2"), index.groupsOf(Kind.LIVE)["Sports"]!!.map { it.id })
        // "All channels" follows the same order; the playlist's own order is kept underneath.
        assertEquals(listOf("b3", "b1", "b2", "a1", "a2", "c1"), index.live.map { it.id })
        assertEquals(list.map { it.id }, index.all.map { it.id })
    }

    @Test
    fun goneOnesAreSkippedAndNewOnesFollow() {
        // "x9" left the playlist; "b3" is new since the order was chosen.
        val order = Arrangement(channels = mapOf("LIVE|Sports" to listOf("b2", "x9")))
        val index = LibraryIndex.build(list, arrangement = order)
        assertEquals(listOf("b2", "b1", "b3"), index.groupsOf(Kind.LIVE)["Sports"]!!.map { it.id })
    }

    @Test
    fun noOrderLeavesTheListsAsTheyCame() {
        val index = LibraryIndex.build(list)
        assertEquals(listOf("News", "Sports", "Kids"), index.groupsOf(Kind.LIVE).keys.toList())
        assertEquals(list.map { it.id }, index.live.map { it.id })
    }

    @Test
    fun onlyWhatDiffersFromThePlaylistIsKept() {
        val natural = mapOf("A" to 0, "B" to 1, "C" to 2, "D" to 3)
        // C to the top: only C needs keeping.
        assertEquals(listOf("C"), Arrangement.trimmed(listOf("C", "A", "B", "D"), natural))
        // A down one is the same as B to the top: only B.
        assertEquals(listOf("B"), Arrangement.trimmed(listOf("B", "A", "C", "D"), natural))
        // D up one (between B and C): A, B, D.
        assertEquals(listOf("A", "B", "D"), Arrangement.trimmed(listOf("A", "B", "D", "C"), natural))
        // Back as it was: nothing kept.
        assertEquals(emptyList<String>(), Arrangement.trimmed(listOf("A", "B", "C", "D"), natural))
    }
}
