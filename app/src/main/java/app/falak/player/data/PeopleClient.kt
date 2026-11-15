package app.falak.player.data

import app.falak.player.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Asks Wikidata and Wikipedia about people (see People). Off the main thread. Answers are kept for
 * the session. Wikimedia asks every app to say who it is: the User-Agent names the app and its page.
 */
class PeopleClient(private val http: OkHttpClient) {
    private val agent = "ThePlayer/${BuildConfig.VERSION_NAME} (https://github.com/to0olate2-wq/Project; Android IPTV player)"

    private fun get(url: String, accept: String = "application/json"): String? {
        if (testing) return null
        return try {
            http.newCall(Request.Builder().url(url).header("User-Agent", agent).header("Accept", accept).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun sparql(query: String) = get(People.sparqlUrl(query), "application/sparql-results+json")

    private val found = java.util.concurrent.ConcurrentHashMap<String, List<People.Person>>()
    private val abouts = java.util.concurrent.ConcurrentHashMap<String, People.About>()
    private val credits = java.util.concurrent.ConcurrentHashMap<String, Pair<List<People.Title>, List<People.Title>>>()

    /** The actors and directors with that name, best first. Null: no answer (offline). */
    fun search(name: String): List<People.Person>? {
        val q = name.trim()
        if (q.length < 2) return emptyList()
        val key = People.language(q) + "|" + TitleMatch.norm(q)
        found[key]?.let { return it }
        val all = People.parseSearch(get(People.searchUrl(q)) ?: return null)
        val people = if (all.isEmpty()) emptyList() else People.keepPeople(all, sparql(People.whoQuery(all.map { it.id })) ?: return null)
        found[key] = people
        return people
    }

    /** The person a cast list names. */
    fun person(name: String): People.Person? = search(name)?.let { People.pick(it, name) }

    /** Born, died, photo and the start of their Wikipedia page. */
    fun about(id: String): People.About? {
        abouts[id]?.let { return it }
        var a = People.parseAbout(sparql(People.aboutQuery(id)) ?: return null) ?: return null
        People.summaryUrl(a.wiki)?.let { url -> get(url)?.let { a = a.copy(bio = People.parseSummary(it)) } }
        abouts[id] = a
        return a
    }

    /** Their films and series. Null: no answer. */
    fun credits(id: String): Pair<List<People.Title>, List<People.Title>>? {
        credits[id]?.let { return it }
        val c = People.parseCredits(sparql(People.creditsQuery(id)) ?: return null)
        credits[id] = c
        return c
    }

    companion object {
        /** Screen tests: nothing is asked (their pictures must not depend on the internet). */
        @Volatile var testing = android.os.Build.FINGERPRINT == "robolectric"
    }
}
