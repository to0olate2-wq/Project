package app.falak.player.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Trakt (8.29; the owner picked it): what is sent to api.trakt.tv and read back, without the network
 * (TraktClient asks). Only with the app's own Trakt app (BuildConfig.TRAKT_ID, made by the owner at
 * developer.trakt.tv): ratings, and a person's own account (connected with a code on
 * trakt.tv/activate) to mark what they watch and for their watchlist. Since October 2026 an app made
 * there has no client secret, and until Trakt reviews it ("Production access") only its owner and 16
 * people added to it can connect. Actors come from Wikidata instead (People).
 */
object Trakt {
    const val API = "https://api.trakt.tv"
    /** Sign-in calls (/oauth/…): Trakt's own host for them (required from February 2027). */
    const val AUTH = "https://auth.trakt.tv"
    /** The redirect the owner gives the app (a development app takes only a local one). */
    const val REDIRECT = "http://localhost"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** A film or series: of a person's, or on a watchlist. [type] "movie" or "series". */
    data class Title(
        val type: String,
        val name: String,
        val year: Int?,
        val imdb: String,
        val poster: String,
        val character: String = "",
    )

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

    private fun title(o: JsonObject, type: String): Title? {
        val name = o.str("title").ifBlank { return null }
        val imdb = o.obj("ids")?.str("imdb").orEmpty()
        if (!Regex("tt\\d{5,}").matches(imdb)) return null
        return Title(type, name, o.int("year"), imdb, image(o.obj("images")?.get("poster")))
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

    /** (A secret only for an app made before October 2026 that has one.) */
    private fun secretPart(secret: String) = if (secret.isEmpty()) "" else ""","client_secret":${q(secret)}"""

    fun deviceTokenBody(code: String, clientId: String, secret: String = "") =
        """{"code":${q(code)},"client_id":${q(clientId)}${secretPart(secret)}}"""

    fun refreshBody(refresh: String, clientId: String, secret: String = "") =
        """{"refresh_token":${q(refresh)},"client_id":${q(clientId)}${secretPart(secret)},"redirect_uri":${q(REDIRECT)},"grant_type":"refresh_token"}"""

    fun scoreUrl(imdb: String, type: String) = "$API/" + (if (type == "series") "shows" else "movies") + "/${enc(imdb)}/ratings"

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
