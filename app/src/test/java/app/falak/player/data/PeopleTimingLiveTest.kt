package app.falak.player.data

import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How long each step of an actor's page takes against the real Wikidata and Wikipedia (only in an
 * "[only …]" run, LIVE_CHECKS): to see which step makes the page slow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class PeopleTimingLiveTest {
    private val http = okhttp3.OkHttpClient()
    private val agent = "ThePlayer/8.29 (https://github.com/to0olate2-wq/Project; Android IPTV player)"

    private fun timed(label: String, url: String, accept: String = "application/json"): String? {
        val t0 = System.nanoTime()
        val r = try {
            http.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", agent).header("Accept", accept).build()).execute().use { r ->
                val body = r.body?.string()
                println("TIMING $label: ${(System.nanoTime() - t0) / 1_000_000} ms, HTTP ${r.code}, ${body?.length ?: 0} bytes, cache ${r.header("x-cache-status") ?: r.header("x-cache") ?: "-"}")
                body
            }
        } catch (e: Exception) {
            println("TIMING $label: failed after ${(System.nanoTime() - t0) / 1_000_000} ms: $e")
            null
        }
        return r
    }

    private fun sparql(label: String, q: String) = timed(label, People.sparqlUrl(q), "application/sparql-results+json")

    @Test
    fun eachStep() {
        assumeTrue(System.getenv("LIVE_CHECKS") != null)
        for (name in listOf("Keanu Reeves", "Tom Hanks", "عادل إمام", "Samuel L. Jackson", "Keanu Reeves")) {
            println("---- $name")
            val t0 = System.nanoTime()
            val found = People.parseSearch(timed("1 search", People.searchUrl(name)) ?: continue)
            val people = People.keepPeople(found, sparql("2 who (filter people)", People.whoQuery(found.map { it.id })) ?: continue)
            val p = People.pick(people, name) ?: continue
            val about = People.parseAbout(sparql("3 about", People.aboutQuery(p.id)) ?: continue)
            People.summaryUrl(about?.wiki.orEmpty())?.let { timed("4 wikipedia summary", it) }
            val credits = sparql("5 credits", People.creditsQuery(p.id))
            println("TIMING all steps one after another: ${(System.nanoTime() - t0) / 1_000_000} ms (${credits?.let { People.parseCredits(it).first.size }} films)")
            // Faster ways, to compare:
            timed("alt: about by wbgetentities", "${People.WIKIDATA}?action=wbgetentities&format=json&ids=${p.id}&props=labels|descriptions|claims|sitelinks&languages=en|ar|mul&sitefilter=enwiki|arwiki")
            sparql("alt: credits, no names or kinds", """SELECT ?w ?imdb WHERE { { ?w wdt:P161 wd:${p.id} . } UNION { ?w wdt:P57 wd:${p.id} . } ?w wdt:P345 ?imdb . }""")
            sparql(
                "alt: credits, no GROUP BY",
                """SELECT ?w ?imdb ?d ?kind ?en ?mul ?ar WHERE { { ?w wdt:P161 wd:${p.id} . } UNION { ?w wdt:P57 wd:${p.id} . } ?w wdt:P345 ?imdb .
                   OPTIONAL { ?w wdt:P31 ?kind . } OPTIONAL { ?w wdt:P577 ?d . }
                   OPTIONAL { ?w rdfs:label ?en . FILTER(LANG(?en) = "en") } OPTIONAL { ?w rdfs:label ?mul . FILTER(LANG(?mul) = "mul") }
                   OPTIONAL { ?w rdfs:label ?ar . FILTER(LANG(?ar) = "ar") } }""",
            )
        }
    }

    /** 8.30: the app's own way (the films started early, answers kept on the device). */
    @Test
    fun theAppsWay() {
        assumeTrue(System.getenv("LIVE_CHECKS") != null)
        PeopleClient.testing = false
        val dir = java.nio.file.Files.createTempDirectory("people").toFile()
        try {
            for (round in listOf("first time", "app opened again (kept on the device)")) {
                val c = PeopleClient(http, dir)
                for (name in listOf("Denzel Washington", "يسرا", "Cate Blanchett")) {
                    val t0 = System.nanoTime()
                    val p = c.find(name)!!
                    val t1 = System.nanoTime()
                    val works = c.credits(p.id)!!
                    val t2 = System.nanoTime()
                    c.about(p.id)
                    val t3 = System.nanoTime()
                    println("APP $round, $name: who ${(t1 - t0) / 1_000_000} ms, films ${(t2 - t0) / 1_000_000} ms (${works.first.size}), story ${(t3 - t0) / 1_000_000} ms")
                }
            }
        } finally {
            PeopleClient.testing = true
            dir.deleteRecursively()
        }
    }
}
