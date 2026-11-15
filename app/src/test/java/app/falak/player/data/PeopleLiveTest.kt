package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Asks the real Wikidata and Wikipedia (8.29), only in a "[only …]" run (LIVE_CHECKS): that actors
 * are found by name (English and Arabic), and their page, films, series and posters read as expected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class PeopleLiveTest {
    private fun client(): PeopleClient {
        assumeTrue("Only when asked for", System.getenv("LIVE_CHECKS") != null)
        PeopleClient.testing = false
        return PeopleClient(okhttp3.OkHttpClient())
    }

    @Test
    fun anActorTheirPageAndTitles() {
        val c = client()
        try {
            val found = c.search("Keanu Reeves")
            println("Search 'Keanu Reeves': $found")
            val keanu = People.pick(found!!, "Keanu Reeves")!!
            assertEquals("Q43416", keanu.id)
            assertTrue("no photo", keanu.photo.startsWith("https://commons.wikimedia.org/"))
            val about = c.about(keanu.id)!!
            println("About: $about")
            assertEquals("1964-09-02", about.born)
            assertTrue("no story", about.bio.length > 40)
            val (films, series) = c.credits(keanu.id)!!
            println("Films ${films.size}: ${films.take(8).map { "${it.name} ${it.year} ${it.imdb}" }}")
            println("Series ${series.size}: ${series.take(8).map { "${it.name} ${it.year} ${it.imdb}" }}")
            assertTrue(films.any { it.imdb == "tt0133093" && it.year == 1999 })
            assertTrue(films.size > 30)
            assertTrue(series.none { it.imdb == "tt0133093" })
            // The poster service answers for a title's IMDb number.
            val poster = okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(People.poster("tt0133093")).build()).execute()
            println("Poster: HTTP ${poster.code} ${poster.header("Content-Type")}")
            poster.close()
            assertTrue(poster.isSuccessful)
            // The photo link opens (Commons sends it on to the file).
            val photo = okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(keanu.photo).header("User-Agent", "ThePlayer-test").build()).execute()
            println("Photo: HTTP ${photo.code} ${photo.request.url.host} ${photo.header("Content-Type")}")
            photo.close()
            assertTrue(photo.isSuccessful)
        } finally {
            PeopleClient.testing = true
        }
    }

    @Test
    fun arabicNamesAndADirector() {
        val c = client()
        try {
            val adel = c.search("عادل إمام")
            println("Search 'عادل إمام': $adel")
            assertNotNull(adel?.firstOrNull())
            val page = c.credits(adel!!.first().id)!!
            println("Adel Emam: ${page.first.size} films, e.g. ${page.first.take(5).map { "${it.name} / ${it.altName} ${it.year}" }}; ${page.second.size} series")
            assertTrue(page.first.isNotEmpty())
            val nolan = c.person("Christopher Nolan")!!
            val films = c.credits(nolan.id)!!.first
            println("Nolan: ${films.take(5).map { it.name }}")
            assertTrue(films.any { it.imdb == "tt1375666" })
            // English names, not Arabic ones, where Wikidata has them (also as "mul").
            assertTrue(films.any { it.name == "Oppenheimer" })
            // Typed in part, as in the search box.
            println("Search 'tom han': ${c.search("tom han")?.take(5)}")
        } finally {
            PeopleClient.testing = true
        }
    }
}
