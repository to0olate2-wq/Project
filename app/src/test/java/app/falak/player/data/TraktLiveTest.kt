package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Asks the real api.trakt.tv with the app's Trakt key (8.29), only in a "[only …]" run (LIVE_CHECKS)
 * and when the key is a repository secret (TRAKT_LIVE_ID): that the answers read as Trakt expects.
 */
class TraktLiveTest {
    private val id = System.getenv("TRAKT_LIVE_ID").orEmpty()

    private fun client(): TraktClient {
        assumeTrue("LIVE_CHECKS" in System.getenv() && id.isNotBlank())
        return TraktClient(okhttp3.OkHttpClient(), { Settings() }, {}, clientId = id, secret = "")
    }

    @Test
    fun anActorTheirFilmsAndRatings() {
        val t = client()
        val keanu = t.person("Keanu Reeves")
        assertNotNull("No answer from Trakt (is the key right?)", keanu)
        assertEquals("Keanu Reeves", keanu!!.name)
        println("Trakt person: $keanu")
        val films = t.credits(keanu.slug, "movie")!!
        println("Films: ${films.size}, e.g. ${films.take(3)}")
        assertTrue(films.any { it.imdb == "tt0133093" })
        val series = t.credits(keanu.slug, "series")
        println("Series: ${series?.size}")
        val about = t.about(keanu.slug)
        println("About: born ${about?.born} in ${about?.place}, photo ${about?.photo}, bio ${about?.bio?.take(60)}")
        val score = t.score("tt0133093", "movie")
        println("The Matrix on Trakt: $score")
        assertTrue((score?.percent ?: 0) in 60..100)
        assertNotNull(t.score("tt0903747", "series"))
        // The search by name, as typed.
        val found = t.searchPeople("adel imam").orEmpty()
        println("Search 'adel imam': ${found.take(3).map { it.name }}")
    }
}
