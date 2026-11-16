package app.falak.player.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Actors and directors (8.29; the owner: tap an actor "to see what shows they have", and find them by
 * name). From Wikidata, the free database behind Wikipedia: no key, nothing to set up (Trakt, the
 * first plan, now lets only 16 people use an app without its review). What is asked and how the
 * answers are read; PeopleClient asks.
 *
 * - Find: wikidata.org's search (wbsearchentities), then one query keeping the people who played in or
 *   directed something (and their photos).
 * - A person: born, died, where, photo (Wikimedia Commons), and the start of their Wikipedia page.
 * - Their films and series: what lists them as cast (P161) or director (P57) and has an IMDb number;
 *   posters from Stremio's image service (images.metahub.space), as add-ons show them.
 */
object People {
    data class Person(val name: String, val id: String, val photo: String = "")

    /** A film or series of theirs. [type] "movie" or "series"; [altName] its Arabic name, if any. */
    data class Title(
        val type: String,
        val name: String,
        val year: Int?,
        val imdb: String,
        val poster: String,
        val altName: String = "",
        val character: String = "",
    )

    data class About(
        val name: String,
        /** Wikidata's line about them ("Canadian actor (born 1964)"). */
        val line: String,
        val born: String,
        val died: String,
        val place: String,
        val photo: String,
        /** Their Wikipedia page (English, else Arabic): the start of it is their story. */
        val wiki: String,
        val bio: String = "",
    )

    const val WIKIDATA = "https://www.wikidata.org/w/api.php"
    const val SPARQL = "https://query.wikidata.org/sparql"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parse(text: String): JsonElement? = try {
        json.parseToJsonElement(text)
    } catch (e: Exception) {
        null
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** Arabic letters in it: asked for in Arabic. */
    fun language(name: String) = if (name.any { it in '؀'..'ۿ' }) "ar" else "en"

    private val QID = Regex("Q\\d+")

    // ---- finding someone ----

    fun searchUrl(name: String): String {
        val lang = language(name)
        return "$WIKIDATA?action=wbsearchentities&format=json&type=item&limit=15&language=$lang&uselang=$lang&search=" + enc(name.trim())
    }

    /** wbsearchentities: everything with that name (people, places, films…), Wikidata's best first. */
    fun parseSearch(text: String): List<Person> =
        ((parse(text) as? JsonObject)?.get("search") as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id").takeIf { QID.matches(it) } ?: return@mapNotNull null
            val name = o.str("label").ifBlank { (o["match"] as? JsonObject)?.str("text").orEmpty() }.ifBlank { return@mapNotNull null }
            Person(name, id)
        }.distinctBy { it.id }

    /** Of [ids], the people who played in or directed something, with a photo where there is one. */
    fun whoQuery(ids: List<String>): String = """
        SELECT ?p (SAMPLE(?img) AS ?image) WHERE {
          VALUES ?p { ${ids.filter { QID.matches(it) }.joinToString(" ") { "wd:$it" }} }
          ?p wdt:P31 wd:Q5 .
          FILTER EXISTS { ?w wdt:P161|wdt:P57 ?p . }
          OPTIONAL { ?p wdt:P18 ?img . }
        } GROUP BY ?p
    """.trimIndent()

    /** The people of [found] that [whoQuery] kept, in the search's order, with their photos. */
    fun keepPeople(found: List<Person>, text: String): List<Person> {
        val rows = rows(text)
        val photos = HashMap<String, String>()
        for (r in rows) {
            val id = entity(r["p"]) ?: continue
            photos[id] = photo(value(r["image"]))
        }
        return found.filter { it.id in photos }.map { it.copy(photo = photos[it.id].orEmpty()) }
    }

    /** The one a cast list means by [name]: the same name first, else the search's first. */
    fun pick(found: List<Person>, name: String): Person? {
        val want = TitleMatch.norm(name)
        return found.firstOrNull { TitleMatch.norm(it.name) == want } ?: found.firstOrNull()
    }

    // ---- a person ----

    fun aboutQuery(id: String): String = """
        SELECT ?pLabel ?pDescription ?born ?died ?placeLabel ?image ?enwiki ?arwiki WHERE {
          BIND(wd:$id AS ?p)
          OPTIONAL { ?p wdt:P569 ?born . }
          OPTIONAL { ?p wdt:P570 ?died . }
          OPTIONAL { ?p wdt:P19 ?place . }
          OPTIONAL { ?p wdt:P18 ?image . }
          OPTIONAL { ?enwiki schema:about ?p ; schema:isPartOf <https://en.wikipedia.org/> . }
          OPTIONAL { ?arwiki schema:about ?p ; schema:isPartOf <https://ar.wikipedia.org/> . }
          SERVICE wikibase:label { bd:serviceParam wikibase:language "en,mul,ar". }
        } LIMIT 1
    """.trimIndent()

    fun parseAbout(text: String): About? {
        val r = rows(text).firstOrNull() ?: return null
        val name = value(r["pLabel"]).ifBlank { return null }
        return About(
            name = name,
            line = value(r["pDescription"]).replaceFirstChar { it.uppercase() },
            born = value(r["born"]).take(10).takeIf { Regex("\\d{4}-\\d\\d-\\d\\d").matches(it) }.orEmpty(),
            died = value(r["died"]).take(10).takeIf { Regex("\\d{4}-\\d\\d-\\d\\d").matches(it) }.orEmpty(),
            place = value(r["placeLabel"]).takeUnless { QID.matches(it) }.orEmpty(),
            photo = photo(value(r["image"])),
            wiki = value(r["enwiki"]).ifBlank { value(r["arwiki"]) },
        )
    }

    /** "https://en.wikipedia.org/wiki/Keanu_Reeves" → its summary's address. */
    fun summaryUrl(wiki: String): String? {
        val m = Regex("https?://([a-z]+)\\.wikipedia\\.org/wiki/(.+)").matchEntire(wiki) ?: return null
        return "https://${m.groupValues[1]}.wikipedia.org/api/rest_v1/page/summary/${m.groupValues[2]}"
    }

    /** Wikipedia's summary: its first lines. */
    fun parseSummary(text: String): String = ((parse(text) as? JsonObject)?.str("extract")).orEmpty().trim()

    // ---- their films and series ----

    /** Kinds of Wikidata item that are series (the rest are films; episodes and seasons are left out). */
    // (television series, miniseries, web series, anime series, animated series ×2, television programme)
    private val SERIES = setOf("Q5398426", "Q1259759", "Q526877", "Q63952888", "Q117467246", "Q581714", "Q15416")
    // (television episode, television season, film series, series of works, album, single, track)
    private val NOT_A_TITLE = setOf("Q21191270", "Q3464665", "Q24856", "Q7725310", "Q482994", "Q134556", "Q7302866")

    fun creditsQuery(id: String): String = """
        SELECT ?w (SAMPLE(?imdbId) AS ?imdb) (SAMPLE(?enName) AS ?en) (SAMPLE(?mulName) AS ?mul) (SAMPLE(?arName) AS ?ar) (MIN(?d) AS ?date)
               (GROUP_CONCAT(DISTINCT STRAFTER(STR(?kind), "entity/"); separator=",") AS ?kinds) (SAMPLE(?eps) AS ?episodes) WHERE {
          { ?w wdt:P161 wd:$id . } UNION { ?w wdt:P57 wd:$id . }
          ?w wdt:P345 ?imdbId . FILTER(STRSTARTS(?imdbId, "tt"))
          OPTIONAL { ?w wdt:P31 ?kind . }
          OPTIONAL { ?w wdt:P577|wdt:P580 ?d . }
          OPTIONAL { ?w wdt:P1113|wdt:P2437 ?eps . }
          OPTIONAL { ?w rdfs:label ?enName . FILTER(LANG(?enName) = "en") }
          OPTIONAL { ?w rdfs:label ?mulName . FILTER(LANG(?mulName) = "mul") }
          OPTIONAL { ?w rdfs:label ?arName . FILTER(LANG(?arName) = "ar") }
        } GROUP BY ?w
    """.trimIndent()

    /** Their films and series, each newest first; ones with no date last. */
    fun parseCredits(text: String): Pair<List<Title>, List<Title>> {
        val all = rows(text).mapNotNull { r ->
            val imdb = value(r["imdb"]).takeIf { Regex("tt\\d{5,}").matches(it) } ?: return@mapNotNull null
            val kinds = value(r["kinds"]).split(',').filter { it.isNotBlank() }.toSet()
            if (kinds.any { it in NOT_A_TITLE } && kinds.none { it in SERIES }) return@mapNotNull null
            val series = kinds.any { it in SERIES } || value(r["episodes"]).isNotEmpty()
            // (Since 2024 many titles keep their name as "mul", the same in every language, and no English one.)
            val en = value(r["en"]).ifBlank { value(r["mul"]) }
            val ar = value(r["ar"])
            val name = en.ifBlank { ar }.ifBlank { return@mapNotNull null }
            Title(
                type = if (series) "series" else "movie",
                name = name,
                year = value(r["date"]).take(4).toIntOrNull(),
                imdb = imdb,
                poster = poster(imdb),
                altName = if (en.isNotBlank() && ar.isNotBlank()) ar else "",
            )
        }.distinctBy { it.imdb }.sortedWith(compareByDescending<Title> { it.year ?: 0 }.thenBy { it.name })
        return all.filter { it.type == "movie" } to all.filter { it.type == "series" }
    }

    fun poster(imdb: String) = "https://images.metahub.space/poster/small/$imdb/img"

    fun sparqlUrl(query: String) = "$SPARQL?format=json&query=" + enc(query)

    // ---- SPARQL answers ----

    private fun rows(text: String): List<JsonObject> =
        (((parse(text) as? JsonObject)?.get("results") as? JsonObject)?.get("bindings") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun value(e: JsonElement?): String = ((e as? JsonObject)?.str("value")).orEmpty().trim()

    private fun entity(e: JsonElement?): String? = value(e).substringAfterLast('/').takeIf { QID.matches(it) }

    /** A Commons file ("http://commons.wikimedia.org/wiki/Special:FilePath/Reeves%202019.jpg") at a small size. */
    fun photo(file: String): String {
        if (file.isBlank()) return ""
        val https = file.replaceFirst("http://", "https://")
        return if ("Special:FilePath/" in https && "width=" !in https) "$https?width=360" else https
    }

    // ---- your playlist ----

    /**
     * The playlist's films and series by their cleaned names ("EN - The Matrix (1999) 4K" → "the matrix"),
     * made once for a playlist (cleaning 60,000 names takes a second or more on a phone) and kept
     * while it doesn't change (PeopleClient.names).
     */
    class Names(library: List<Channel>) {
        internal val byName = HashMap<String, ArrayList<Pair<Channel, Int?>>>()

        init {
            val year = TitleMatch.currentYear()
            for (ch in library) {
                if (ch.kind != Kind.MOVIE && ch.kind != Kind.SERIES) continue
                val (name, y) = TitleMatch.clean(ch.name, year)
                val key = TitleMatch.norm(name)
                if (key.isNotEmpty()) byName.getOrPut(key) { ArrayList(1) } += ch to y
            }
        }
    }

    /**
     * Which of [titles] the playlist has: each matched to a film or series by its name once cleaned or
     * its Arabic name, and, where both are known, the year (±1).
     */
    fun inLibrary(titles: List<Title>, names: Names): List<Pair<Title, Channel>> {
        if (titles.isEmpty() || names.byName.isEmpty()) return emptyList()
        val out = ArrayList<Pair<Title, Channel>>()
        val used = HashSet<String>()
        for (t in titles) {
            val wantKind = if (t.type == "series") Kind.SERIES else Kind.MOVIE
            val candidates = listOf(t.name, t.altName).filter { it.isNotBlank() }
                .flatMap { names.byName[TitleMatch.norm(it)].orEmpty() }
                .filter { it.first.kind == wantKind && it.first.id !in used }
            val hit = candidates.firstOrNull { (_, y) -> y != null && t.year != null && kotlin.math.abs(y - t.year) <= 1 }
                ?: candidates.firstOrNull { (_, y) -> y == null }
                ?: continue
            used += hit.first.id
            out += t to hit.first
        }
        return out
    }

    fun inLibrary(titles: List<Title>, library: List<Channel>): List<Pair<Title, Channel>> =
        if (titles.isEmpty() || library.isEmpty()) emptyList() else inLibrary(titles, Names(library))

    /** "Keanu Reeves, Laurence Fishburne ,Carrie-Anne Moss" → the names (a provider's cast line). */
    fun castNames(line: String, max: Int = 12): List<String> =
        line.split(',', ';', '،', '/', '|').map { it.trim().trim('.') }.filter { it.length in 2..60 }.distinct().take(max)
}
