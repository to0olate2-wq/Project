package com.novatv.player.data

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
) {
    val epgKey: String get() = tvgId.lowercase()
}

data class Program(val start: Long, val stop: Long, val title: String, val desc: String)

@Serializable
data class PlaylistCache(val channels: List<Channel>, val epgUrl: String = "")

@Serializable
data class AppState(
    val playlists: List<Playlist> = emptyList(),
    /** playlistId -> channel ids */
    val favorites: Map<String, List<String>> = emptyMap(),
    val recents: Map<String, List<String>> = emptyMap(),
    val lastPlaylistId: String? = null,
)

/** 64-bit FNV-1a hash, so ids stay stable across playlist refreshes. */
fun stableId(s: String): String {
    var h = 0xcbf29ce484222325UL.toLong()
    for (c in s) {
        h = h xor c.code.toLong()
        h *= 0x100000001b3L
    }
    return java.lang.Long.toUnsignedString(h, 36)
}
