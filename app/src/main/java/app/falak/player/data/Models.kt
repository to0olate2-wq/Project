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
        tvgId.lowercase().ifEmpty { "name:" + normName(name) }
    }
    val isSeriesFolder: Boolean get() = seriesId.isNotEmpty() && url.isEmpty()
    val hasCatchup: Boolean get() = kind == Kind.LIVE && catchupDays > 0
}

@Serializable
data class Program(val start: Long, val stop: Long, val title: String, val desc: String)

@Serializable
class GuideCache(val savedAt: Long, val programs: Map<String, List<Program>>, val matched: Int = 0)

private val PREFIX = Regex("^\\s*[\\[(]?[a-z]{2,3}[\\])]?\\s*(?:[|\\-–]\\s+|:\\s*)")
private val BRACKETS = Regex("[\\[(][^\\])]*[\\])]")
private val QUALITY = Regex("\\b(fhd|uhd|hd|sd|4k|8k|hevc|h265|1080p?|720p?|hq|lq|backup|vip|raw|multi|ᴴᴰ)\\b")
private val NON_WORD = Regex("[^\\p{L}\\p{N}+]")

/**
 * "AR: MBC 1 HD", "[AE] MBC1 (Backup)" and "mbc1" all become "mbc1", so guides can be
 * matched by channel name when the playlist's tvg-id doesn't match the guide.
 */
fun normName(s: String): String {
    var t = s.lowercase()
    t = PREFIX.replace(t, "")
    t = BRACKETS.replace(t, " ")
    t = QUALITY.replace(t, " ")
    return NON_WORD.replace(t, "")
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
