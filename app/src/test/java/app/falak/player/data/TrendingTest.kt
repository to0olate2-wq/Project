package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendingTest {
    private val now = 1_800_000_000L

    private fun film(name: String, group: String, daysAgo: Int, rating: String) = Channel(
        id = "f-$group-$name", name = name, group = group, url = "http://x/movie/$name.mp4", kind = Kind.MOVIE,
        added = if (daysAgo < 0) 0 else now - daysAgo * 86_400L, rating = rating,
    )

    private fun series(name: String, group: String, daysAgo: Int, rating: String) = Channel(
        id = "s-$group-$name", name = name, group = group, url = "", seriesId = "id-$name", kind = Kind.SERIES,
        added = now - daysAgo * 86_400L, rating = rating,
    )

    private fun groups(vararg items: Channel) = items.groupByTo(LinkedHashMap()) { it.group }

    @Test
    fun whatIsNewComesBeforeTheProvidersLists() {
        // (Until 8.22 the provider's list came first, in its order: the same row every day.)
        val movies = groups(
            film("Pick One", "Trending Now", 400, ""),
            film("Pick Two", "Trending Now", 400, ""),
            film("Fresh", "Action", 1, "7.0"),
        )
        assertEquals(listOf("Fresh", "Pick One", "Pick Two"), Trending.pick(movies, emptyMap(), now).map { it.name })
    }

    @Test
    fun newCountsForMoreThanWellRated() {
        val movies = groups(
            film("Old Gem", "Drama", 100, "9.0"),
            film("New Good", "Drama", 2, "7.0"),
            film("Last Month", "Drama", 30, "8.0"),
            film("Too Old", "Drama", 200, "9.9"),
            film("No Rating", "Drama", 1, ""),
            film("No Date", "Drama", -1, "9.5"),
        )
        assertEquals(listOf("New Good", "No Rating", "Last Month", "Old Gem"), Trending.pick(movies, emptyMap(), now).map { it.name })
    }

    @Test
    fun anOldFilmIsNotNew_wheneverItWasAdded() {
        // (now is January 2027.)
        val movies = groups(
            film("The Godfather (1972)", "Classics", 1, "9.2"),
            film("Heat 1995 4K", "Classics", 1, "8.3"),
            film("This Winter (2026)", "Drama", 20, "6.5"),
            film("Blade Runner 2049", "Sci-Fi", 3, "8.0"),
        )
        assertEquals(listOf("Blade Runner 2049", "This Winter (2026)"), Trending.pick(movies, emptyMap(), now).map { it.name })
    }

    @Test
    fun aDayThatStampedTheWholeLibraryIsNotNews() {
        // The provider moved its library two days ago: 300 films carry that day.
        val moved = (1..300).map { film("Library $it", "All", 2, "9.0") }
        val movies = groups(*(moved + film("Really New", "All", 5, "6.0") + film("Out Now (2027)", "All", 2, "7.0")).toTypedArray())
        assertEquals(listOf("Really New", "Out Now (2027)"), Trending.pick(movies, emptyMap(), now).map { it.name })
    }

    @Test
    fun withoutDates_theYearInTheNameDecides() {
        // (An M3U playlist has no dates.)
        val movies = groups(
            film("Old One (2019)", "Films", -1, "9.0"),
            film("Last Year (2026)", "Films", -1, "9.0"),
            film("This Year (2027)", "Films", -1, "7.0"),
            film("No Year", "Films", -1, "9.9"),
        )
        assertEquals(listOf("This Year (2027)", "Last Year (2026)"), Trending.pick(movies, emptyMap(), now).map { it.name })
    }

    @Test
    fun aPopularListCountsALittleMore() {
        val movies = groups(
            film("Plain", "Drama", 3, "7.0"),
            film("Listed", "Drama", 3, "7.0"),
            film("Listed", "Most Watched", 3, "7.0"),
        )
        assertEquals(listOf("Listed", "Plain"), Trending.pick(movies, emptyMap(), now).map { it.name })
    }

    @Test
    fun yearsInNames() {
        assertEquals(1995, Trending.yearIn("Heat (1995)", 2027))
        assertEquals(2024, Trending.yearIn("Dune Part Two 2024", 2027))
        assertEquals(2025, Trending.yearIn("Sinners [2025] 4K", 2027))
        assertEquals(2019, Trending.yearIn("1917 (2019)", 2027))
        assertEquals(2017, Trending.yearIn("Blade Runner 2049 (2017)", 2027))
        assertNull(Trending.yearIn("Blade Runner 2049", 2027))
        assertNull(Trending.yearIn("Inception", 2027))
        assertNull(Trending.yearIn("Channel 12019", 2027))
    }

    @Test
    fun filmsAndSeriesTakeTurns() {
        val movies = groups(film("F1", "Drama", 1, "8.0"), film("F2", "Drama", 2, "7.0"))
        val shows = groups(series("S1", "Shows", 1, "8.0"), series("S2", "Shows", 2, "7.0"))
        assertEquals(listOf("F1", "S1", "F2", "S2"), Trending.pick(movies, shows, now).map { it.name })
    }

    @Test
    fun adultGroupsAndRepeatsLeftOut() {
        val movies = groups(
            film("Hidden", "XXX Trending", 1, "9.0"),
            film("Twice", "Popular", 1, "8.0"),
            film("Twice", "Drama", 1, "8.0"),
        )
        assertEquals(listOf("Twice"), Trending.pick(movies, emptyMap(), now).map { it.name })
    }

    @Test
    fun episodesAreNotPicked() {
        val episode = Channel(id = "e1", name = "Episode 1", group = "Top 10 Series", url = "http://x/series/1.mp4", kind = Kind.SERIES)
        assertTrue(Trending.pick(emptyMap(), groups(episode), now).isEmpty())
    }

    @Test
    fun ratings() {
        assertEquals(7.4, Trending.rating("7.4")!!, 1e-9)
        assertEquals(7.4, Trending.rating("74")!!, 1e-9)
        assertEquals(7.5, Trending.rating("7,5")!!, 1e-9)
        assertNull(Trending.rating("0"))
        assertNull(Trending.rating(""))
        assertNull(Trending.rating("N/A"))
    }

    @Test
    fun trendingGroupNames() {
        listOf("Trending Now", "POPULAR MOVIES", "Top 10 Netflix", "TOP-20", "Most Watched", "الأكثر مشاهدة", "ترند").forEach {
            assertTrue(it, Trending.GROUP.containsMatchIn(it))
        }
        listOf("TOP MOVIES 4K 3840P DOLBY AUDIO (MULTI-SUBS)", "Drama", "Hotstar", "Stop Motion").forEach {
            assertFalse(it, Trending.GROUP.containsMatchIn(it))
        }
    }
}
