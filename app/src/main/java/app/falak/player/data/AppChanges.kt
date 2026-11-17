package app.falak.player.data

/**
 * Big changes to core features, by version (8.27; the owner: "whenever there is a new update with
 * big changes in some core features, show where the update is"). After an update, the ones newer
 * than the version last opened are shown once as a short guide (Guide), each pointing at the
 * button it is about when that button is on screen. Add an entry here with a release only when
 * it changes how something the owner uses works or where it is; small fixes don't belong here.
 * [keys]: the buttons it may point at (Guide.TOUR's names: "Home", "Live", "Films", "Series",
 * "Search", "TV guide", "Add-ons", "More", "Settings"); none: a card in the middle.
 */
object AppChanges {
    class Change(val version: String, val title: String, val text: String, val keys: List<String> = emptyList())

    val BIG = listOf(
        Change(
            "8.27",
            "A Home button while you watch",
            "On a phone, the house at the top of the player takes you straight back to Home, from live TV, films and series.",
        ),
        Change(
            "8.27",
            "Skip intro on your provider's films and series",
            "\"Skip intro\" and \"Skip credits\" now also show on your provider's titles, when the free intro list knows them. " +
                "During a series' credits, the Up next card comes instead.",
        ),
        Change(
            "8.27",
            "A guide to the app",
            "Settings → Help and about shows the guide to the main buttons again, and what changed in this version.",
            keys = listOf("Settings", "More"),
        ),
        Change(
            "8.28",
            "Keep everything in a backup file",
            "Settings → Devices & backup: save your playlists, favorites, lists and settings to a file, " +
                "and restore them on a new phone. The file holds your playlists' passwords: keep it private.",
            keys = listOf("Settings", "More"),
        ),
        Change(
            "8.28",
            "Lock the screen, and Chromecast",
            "On a phone, the lock on the left of the player stops touches while you watch. " +
                "The cast button at the top sends a channel or film to a Chromecast on your Wi-Fi.",
        ),
        Change(
            "8.29",
            "Actors and ratings",
            "Tap an actor on a film's or series' page to see what they played in, with what your playlist has first; " +
                "search finds actors by name too. Film and series pages show their IMDb rating.",
            keys = listOf("Search"),
        ),
        Change(
            "8.29",
            "Sound timing",
            "Sound out of step with the picture? In the player, Player → Sound timing moves it earlier or later.",
        ),
        Change(
            "8.31",
            "Settings in fewer groups",
            "Settings now has eight groups: Look & start, Player, Sound & subtitles, Playlists & TV guide, " +
                "Recordings & reminders, Profiles & parental control, Devices & backup, and Help and about.",
            keys = listOf("Settings", "More"),
        ),
        Change(
            "8.31",
            "Frozen streams restart by themselves",
            "When a channel or film stops moving, the app reconnects on its own. Settings → Player → Check the connection " +
                "measures your internet and your provider. On a series' page, press and hold an episode to mark it watched.",
        ),
        Change(
            "8.32",
            "Move the mini player anywhere",
            "On a phone, drag the small picture wherever you like. On a TV, hold OK on it, move it with the arrows, " +
                "then press OK. It stays where you put it.",
        ),
        Change(
            "8.33",
            "MAC portals",
            "Providers that give a portal and a MAC address work now: Playlists → Add → MAC portal. " +
                "Live TV, films, series and the portal's TV guide come from it.",
        ),
        Change(
            "8.33",
            "Up to four screens at once",
            "With two screens on, add a third and a fourth: Add a screen on a phone, or hold OK on a TV remote. " +
                "Choose the picture you hear with a tap, or with the arrows on a TV.",
        ),
    )

    /** Versions before 8.27 didn't note which one was opened last: taken as this one. */
    const val FIRST_NOTED = "8.26"

    /** The big changes after [seen] up to [current]. */
    fun since(seen: String, current: String): List<Change> =
        BIG.filter { newer(it.version, seen) && !newer(it.version, current) }

    /** "8.10" is newer than "8.9". */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}
