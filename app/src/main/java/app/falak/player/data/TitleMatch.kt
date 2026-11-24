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
        // Plain English letters (most names): done by hand, many times quicker (8.29).
        if (s.all { it.code < 128 }) {
            // Words of letters and digits; "&" is the word "and"; anything else only parts words.
            val b = StringBuilder(s.length + 4)
            var gap = false
            for (c in s) {
                when {
                    c in 'a'..'z' || c in '0'..'9' || c in 'A'..'Z' -> {
                        if (gap && b.isNotEmpty()) b.append(' ')
                        gap = false
                        b.append(c.lowercaseChar())
                    }
                    c == '&' -> {
                        if (b.isNotEmpty()) b.append(' ')
                        b.append("and")
                        gap = true
                    }
                    else -> gap = true
                }
            }
            return b.toString()
        }
        val plain = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(MARKS, "")
        return plain.replace("&", " and ").replace(NOT_LETTERS, " ").trim()
    }

    // (Made once: a playlist's names are cleaned by the ten thousand, 8.29.)
    private val MARKS = Regex("\\p{Mn}+")
    private val NOT_LETTERS = Regex("[^\\p{L}\\p{N}]+")
    private val BRACKETED = Regex("\\[[^\\]]*\\]")
    private val SPACES = Regex("\\s+")
    private val TAGS = setOf(
        "4k", "8k", "uhd", "fhd", "hd", "sd", "hevc", "x265", "x264", "h264", "h265", "h.264", "h.265", "2160p", "1080p", "720p", "480p",
        "multi", "multisub", "sub", "subs", "dub", "dubbed", "vf", "vostfr", "vost", "hdr", "hdr10", "dv", "imax", "remastered", "extended",
    )

    /** Its last word is a tag like "4K" or "(HDR)" (checked before the slower TRAIL_TAG pattern). */
    private fun endsWithTag(s: String): Boolean {
        var end = s.length
        while (end > 0 && (s[end - 1].isWhitespace() || s[end - 1] == ')' || s[end - 1] == ']')) end--
        var start = end
        while (start > 0 && (s[start - 1].isLetterOrDigit() || s[start - 1] == '.')) start--
        if (end <= start) return false
        val word = s.substring(start, end).lowercase()
        return word in TAGS || word.substringAfterLast('.') in TAGS
    }
    private val yearPatterns = java.util.concurrent.ConcurrentHashMap<Int, Pair<Regex, Regex>>()

    /** This year, asked of the calendar once an hour at most (asking it costs more than cleaning a name). */
    private var yearNow = 0
    private var yearAsked = 0L
    fun currentYear(): Int {
        val now = System.currentTimeMillis()
        if (now - yearAsked > 3_600_000L) {
            yearNow = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            yearAsked = now
        }
        return yearNow
    }

    // Tags providers put round names.
    private val LEAD_BRACKET = Regex("^\\s*(\\[[^\\]]{1,12}\\]|\\|[^|]{1,12}\\||\\{[^}]{1,12}\\})\\s*")
    // "EN - ", "AR: ", "FR| ", "ENG - ", "4K - " (two letters with any mark; three, or 4K/8K, only with | or a spaced dash).
    private val LEAD_CODE = Regex("^\\s*(?:[A-Z]{2}\\s*[-:|•]|(?:[A-Z]{3}|[48]K|VOD|MULTI)\\s*(?:\\||\\s-))\\s*")
    private val TRAIL_TAG = Regex(
        "(?i)[\\s\\-|•]*[\\[(]?\\b(4k|8k|uhd|fhd|hd|sd|hevc|x265|x264|h\\.?26[45]|2160p|1080p|720p|480p|multi|multisub|subs?|dub(bed)?|vf|vostfr|vost|hdr10?|dv|imax|remastered|extended)\\b[\\])]?\\s*$",
    )

    /** "EN - The Matrix (1999) 4K" → ("The Matrix", 1999); "|AR| Breaking Bad" → ("Breaking Bad", null). */
    fun clean(raw: String, thisYear: Int = currentYear()): Pair<String, Int?> {
        var s = raw.trim()
        // (8.49) Each pattern only where a quick look at the letters says it can match: a TV box spent
        // seconds on the patterns of a big playlist's names, most of which have no tags at all.
        for (round in 0 until 3) {
            val before = s
            if (leadMark(s, bracket = true)) s = s.replace(LEAD_BRACKET, "")
            if (leadMark(s, bracket = false)) s = s.replace(LEAD_CODE, "")
            if (s == before) break
        }
        trailTags@ for (k in 0 until 4) { if (!endsWithTag(s)) break@trailTags; s = s.replace(TRAIL_TAG, "") }
        val year = Trending.yearIn(s, thisYear)
        if (year != null) {
            val y = year.toString()
            // The year and its brackets go; a name that is only a year ("1917") stays.
            val (inBrackets, after) = yearPatterns.getOrPut(year) {
                Regex("\\s*[(\\[]\\s*$y\\s*[)\\]]") to Regex("(?<=\\S)\\s+$y\\b")
            }
            val without = s.replace(inBrackets, "").let { t ->
                if (t != s) t else s.replace(after, "")
            }
            if (norm(without).isNotEmpty()) s = without
        }
        trailTags@ for (k in 0 until 4) { if (!endsWithTag(s)) break@trailTags; s = s.replace(TRAIL_TAG, "") }
        if (s.indexOf('[') >= 0) s = s.replace(BRACKETED, " ")
        if (oddSpaces(s)) s = s.replace(SPACES, " ")
        s = s.trim().trim('-', '|', ':', '•', '·').trim()
        return s to year
    }

    /** A space as the patterns' \s may see it (wider than isWhitespace: no-break spaces too). */
    private fun spaceLike(c: Char) = c.isWhitespace() || Character.isSpaceChar(c)

    /**
     * Whether [LEAD_BRACKET] ([bracket]) or [LEAD_CODE] could match at the start of [s]: its first
     * mark after spaces opens a bracket, or it starts with two capitals (or 4K / 8K).
     */
    internal fun leadMark(s: String, bracket: Boolean): Boolean {
        var i = 0
        while (i < s.length && spaceLike(s[i])) i++
        if (i >= s.length) return false
        val c = s[i]
        if (bracket) return c == '[' || c == '|' || c == '{'
        val d = s.getOrNull(i + 1) ?: return false
        return (c in 'A'..'Z' && d in 'A'..'Z') || ((c == '4' || c == '8') && d == 'K')
    }

    /** Spaces other than single plain ones between words (what [SPACES] would change). */
    internal fun oddSpaces(s: String): Boolean {
        for (i in s.indices) {
            val c = s[i]
            if (c == ' ') {
                if (i + 1 < s.length && spaceLike(s[i + 1])) return true
            } else if (spaceLike(c)) {
                return true
            }
        }
        return false
    }
}
