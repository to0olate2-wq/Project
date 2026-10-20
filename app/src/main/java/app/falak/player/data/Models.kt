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
) {
    val epgKey: String get() = tvgId.lowercase()
    val isSeriesFolder: Boolean get() = seriesId.isNotEmpty() && url.isEmpty()
}

data class Program(val start: Long, val stop: Long, val title: String, val desc: String)

class Season(val number: Int, val episodes: List<Channel>)

class SeriesInfo(val plot: String, val cover: String, val seasons: List<Season>)

@Serializable
data class PlaylistCache(val channels: List<Channel>, val epgUrl: String = "")

@Serializable
data class Settings(
    /** 0 = fit, 1 = fill (stretch), 2 = zoom (crop) */
    val aspect: Int = 0,
    val bigBuffer: Boolean = false,
    val preferHls: Boolean = false,
    val openLast: Boolean = true,
    /** ISO 639 code like "ar" or "en"; empty = what the stream prefers. */
    val audioLang: String = "",
    /** Show subtitles/closed captions automatically when a stream has them. */
    val subtitlesOn: Boolean = false,
    val subtitleLang: String = "",
    val pin: String = "",
    val lockAdult: Boolean = true,
    /** playlistId -> locked group names */
    val lockedGroups: Map<String, List<String>> = emptyMap(),
)

/** Watch progress of a movie or episode. */
@Serializable
data class Watch(val pos: Long, val dur: Long)

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
