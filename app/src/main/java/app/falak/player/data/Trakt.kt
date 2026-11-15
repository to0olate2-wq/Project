package app.falak.player.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Trakt (8.29; the owner picked it, with actors' pages and finding actors by name): what is sent
 * to api.trakt.tv and read back, without the network (TraktClient asks). The app's own Trakt app
 * (BuildConfig.TRAKT_ID) is enough for people, their films and series, and ratings; a person's own
 * account (connected with a code on trakt.tv/activate) is needed to mark what they watch and for
 * their watchlist.
 */
object Trakt {
    const val API = "https://api.trakt.tv"
    const val REDIRECT = "urn:ietf:wg:oauth:2.0:oob"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Person(val name: String, val slug: String, val imdb: String, val photo: String)

    /** A film or series: of a person's, or on a watchlist. [type] "movie" or "series". */
    data class Title(
        val type: String,
        val name: String,
        val year: Int?,
        val imdb: String,
        val poster: String,
        val character: String = "",
    )

    data class About(val bio: String, val born: String, val died: String, val place: String, val photo: String, val job: String)

    data class Score(val rating: Double, val votes: Int) {
        /** "87%" as Trakt shows it. */
        val percent: Int get() = Math.round(rating * 10).toInt()
    }

    data class DeviceCode(val deviceCode: String, val userCode: String, val url: String, val expiresIn: Int, val interval: Int)

    data class Tokens(val access: String, val refresh: String, val until: Long)

    // ---- reading ----

    private fun parse(text: String): JsonElement? = try {
        json.parseToJsonElement(text)
    } catch (e: Exception) {
        null
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()

    /**
     * An image of Trakt's: a list of addresses ("media.trakt.tv/images/…webp", without https),
     * an object of sizes, or one address. The first, as a full link; "" when there is none.
     */
    fun image(e: JsonElement?): String {
        val raw = when (e) {
            is JsonArray -> e.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
            is JsonObject -> listOf("medium", "thumb", "full").firstNotNullOfOrNull { k -> image(e[k]).takeIf { it.isNotEmpty() } }
            is JsonPrimitive -> e.contentOrNull
            else -> null
        }?.trim().orEmpty()
        return when {
            raw.isEmpty() -> ""
            raw.startsWith("http://") || raw.startsWith("https://") -> raw.replaceFirst("http://", "https://")
            raw.startsWith("//") -> "https:$raw"
            else -> "https://$raw"
        }
    }

    private fun person(o: JsonObject): Person? {
        val name = o.str("name").ifBlank { return null }
        val ids = o.obj("ids")
        val slug = ids?.str("slug").orEmpty().ifBlank { ids?.str("trakt").orEmpty() }.ifBlank { return null }
        return Person(name, slug, ids?.str("imdb").orEmpty(), image(o.obj("images")?.get("headshot")))
    }

    /** /search/person: the people found, Trakt's best first. */
    fun parsePeople(text: String): List<Person> =
        (parse(text) as? JsonArray).orEmpty().mapNotNull { e -> (e as? JsonObject)?.obj("person")?.let { person(it) } }
            .distinctBy { it.slug }

    /** The one meant by [name] (from a film's cast): the same name first, else Trakt's first answer. */
    fun pickPerson(found: List<Person>, name: String): Person? {
        val want = TitleMatch.norm(name)
        return found.firstOrNull { TitleMatch.norm(it.name) == want } ?: found.firstOrNull()
    }

    private fun title(o: JsonObject, type: String): Title? {
        val name = o.str("title").ifBlank { return null }
        val imdb = o.obj("ids")?.str("imdb").orEmpty()
        if (!Regex("tt\\d{5,}").matches(imdb)) return null
        return Title(type, name, o.int("year"), imdb, image(o.obj("images")?.get("poster")))
    }

    /**
     * /people/{slug}/movies or /shows: the parts they played ("cast"; and, for someone who directs,
     * "crew" when they played in nothing), newest first; ones not out yet last.
     */
    fun parseCredits(text: String, type: String): List<Title> {
        val o = parse(text) as? JsonObject ?: return emptyList()
        val key = if (type == "series") "show" else "movie"
        fun read(list: JsonArray?) = list.orEmpty().mapNotNull { e ->
            val c = e as? JsonObject ?: return@mapNotNull null
            val t = c.obj(key)?.let { title(it, type) } ?: return@mapNotNull null
            val part = (c["characters"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: c.str("character").ifBlank { c.str("job") }
            t.copy(character = part.orEmpty())
        }
        var found = read(o["cast"] as? JsonArray)
        if (found.isEmpty()) {
            val crew = o.obj("crew")
            found = crew?.values?.flatMap { read(it as? JsonArray) }.orEmpty()
        }
        return found.distinctBy { it.imdb }.sortedByDescending { it.year ?: 0 }
    }

    /** /people/{slug}?extended=full,images. */
    fun parseAbout(text: String): About? {
        val o = parse(text) as? JsonObject ?: return null
        if (o.str("name").isBlank()) return null
        return About(
            bio = o.str("biography").trim(),
            born = o.str("birthday"),
            died = o.str("death"),
            place = o.str("birthplace"),
            photo = image(o.obj("images")?.get("headshot")),
            job = o.str("known_for_department"),
        )
    }

    /** /movies/{id}/ratings or /shows/{id}/ratings. */
    fun parseScore(text: String): Score? {
        val o = parse(text) as? JsonObject ?: return null
        val r = (o["rating"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: return null
        val votes = o.int("votes") ?: 0
        return if (votes > 0 && r > 0) Score(r, votes) else null
    }

    /** /sync/watchlist: films and series, the newest added first (as Trakt lists them). */
    fun parseWatchlist(text: String): List<Title> =
        (parse(text) as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            when (o.str("type")) {
                "movie" -> o.obj("movie")?.let { title(it, "movie") }
                "show" -> o.obj("show")?.let { title(it, "series") }
                else -> null
            }
        }.distinctBy { it.imdb }

    fun parseDeviceCode(text: String): DeviceCode? {
        val o = parse(text) as? JsonObject ?: return null
        val code = o.str("device_code").ifBlank { return null }
        return DeviceCode(code, o.str("user_code"), o.str("verification_url").ifBlank { "https://trakt.tv/activate" }, o.int("expires_in") ?: 600, (o.int("interval") ?: 5).coerceAtLeast(1))
    }

    fun parseTokens(text: String, now: Long): Tokens? {
        val o = parse(text) as? JsonObject ?: return null
        val access = o.str("access_token").ifBlank { return null }
        // (Renewed a day before it runs out.)
        val life = (o.int("expires_in") ?: 7_776_000).toLong() * 1000
        return Tokens(access, o.str("refresh_token"), now + life - 86_400_000L)
    }

    /** /users/settings: the account's name. */
    fun parseUser(text: String): String {
        val o = parse(text) as? JsonObject ?: return ""
        val u = o.obj("user") ?: return ""
        return u.str("name").ifBlank { u.str("username") }
    }

    // ---- sending ----

    private fun q(s: String) = JsonPrimitive(s).toString()

    /**
     * What a scrobble says is playing: [video] "tt0133093" (a film) or "tt0903747:1:2" (an episode),
     * [progress] in percent. Null for anything else.
     */
    fun scrobbleBody(video: String, progress: Double): String? {
        val parts = video.split(':')
        val imdb = parts[0]
        if (!Regex("tt\\d{5,}").matches(imdb)) return null
        val p = String.format(java.util.Locale.US, "%.2f", progress.coerceIn(0.0, 100.0))
        return when (parts.size) {
            1 -> """{"movie":{"ids":{"imdb":${q(imdb)}}},"progress":$p}"""
            3 -> {
                val s = parts[1].toIntOrNull() ?: return null
                val e = parts[2].toIntOrNull() ?: return null
                """{"show":{"ids":{"imdb":${q(imdb)}}},"episode":{"season":$s,"number":$e},"progress":$p}"""
            }
            else -> null
        }
    }

    /** For /sync/watchlist (and …/remove): one film or series. */
    fun watchlistBody(imdb: String, type: String): String {
        val key = if (type == "series") "shows" else "movies"
        return """{"$key":[{"ids":{"imdb":${q(imdb)}}}]}"""
    }

    fun deviceCodeBody(clientId: String) = """{"client_id":${q(clientId)}}"""

    fun deviceTokenBody(code: String, clientId: String, secret: String) =
        """{"code":${q(code)},"client_id":${q(clientId)},"client_secret":${q(secret)}}"""

    fun refreshBody(refresh: String, clientId: String, secret: String) =
        """{"refresh_token":${q(refresh)},"client_id":${q(clientId)},"client_secret":${q(secret)},"redirect_uri":${q(REDIRECT)},"grant_type":"refresh_token"}"""

    fun searchPeopleUrl(name: String) =
        "$API/search/person?query=" + java.net.URLEncoder.encode(name.trim(), "UTF-8") + "&extended=images&limit=12"

    fun creditsUrl(slug: String, type: String) =
        "$API/people/${enc(slug)}/" + (if (type == "series") "shows" else "movies") + "?extended=images"

    fun aboutUrl(slug: String) = "$API/people/${enc(slug)}?extended=full,images"

    fun scoreUrl(imdb: String, type: String) = "$API/" + (if (type == "series") "shows" else "movies") + "/${enc(imdb)}/ratings"

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    // ---- your playlist ----

    /**
     * Which of [titles] the playlist has: each matched to a film or series of [library] by its name
     * once cleaned ("EN - The Matrix (1999) 4K") and, where both are known, the year (±1).
     */
    fun inLibrary(titles: List<Title>, library: List<Channel>): List<Pair<Title, Channel>> {
        if (titles.isEmpty() || library.isEmpty()) return emptyList()
        val byName = HashMap<String, ArrayList<Pair<Channel, Int?>>>()
        for (ch in library) {
            val (name, year) = TitleMatch.clean(ch.name)
            val key = TitleMatch.norm(name)
            if (key.isNotEmpty()) byName.getOrPut(key) { ArrayList() } += ch to year
        }
        val out = ArrayList<Pair<Title, Channel>>()
        val used = HashSet<String>()
        for (t in titles) {
            val wantKind = if (t.type == "series") Kind.SERIES else Kind.MOVIE
            val candidates = byName[TitleMatch.norm(t.name)].orEmpty().filter { it.first.kind == wantKind && it.first.id !in used }
            val hit = candidates.firstOrNull { (_, y) -> y != null && t.year != null && kotlin.math.abs(y - t.year) <= 1 }
                ?: candidates.firstOrNull { (_, y) -> y == null }
                ?: continue
            used += hit.first.id
            out += t to hit.first
        }
        return out
    }

    /** "Keanu Reeves, Laurence Fishburne ,Carrie-Anne Moss" → the names (a provider's cast line). */
    fun castNames(line: String, max: Int = 12): List<String> =
        line.split(',', ';', '،', '/', '|').map { it.trim().trim('.') }.filter { it.length in 2..60 }.distinct().take(max)
}
