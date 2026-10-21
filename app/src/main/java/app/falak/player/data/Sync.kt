package app.falak.player.data

import kotlinx.serialization.Serializable

/**
 * Everything needed to set up The Player on another device: the playlists (with their logins,
 * and the file itself for playlists loaded from a file), favourites, own lists, hidden groups and
 * hand-picked guides. Settings that belong to one device (layout, player, PIN) stay put.
 */
@Serializable
data class SyncBundle(
    val format: Int = 1,
    val app: String = "The Player",
    /** Name of the device it came from. */
    val from: String = "",
    val createdAt: Long = 0,
    val playlists: List<SyncPlaylist> = emptyList(),
    val favorites: Map<String, List<String>> = emptyMap(),
    val favLists: Map<String, List<FavList>> = emptyMap(),
    val hiddenGroups: Map<String, List<String>> = emptyMap(),
    val guideOverrides: Map<String, Map<String, String>> = emptyMap(),
)

/** A playlist, plus the M3U text when it was loaded from a file on the sending device. */
@Serializable
data class SyncPlaylist(val playlist: Playlist, val m3u: String? = null)

/** What an import changed. */
class ImportResult(val added: Int, val updated: Int, val unchanged: Int) {
    fun describe(): String = when {
        added == 0 && updated == 0 -> "Already up to date"
        else -> listOfNotNull(
            if (added > 0) "$added playlist" + (if (added == 1) "" else "s") + " added" else null,
            if (updated > 0) "$updated updated" else null,
        ).joinToString(", ")
    }
}

/** Order-keeping union of two id lists. */
internal fun unionIds(a: List<String>, b: List<String>): List<String> {
    if (b.isEmpty()) return a
    val seen = LinkedHashSet(a)
    seen.addAll(b)
    return seen.toList()
}
