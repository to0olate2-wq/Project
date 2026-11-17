package app.falak.player.data

import app.falak.player.BuildConfig
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Asks Wikidata and Wikipedia about people (see People). Off the main thread. Wikimedia asks every
 * app to say who it is: the User-Agent names the app and its page.
 *
 * Made quicker (8.30; the owner: "it took time to load"): answers are kept on the device for a week
 * ([dir]), so an actor seen before opens at once; the playlist's cleaned names are made once
 * ([names]); and [page] starts on the actor's films while it is still checking who they are.
 */
class PeopleClient(private val http: OkHttpClient, private val dir: File? = null) {
    private val agent = "ThePlayer/${BuildConfig.VERSION_NAME} (https://github.com/to0olate2-wq/Project; Android IPTV player)"

    private fun stored(url: String): File? = dir?.let { File(it, sha1(url)) }

    private fun get(url: String, accept: String = "application/json", keepMs: Long = KEEP_MS): String? {
        if (testing) return null
        val file = stored(url)
        if (file != null && file.isFile && System.currentTimeMillis() - file.lastModified() < keepMs) {
            runCatching { return file.readText() }
        }
        val text = try {
            http.newCall(Request.Builder().url(url).header("User-Agent", agent).header("Accept", accept).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        } catch (e: Exception) {
            null
        }
        if (text == null) {
            // Offline: an older answer is better than none.
            return file?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
        }
        if (file != null) {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.path + "." + Thread.currentThread().id + ".tmp")
                tmp.writeText(text)
                tmp.renameTo(file)
            }
            tidy()
        }
        return text
    }

    /** At most [MAX_FILES] answers are kept: the oldest go (checked now and then). */
    private var writes = 0

    private fun tidy() {
        if (++writes % 50 != 0) return
        val files = dir?.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    private fun sparql(query: String) = get(People.sparqlUrl(query), "application/sparql-results+json")

    private val candidates = java.util.concurrent.ConcurrentHashMap<String, List<People.Person>>()
    private val found = java.util.concurrent.ConcurrentHashMap<String, List<People.Person>>()
    private val abouts = java.util.concurrent.ConcurrentHashMap<String, People.About>()
    private val credits = java.util.concurrent.ConcurrentHashMap<String, Pair<List<People.Title>, List<People.Title>>>()

    private fun key(q: String) = People.language(q) + "|" + TitleMatch.norm(q)

    /** Everything Wikidata has with that name (people, places, films…). Null: no answer. */
    private fun candidates(q: String): List<People.Person>? {
        candidates[key(q)]?.let { return it }
        val all = People.parseSearch(get(People.searchUrl(q)) ?: return null)
        candidates[key(q)] = all
        return all
    }

    /** The actors and directors with that name, best first. Null: no answer (offline). */
    fun search(name: String): List<People.Person>? {
        val q = name.trim()
        if (q.length < 2) return emptyList()
        found[key(q)]?.let { return it }
        val all = candidates(q) ?: return null
        val people = if (all.isEmpty()) emptyList() else People.keepPeople(all, sparql(People.whoQuery(all.map { it.id })) ?: return null)
        found[key(q)] = people
        return people
    }

    /** The person a cast list names. */
    fun person(name: String): People.Person? = search(name)?.let { People.pick(it, name) }

    /** One request at a time for the same thing (the early start and the page may ask together). */
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private inline fun <T> once(key: String, block: () -> T): T = synchronized(locks.getOrPut(key) { Any() }) { block() }

    /** Born, died, photo and the start of their Wikipedia page. */
    fun about(id: String): People.About? = once("a|$id") {
        abouts[id]?.let { return@once it }
        var a = People.parseAbout(sparql(People.aboutQuery(id)) ?: return@once null) ?: return@once null
        People.summaryUrl(a.wiki)?.let { url -> get(url)?.let { a = a.copy(bio = People.parseSummary(it)) } }
        abouts[id] = a
        a
    }

    /** Their films and series. Null: no answer. */
    fun credits(id: String): Pair<List<People.Title>, List<People.Title>>? = once("c|$id") {
        credits[id]?.let { return@once it }
        val c = People.parseCredits(sparql(People.creditsQuery(id)) ?: return@once null)
        credits[id] = c
        c
    }

    /**
     * The one meant by [name], found the quick way: the films and the page of the likeliest one
     * (Wikidata's first answer with that very name) are asked for at the same time as the check that
     * they are an actor or director; if the check picks someone else, theirs are asked for after.
     * [onPerson] hears who it is as soon as that's known. Null: no answer; [NOT_FOUND]: nobody.
     */
    fun find(name: String, onPerson: (People.Person) -> Unit = {}): People.Person? {
        val q = name.trim()
        found[key(q)]?.let { list -> return People.pick(list, q)?.also(onPerson) ?: NOT_FOUND }
        val all = candidates(q) ?: return null
        val want = TitleMatch.norm(q)
        val likely = all.firstOrNull { TitleMatch.norm(it.name) == want }
        if (likely != null) {
            // (Asked meanwhile; kept for the page if it's them.)
            Thread { credits(likely.id) }.start()
            Thread { about(likely.id) }.start()
        }
        val people = search(q) ?: return null
        return People.pick(people, q)?.also(onPerson) ?: NOT_FOUND
    }

    // ---- popular now, and "because you watched" (8.34) ----

    private val similars = java.util.concurrent.ConcurrentHashMap<String, List<People.Title>>()

    /** Cinemeta's popular films or series ([type] "movie" / "series"), kept a day. Null: no answer. */
    fun popular(type: String): List<People.Title>? = once("p|$type") {
        val out = ArrayList<People.Title>()
        for (skip in listOf(0, 100, 200)) {
            val url = "https://v3-cinemeta.strem.io/catalog/$type/top" + (if (skip > 0) "/skip=$skip" else "") + ".json"
            val text = get(url, keepMs = 24 * 3_600_000L) ?: break
            val page = People.parseCatalog(text, type)
            if (page.isEmpty()) break
            out += page
        }
        out.distinctBy { it.imdb }.ifEmpty { null }
    }

    /** Films and series most like [imdb] (Wikidata). Null: no answer. */
    fun similar(imdb: String): List<People.Title>? = once("s|$imdb") {
        similars[imdb]?.let { return@once it }
        val (src, people) = People.parseSimilarPeople(sparql(People.similarPeopleQuery(imdb)) ?: return@once null) ?: return@once emptyList()
        if (people.isEmpty()) return@once emptyList()
        val scores = People.parseScores(sparql(People.similarQuery(src, people)) ?: return@once null)
        if (scores.isEmpty()) return@once emptyList()
        val list = People.similarTitles(scores, sparql(People.titlesQuery(scores.map { it.first })) ?: return@once null)
        similars[imdb] = list
        list
    }

    // ---- the playlist's names ----

    @Volatile private var namesFor: Any? = null
    @Volatile private var namesMade: People.Names? = null

    /**
     * The cleaned names of the playlist's films and series, made once for [index] (the library's
     * index object: a new one when the playlist changes).
     */
    @Synchronized
    fun names(index: Any, library: () -> List<Channel>): People.Names {
        val made = namesMade
        if (made != null && namesFor === index) return made
        return People.Names(library()).also {
            namesFor = index
            namesMade = it
        }
    }

    /**
     * A film's or series' page is open (8.30): its first actors are looked up now, quietly, so
     * tapping one opens their page at once; and the playlist's names are made.
     */
    fun warm(cast: List<String>, index: Any, library: () -> List<Channel>) {
        if (testing || cast.isEmpty()) return
        Thread {
            runCatching { names(index, library) }
            for (n in cast.take(3)) {
                val p = runCatching { find(n) }.getOrNull() ?: continue
                if (p === NOT_FOUND) continue
                runCatching { credits(p.id) }
            }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    companion object {
        /** Screen tests: nothing is asked (their pictures must not depend on the internet). */
        @Volatile var testing = android.os.Build.FINGERPRINT == "robolectric"

        val NOT_FOUND = People.Person("", "")

        const val KEEP_MS = 7L * 24 * 3600 * 1000
        const val MAX_FILES = 600

        private fun sha1(s: String): String =
            java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
