package app.falak.player.data

import kotlinx.serialization.Serializable

@Serializable
enum class SourceType { M3U_URL, M3U_FILE, XTREAM }

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val type: SourceType,
    /** M3U link, local file path, or Xtream server URL. */
    val url: String,
    val username: String = "",
    val password: String = "",
    /** Optional EPG (XMLTV) link that overrides the one from the provider. */
    val epgUrl: String = "",
    val userAgent: String = "",
    /** Extra XMLTV guides merged after the provider's (fills channels the provider misses). */
    val extraEpg: List<String> = emptyList(),
    /** Fix for guides published with the wrong time zone, in minutes (e.g. -60). */
    val epgShiftMinutes: Int = 0,
)

@Serializable
enum class Kind { LIVE, MOVIE, SERIES }

@Serializable
data class Channel(
    val id: String,
    val name: String,
    val group: String,
    val url: String,
    val logo: String = "",
    val tvgId: String = "",
    val kind: Kind = Kind.LIVE,
    val userAgent: String = "",
    val referrer: String = "",
    /** Xtream series id: set on series entries, which open an episode list instead of playing. */
    val seriesId: String = "",
    /** Extra line shown under the name, e.g. "E3 · 45 min". */
    val info: String = "",
    /** When the provider added it (epoch seconds, Xtream only); powers "Newly added". */
    val added: Long = 0,
    val rating: String = "",
    /** Days of replay (catch-up) the provider keeps for this live channel; 0 = none. */
    val catchupDays: Int = 0,
    /** "xc" (Xtream), "default", "append", "shift", "flussonic" … from the playlist. */
    val catchupType: String = "",
    val catchupSource: String = "",
) {
    /** Guide key: the tvg-id, or the cleaned-up name when the playlist has no id. */
    val epgKey: String by lazy(LazyThreadSafetyMode.NONE) {
        // Name-only keys carry the country, so "FR: beIN Sports 1" and "AR: beIN Sports 1" stay apart.
        tvgId.lowercase().ifEmpty { "name:" + (countryOf(name, group) ?: "") + ":" + normName(name) }
    }
    val isSeriesFolder: Boolean get() = seriesId.isNotEmpty() && url.isEmpty()
    val hasCatchup: Boolean get() = kind == Kind.LIVE && catchupDays > 0
}

@Serializable
data class Program(val start: Long, val stop: Long, val title: String, val desc: String)

class GuideCache(
    val savedAt: Long,
    val programs: Map<String, List<Program>>,
    val matched: Int = 0,
    /** Live channels the guide was matched against. */
    val total: Int = 0,
    /** Every channel the guide sources offer, for fixing a channel's guide by hand. */
    val channels: List<GuideChannel> = emptyList(),
)

/** A channel listed in a guide source: its XMLTV id, display name and the source's host. */
class GuideChannel(val id: String, val name: String, val source: String)

/** A user-made list of channels, films or series ("Sports", "Kids", …). */
@Serializable
data class FavList(val id: String, val name: String, val ids: List<String> = emptyList())

/** List id of the built-in Favourites list (stored in [AppState.favorites]). */
const val DEFAULT_LIST = "fav"

/**
 * Country/language tags in front of names: "UK: ", "UK | ", "|UK| ", "[UK] ", "(AR) ", "AR ▎ ", "USA - ".
 * Group 1 = bracketed tag, group 2 = bare tag followed by a separator.
 */
private val PREFIX = Regex(
    "^\\s*(?:[\\[(|]\\s*([a-z0-9]{2,3})\\s*[\\])|]\\s*[|:▎┃•\\-–—]*\\s*|([a-z0-9]{2,3})\\s*(?:[|:▎┃•]+\\s*|\\s[-–—]\\s+|[-–—]\\s+))",
)
/** Superscript tags like ᴴᴰ, ᶠᴴᴰ, ⁴ᴷ (phonetic extensions and super/subscripts). */
private val SUPERSCRIPT = Regex("[\\u1D00-\\u1DBF\\u2070-\\u209F]+")
private val BRACKETS = Regex("[\\[(][^\\])]*[\\])]")
private val QUALITY = Regex(
    "\\b(fhd|uhd|hd|sd|4k|8k|hevc|h\\.?26[45]|1080[pi]?|720p?|2160p?|50fps|60fps|fps|hq|lq|backup|vip|raw|multi|" +
        "hdr|dolby|full hd|ultra hd|low|high|alt|bk|orig|original|direct|ᴴᴰ|ᶠᴴᴰ|ᵁᴴᴰ|ᴿᴬᵂ|⁴ᴷ|ˢᴰ)\\b",
)
private val NON_WORD = Regex("[^\\p{L}\\p{N}+]+")
private val COMBINING = Regex("\\p{Mn}+")
/** Non-country prefixes providers put in front of names. */
private val TAGS = setOf(
    "en", "eng", "ara", "arb", "fra", "fre", "ger", "ita", "spa", "esp", "por", "tur", "hin", "urd", "per", "kur",
    "int", "vip", "top", "ott", "4k", "hd", "fhd", "uhd", "sd", "ex", "ext", "pl", "24", "7",
)

/** Words that some guides add and others don't ("Al Jazeera" vs "Al Jazeera Channel"). */
private val GENERIC = setOf("tv", "channel", "network", "television", "the", "live", "east", "hd")

/** Lowercase, accents removed, country prefixes and quality tags dropped, split into words. */
fun nameWords(s: String): List<String> {
    var t = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
    t = COMBINING.replace(t, "")
    t = SUPERSCRIPT.replace(t, " ")
    t = t.replace("&", " and ")
    repeat(2) { // "VIP | UK | BBC One"
        val m = PREFIX.find(t)
        val tag = m?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        if (m != null && tag != null && (canonCountry(tag) != null || tag in TAGS)) t = t.substring(m.range.last + 1)
    }
    t = BRACKETS.replace(t, " ")
    t = QUALITY.replace(t, " ")
    return t.split(NON_WORD).filter { it.isNotEmpty() }
}

/**
 * "AR: MBC 1 HD", "[AE] MBC1 (Backup)" and "mbc1" all become "mbc1", so guides can be
 * matched by channel name when the playlist's tvg-id doesn't match the guide.
 */
fun normName(s: String): String = nameWords(s).joinToString("")

/** Like [normName] but also ignores generic words such as "TV" and "Channel". */
fun looseName(s: String): String = nameWords(s).filter { it !in GENERIC }.joinToString("")

private val COUNTRY_WORDS = listOf(
    "united kingdom" to "uk", "england" to "uk", "british" to "uk", "scotland" to "uk",
    "united states" to "us", "usa" to "us", "america" to "us",
    "arabic" to "ar", "arab" to "ar", "عربي" to "ar", "france" to "fr", "french" to "fr",
    "germany" to "de", "deutsch" to "de", "canada" to "ca", "india" to "in", "hindi" to "in",
    "turkey" to "tr", "turkish" to "tr", "türk" to "tr", "spain" to "es", "españa" to "es", "spanish" to "es",
    "italy" to "it", "italia" to "it", "portugal" to "pt", "brazil" to "br", "brasil" to "br",
    "australia" to "au", "egypt" to "eg", "netherlands" to "nl", "dutch" to "nl", "poland" to "pl",
    "pakistan" to "pk", "ireland" to "ie", "mexico" to "mx", "latino" to "mx", "greece" to "gr",
)

/** Real country (or "ar" for Arabic) codes; other prefixes like "EN" or "VIP" say nothing about the guide. */
private val COUNTRY_CODES = setOf(
    "uk", "us", "ca", "au", "nz", "ie", "fr", "de", "at", "ch", "it", "es", "pt", "br", "mx", "ar", "tr", "in",
    "pk", "bd", "nl", "be", "se", "no", "dk", "fi", "pl", "ro", "gr", "ru", "ua", "ae", "sa", "eg", "qa", "kw",
    "lb", "ma", "dz", "tn", "iq", "jo", "om", "bh", "sy", "ps", "ly", "sd", "ye", "il", "ir", "af", "za", "ng",
    "ke", "ph", "id", "my", "sg", "th", "vn", "cn", "hk", "tw", "jp", "kr", "al", "rs", "hr", "ba", "mk", "bg",
    "hu", "cz", "sk", "si", "cl", "co", "pe", "ve", "ec", "uy", "cr", "pa", "do", "pr", "cu", "lat",
)

private val ARABIC_REGION = setOf(
    "ar", "ae", "sa", "eg", "qa", "kw", "lb", "ma", "dz", "tn", "iq", "jo", "om", "bh", "sy", "ps", "ly", "sd", "ye",
)

private fun canonCountry(code: String): String? = when (val c = code.lowercase()) {
    "gb", "eng" -> "uk"
    "usa" -> "us"
    "lat" -> "mx"
    else -> c.takeIf { it in COUNTRY_CODES }
}

/** Country of a playlist channel from its name prefix ("UK: …") or its group ("UK | Sports", "ARABIC"). */
fun countryOf(name: String, group: String): String? {
    for (s in listOf(name, group)) {
        var t = s.lowercase()
        repeat(2) { // "VIP | UK | …": look past one non-country tag
            val m = PREFIX.find(t) ?: return@repeat
            val tag = m.groupValues[1].ifEmpty { m.groupValues[2] }
            canonCountry(tag)?.let { return it }
            if (tag !in TAGS) return@repeat
            t = t.substring(m.range.last + 1)
        }
    }
    val g = group.lowercase()
    return COUNTRY_WORDS.firstOrNull { (w, _) -> g.contains(w) }?.second
}

/** Country suffix of an XMLTV id: "BBCOne.uk" → "uk", "MBC1.ae" → "ae". */
fun guideCountry(guideId: String): String? {
    val tail = guideId.substringAfterLast('.', "")
    return if (tail.length in 2..3) canonCountry(tail) else null
}

/** 2 = same country (or same Arabic region), 1 = unknown on either side, 0 = different countries. */
fun countryAgreement(channel: String?, guide: String?): Int = when {
    channel == null || guide == null -> 1
    channel == guide -> 2
    channel in ARABIC_REGION && guide in ARABIC_REGION -> 2
    else -> 0
}

class Season(val number: Int, val episodes: List<Channel>)

class SeriesInfo(
    val plot: String,
    val cover: String,
    val seasons: List<Season>,
    val backdrop: String = "",
    val genre: String = "",
    val cast: String = "",
    val year: String = "",
    val rating: String = "",
)

/** Movie details (Xtream get_vod_info). */
class VodInfo(
    val plot: String,
    val poster: String,
    val backdrop: String,
    val genre: String,
    val cast: String,
    val director: String,
    val year: String,
    val duration: String,
    val rating: String,
    val trailer: String,
)

@Serializable
data class PlaylistCache(
    val channels: List<Channel>,
    val epgUrl: String = "",
    /** Xtream server time zone (IANA id), needed to request replays at the right time. */
    val serverTz: String = "",
    /** When this copy was downloaded (epoch ms); drives the automatic refresh. */
    val savedAt: Long = 0,
)

@Serializable
data class Settings(
    /** 0 = fit, 1 = fill (stretch), 2 = zoom (crop) */
    val aspect: Int = 0,
    val bigBuffer: Boolean = false,
    /** Decode all audio in software (FFmpeg). Fixes silent Dolby/DTS on boxes whose HDMI passthrough fails. */
    val softwareAudio: Boolean = false,
    val preferHls: Boolean = false,
    val openLast: Boolean = true,
    /** ISO 639 code like "ar" or "en"; empty = what the stream prefers. */
    val audioLang: String = "",
    /** Show subtitles/closed captions automatically when a stream has them. */
    val subtitlesOn: Boolean = false,
    val subtitleLang: String = "",
    /** OpenSubtitles.com: free API key (+ optional account for more downloads per day). */
    val osApiKey: String = "",
    val osUser: String = "",
    val osPass: String = "",
    val pin: String = "",
    val lockAdult: Boolean = true,
    /** playlistId -> locked group names */
    val lockedGroups: Map<String, List<String>> = emptyMap(),
    /** playlistId -> group names the user doesn't want to see */
    val hiddenGroups: Map<String, List<String>> = emptyMap(),
    /** Index into the theme list (Screening room, Midnight, Pure black, Emerald, Daylight). */
    val theme: Int = 0,
    /** Refresh the playlist in the background when the saved copy is older than this; 0 = never. */
    val autoRefreshHours: Int = 12,
    /** playlistId -> (channel guide key -> guide channel id chosen by hand; "" = no guide) */
    val guideOverrides: Map<String, Map<String, String>> = emptyMap(),
)

/** Watch progress of a movie or episode. */
@Serializable
data class Watch(
    val pos: Long,
    val dur: Long,
    // Enough to show it in "Continue watching" and resume it directly (episodes aren't in the main list).
    val title: String = "",
    val image: String = "",
    val url: String = "",
    val kind: Kind = Kind.MOVIE,
    val seriesId: String = "",
)

@Serializable
data class AppState(
    val playlists: List<Playlist> = emptyList(),
    /** playlistId -> channel ids */
    val favorites: Map<String, List<String>> = emptyMap(),
    /** playlistId -> the user's own lists, in display order */
    val favLists: Map<String, List<FavList>> = emptyMap(),
    val recents: Map<String, List<String>> = emptyMap(),
    val lastPlaylistId: String? = null,
    val settings: Settings = Settings(),
    /** progressKey(playlistId, channelId) -> position */
    val progress: Map<String, Watch> = emptyMap(),
)

val ADULT_GROUP = Regex("(?i)(xxx|adult|18\\s*\\+|porn)")

fun progressKey(playlistId: String, channelId: String) = "$playlistId|$channelId"

/** 64-bit FNV-1a hash, so ids stay stable across playlist refreshes. */
fun stableId(s: String): String {
    var h = 0xcbf29ce484222325UL.toLong()
    for (c in s) {
        h = h xor c.code.toLong()
        h *= 0x100000001b3L
    }
    return java.lang.Long.toUnsignedString(h, 36)
}

/**
 * Some providers list the same stream twice. Lists key their rows by id and crash on duplicates,
 * so later copies get "_2", "_3"… (the first keeps its id, so favourites stay put).
 */
fun ensureUniqueIds(list: List<Channel>): List<Channel> {
    val seen = HashSet<String>(list.size * 2)
    var out: ArrayList<Channel>? = null
    for ((i, ch) in list.withIndex()) {
        if (seen.add(ch.id)) {
            out?.add(ch)
            continue
        }
        if (out == null) out = ArrayList<Channel>(list.size).apply { addAll(list.subList(0, i)) }
        var n = 2
        while (!seen.add("${ch.id}_$n")) n++
        out.add(ch.copy(id = "${ch.id}_$n"))
    }
    return out ?: list
}
