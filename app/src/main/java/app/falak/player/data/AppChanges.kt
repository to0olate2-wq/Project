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
            "With two screens on, add a third and a fourth: tap Add on a phone, or hold OK on a TV remote. " +
                "Choose the picture you hear with a tap, or with the arrows on a TV.",
        ),
        Change(
            "8.34",
            "Sports, pinned groups and more on Home",
            "Home shows the matches on now and later today (from your TV guide), groups you pin (hold a group: Pin to Home), " +
                "what's popular now and \"Because you watched\". A film listed several times shows once, with its versions on its page; " +
                "Films has collections.",
            keys = listOf("Home"),
        ),
        Change(
            "8.38",
            "More room on your phone",
            "Scroll down and the bars at the top and bottom slide away; scroll up a little and they come back. " +
                "In the add-ons' portal the search is the magnifier at the top (on the left when the phone is on its side). " +
                "Profiles have cartoon pictures: tap one in Settings → Profiles to change it.",
        ),
        Change(
            "8.40",
            "Pictures and shortcuts",
            "Hold a profile on \"Who's watching?\" to change its picture: 25 characters, or a photo of your own on a phone. " +
                "On a phone, hold the app's icon for Live TV, your last channel or Search.",
        ),
        Change(
            "8.42",
            "The menu as icons",
            "On a TV the menu is now along the top: icons, with the name of the one the remote is on. " +
                "On a phone the bar at the bottom shows icons only.",
            keys = listOf("Home"),
        ),
        Change(
            "8.42",
            "Tidier player buttons, and \"Back in…\"",
            "On a phone the tools you set once (Cast, Picture size, Sleep, Lock, Player) sit together at the top right; the rest are one row " +
                "under the progress bar. On a live channel, \"Back in…\" lets you watch something else and brings you back after " +
                "5 to 20 minutes, or when the next programme starts. After 3 hours without a button pressed, the player asks " +
                "\"Still watching?\" (Settings → Player).",
        ),
        Change(
            "8.42",
            "Phone and TV together",
            "With your phone and TV linked, \"On TV\" in the player moves what you watch to the TV, at the same place " +
                "(\"On phone\" the other way). More → Second screen shows what the TV plays, with a remote. " +
                "And Continue watching ends with \"What can I finish?\": pick the time you have.",
        ),
        Change(
            "8.43",
            "Live TV's list on a TV",
            "The channel the remote is on plays small at the top right, with what's on, what's next and the time left beside it. " +
                "OK opens it full screen. The bar above the list picks the group, List, Grid or the TV guide, and Search.",
        ),
        Change(
            "8.43",
            "New icons",
            "The player's buttons are icons now (on a TV the name shows on the one the remote is on), and every icon has a new, cleaner look.",
        ),
        Change(
            "8.46",
            "A shorter Home",
            "Home starts with what's yours: Continue watching, your favorite channels and what you watched lately. " +
                "\"Popular now\" and \"For this hour\" are off; turn them on in Settings → Look & start → Home rows. " +
                "If you had arranged Home yourself, it stays as you set it.",
            keys = listOf("Home"),
        ),
        Change(
            "8.48",
            "Search from any page",
            "The magnifier is on every page now (on a TV in the menu at the top, and at the top right of a film's or a series' page; " +
                "on a phone also under More). It searches every channel, film and series.",
            keys = listOf("Search"),
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
