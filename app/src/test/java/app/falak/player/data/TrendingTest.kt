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
    fun providerTrendingGroupsComeFirst() {
        val movies = groups(
            film("Fresh", "Action", 1, "9.0"),
            film("Pick One", "Trending Now", 400, ""),
            film("Pick Two", "Trending Now", 400, ""),
        )
        assertEquals(listOf("Pick One", "Pick Two", "Fresh"), Trending.pick(movies, emptyMap(), now).map { it.name })
    }

    @Test
    fun newAndWellRatedFirst_oldUnratedOrUndatedLeftOut() {
        val movies = groups(
            film("Old Gem", "Drama", 100, "9.0"),
            film("New Good", "Drama", 2, "7.0"),
            film("Too Old", "Drama", 200, "9.9"),
            film("No Rating", "Drama", 1, ""),
            film("No Date", "Drama", -1, "9.5"),
        )
        assertEquals(listOf("New Good", "Old Gem"), Trending.pick(movies, emptyMap(), now).map { it.name })
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
