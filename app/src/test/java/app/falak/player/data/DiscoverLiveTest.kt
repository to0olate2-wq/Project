package app.falak.player.data

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Asks the real Cinemeta and Wikidata (8.34), only in a "[only …]" run (LIVE_CHECKS): what is
 * popular now, and what is most like The Matrix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class DiscoverLiveTest {
    private fun client(): PeopleClient {
        assumeTrue("Only when asked for", System.getenv("LIVE_CHECKS") != null)
        PeopleClient.testing = false
        // (The app's client waits up to a minute for an answer, like this one.)
        return PeopleClient(okhttp3.OkHttpClient.Builder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build())
    }

    @Test
    fun popularNowAndMostAlike() {
        val c = client()
        try {
            val t0 = System.currentTimeMillis()
            val films = c.popular("movie")!!
            val series = c.popular("series")!!
            println("Popular films ${films.size}: ${films.take(10).map { "${it.name} ${it.year}" }} (${System.currentTimeMillis() - t0} ms)")
            println("Popular series ${series.size}: ${series.take(10).map { "${it.name} ${it.year}" }}")
            assertTrue(films.size >= 100)
            assertTrue(series.size >= 100)
            val t1 = System.currentTimeMillis()
            val alike = c.similar("tt0133093")!!
            println("Like The Matrix ${alike.size} (${System.currentTimeMillis() - t1} ms): ${alike.take(15).map { "${it.name} ${it.year} ${it.type}" }}")
            assertTrue(alike.any { it.imdb == "tt0234215" }) // The Matrix Reloaded
            assertTrue("too slow: ${System.currentTimeMillis() - t1} ms", System.currentTimeMillis() - t1 < 15_000)
            // A second film, and a series.
            val t3 = System.currentTimeMillis()
            val heat = c.similar("tt0113277")!! // Heat (1995)
            println("Like Heat ${heat.size} (${System.currentTimeMillis() - t3} ms): ${heat.take(10).map { "${it.name} ${it.year}" }}")
            val t4 = System.currentTimeMillis()
            val bb = c.similar("tt0903747")!! // Breaking Bad
            println("Like Breaking Bad ${bb.size} (${System.currentTimeMillis() - t4} ms): ${bb.take(10).map { "${it.name} ${it.year} ${it.type}" }}")
            // A playlist that has some of them finds them by name.
            val library = listOf(
                Channel(id = "m1", name = "EN - The Matrix Reloaded (2003)", group = "Films", url = "u1", kind = Kind.MOVIE),
                Channel(id = "m2", name = "John Wick (2014) 4K", group = "Films", url = "u2", kind = Kind.MOVIE),
                Channel(id = "m3", name = "Paper Kites", group = "Films", url = "u3", kind = Kind.MOVIE),
            )
            val inList = People.inLibrary(alike, library).map { it.second.id }
            println("In the playlist: $inList")
            assertTrue("m1" in inList)
            assertTrue("m3" !in inList)
        } finally {
            PeopleClient.testing = true
        }
    }
}
