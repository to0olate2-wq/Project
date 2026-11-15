package app.falak.player.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The IMDb number of a film or series from your provider (8.27), so its intro and credits can be
 * looked up like an add-on title's (see SkipSegments): providers send no IMDb number, and
 * introdb.app takes nothing else (a TMDB number is refused, checked on its real API). The title's
 * name and year are searched on Cinemeta (Stremio's free list of films and series, no key), and
 * a result is used only when its name is the same, letter for letter once tidied, and its year
 * matches; with no year, only when one title has that name. Anything less sure: no number, no
 * button (a wrong title would put a button at the wrong time).
 */
object TitleMatch {
    data class Found(val id: String, val name: String, val year: Int?)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun searchUrl(type: String, name: String): String =
        "https://v3-cinemeta.strem.io/catalog/$type/top/search=" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20") + ".json"

    /** Cinemeta's answer: {"metas":[{"id":"tt0133093","name":"The Matrix","releaseInfo":"1999"},…]}. */
    fun parse(text: String): List<Found> {
        val o = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        return (o["metas"] as? JsonArray).orEmpty().mapNotNull { e ->
            val m = e as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = (m[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val id = str("imdb_id").ifEmpty { str("id") }
            if (!Regex("tt\\d{5,}").matches(id)) return@mapNotNull null
            Found(id, str("name"), Regex("(19|20)\\d{2}").find(str("releaseInfo").ifEmpty { str("year") })?.value?.toInt())
        }
    }

    /**
     * The one result that is surely this title, or null. With no year, a series' name shared by
     * several titles ("Friends") is taken when Cinemeta's search puts it first (its best answer,
     * the well-known one); a film's isn't (remakes share names more often, and a film's year is
     * nearly always in its name or on its page).
     */
    fun pick(found: List<Found>, name: String, year: Int?, series: Boolean = false): String? {
        val want = norm(name)
        if (want.isEmpty()) return null
        val same = found.filter { norm(it.name) == want }
        if (year == null) {
            return same.singleOrNull()?.id ?: if (series && found.firstOrNull()?.let { norm(it.name) == want } == true) found.first().id else null
        }
        return same.filter { it.year != null && kotlin.math.abs(it.year - year) <= 1 }
            .minByOrNull { kotlin.math.abs(it.year!! - year) }?.id
    }

    /** Letters and digits only, lower case, without accents ("Amélie" = "amelie", "&" = "and"). */
    fun norm(s: String): String {
        val plain = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return plain.replace("&", " and ").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    // Tags providers put round names.
    private val LEAD_BRACKET = Regex("^\\s*(\\[[^\\]]{1,12}\\]|\\|[^|]{1,12}\\||\\{[^}]{1,12}\\})\\s*")
    // "EN - ", "AR: ", "FR| ", "ENG - ", "4K - " (two letters with any mark; three, or 4K/8K, only with | or a spaced dash).
    private val LEAD_CODE = Regex("^\\s*(?:[A-Z]{2}\\s*[-:|•]|(?:[A-Z]{3}|[48]K|VOD|MULTI)\\s*(?:\\||\\s-))\\s*")
    private val TRAIL_TAG = Regex(
        "(?i)[\\s\\-|•]*[\\[(]?\\b(4k|8k|uhd|fhd|hd|sd|hevc|x265|x264|h\\.?26[45]|2160p|1080p|720p|480p|multi|multisub|subs?|dub(bed)?|vf|vostfr|vost|hdr10?|dv|imax|remastered|extended)\\b[\\])]?\\s*$",
    )

    /** "EN - The Matrix (1999) 4K" → ("The Matrix", 1999); "|AR| Breaking Bad" → ("Breaking Bad", null). */
    fun clean(raw: String, thisYear: Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)): Pair<String, Int?> {
        var s = raw.trim()
        repeat(3) {
            s = s.replace(LEAD_BRACKET, "")
            s = s.replace(LEAD_CODE, "")
        }
        repeat(4) { s = s.replace(TRAIL_TAG, "") }
        val year = Trending.yearIn(s, thisYear)
        if (year != null) {
            val y = year.toString()
            // The year and its brackets go; a name that is only a year ("1917") stays.
            val without = s.replace(Regex("\\s*[(\\[]\\s*$y\\s*[)\\]]"), "").let { t ->
                if (t != s) t else s.replace(Regex("(?<=\\S)\\s+$y\\b"), "")
            }
            if (norm(without).isNotEmpty()) s = without
        }
        repeat(4) { s = s.replace(TRAIL_TAG, "") }
        s = s.replace(Regex("\\[[^\\]]*\\]"), " ").replace(Regex("\\s+"), " ").trim().trim('-', '|', ':', '•', '·').trim()
        return s to year
    }
}
