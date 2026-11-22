package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 8.44: "Films on now" on Live TV. */
class FilmsOnNowTest {
    private val now = 1_700_000_000_000L
    private val min = 60_000L

    private fun live(id: String, name: String, group: String) = Channel(id = id, name = name, group = group, url = "u$id", tvgId = id)

    private fun on(title: String, startedMinAgo: Long, leftMin: Long, film: Boolean = false) =
        listOf(Program(now - startedMinAgo * min, now + leftMin * min, title, "", film))

    @Test
    fun theGuidesTypesAndFilmChannelsCount() {
        val channels = listOf(
            live("1", "One", "General"), // the guide says it's a film
            live("2", "Cinema 1", "Entertainment"), // a film channel
            live("3", "News 24", "News"), // neither
            live("4", "Two", "UK | Movies"), // a film channel by its group
            live("5", "Three", "UK | Movies"), // a trailer
            live("6", "Four", "UK | Movies"), // nearly over
        )
        val epg = mapOf(
            "1" to on("Heat", 20, 140, film = true),
            "2" to on("The Matrix", 30, 100),
            "3" to on("World Today", 10, 20),
            "4" to on("Up", 5, 85),
            "5" to on("Trailer: Dune", 1, 15),
            "6" to on("Jaws", 110, 5),
        )
        val found = FilmsOnNow.find(channels, epg, now)
        // The most left to watch first.
        assertEquals(listOf("Heat", "The Matrix", "Up"), found.map { it.title })
    }

    @Test
    fun oneFilmOnTwoChannelsIsOneCard() {
        val channels = listOf(live("1", "Movies 1", "Films"), live("2", "Movies 2", "Films"))
        val epg = mapOf("1" to on("Heat", 20, 140), "2" to on("HEAT", 20, 140))
        val found = FilmsOnNow.find(channels, epg, now)
        assertEquals(1, found.size)
        assertEquals(listOf("1", "2"), found.single().channels.map { it.id })
    }

    @Test
    fun typesOfTheGuide() {
        assertTrue(FilmsOnNow.isFilmCategory("Movie"))
        assertTrue(FilmsOnNow.isFilmCategory("Movie / Drama"))
        assertTrue(FilmsOnNow.isFilmCategory("Spielfilm"))
        assertTrue(FilmsOnNow.isFilmCategory("أفلام"))
        assertFalse(FilmsOnNow.isFilmCategory("News"))
        assertFalse(FilmsOnNow.isFilmCategory("Sports"))
    }
}
