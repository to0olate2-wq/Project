package app.falak.player.ui

import android.view.KeyEvent
import app.falak.player.data.Settings

/** What a remote button can be given to do while watching (Settings → Remote buttons). */
object KeyActions {
    const val CHANNELS = "channels"
    const val GUIDE = "guide"
    const val FAVORITE = "favorite"
    const val RECORD = "record"
    const val RECORDINGS = "recordings"
    const val AUDIO = "audio"
    const val SUBTITLES = "subtitles"
    const val PICTURE = "picture"
    const val LAST = "last"
    const val TWO = "two"
    const val SLEEP = "sleep"
    const val REWIND = "rewind"
    const val FORWARD = "forward"
    const val LIVE = "live"
    const val PLAY_PAUSE = "playpause"
    const val INFO = "info"
    const val MENU = "menu"
    const val PLAYER = "player"

    val ALL: List<Pair<String, String>> = listOf(
        CHANNELS to "Channel list",
        GUIDE to "TV guide",
        RECORD to "Record",
        RECORDINGS to "Recordings",
        REWIND to "Rewind 30 seconds",
        FORWARD to "Forward 30 seconds",
        LIVE to "Back to live",
        PLAY_PAUSE to "Pause / play",
        FAVORITE to "Add to Favorites",
        LAST to "Previous channel",
        AUDIO to "Audio track",
        SUBTITLES to "Subtitles",
        PICTURE to "Picture size",
        TWO to "Two screens",
        SLEEP to "Sleep timer",
        INFO to "What's on",
        MENU to "Menu",
        PLAYER to "Choose player",
    )

    fun label(job: String): String = ALL.firstOrNull { it.first == job }?.second ?: job

    /** Buttons that keep their own job: the arrows, OK, Back, Home, volume, numbers, channel up and down. */
    private val RESERVED = setOf(
        KeyEvent.KEYCODE_UNKNOWN,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_DPAD_UP_LEFT, KeyEvent.KEYCODE_DPAD_UP_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN_LEFT, KeyEvent.KEYCODE_DPAD_DOWN_RIGHT,
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_SOFT_SLEEP,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE,
        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SETTINGS,
        KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_SPACE,
    )

    fun assignable(code: Int): Boolean =
        code !in RESERVED && code !in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && code !in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 &&
            code !in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z

    /** "Red", "Green", "Info", "F1"… */
    fun keyName(code: Int): String = when (code) {
        KeyEvent.KEYCODE_PROG_RED -> "Red"
        KeyEvent.KEYCODE_PROG_GREEN -> "Green"
        KeyEvent.KEYCODE_PROG_YELLOW -> "Yellow"
        KeyEvent.KEYCODE_PROG_BLUE -> "Blue"
        KeyEvent.KEYCODE_MENU -> "Menu"
        KeyEvent.KEYCODE_INFO -> "Info"
        KeyEvent.KEYCODE_GUIDE -> "Guide"
        KeyEvent.KEYCODE_MEDIA_RECORD -> "Record"
        KeyEvent.KEYCODE_BOOKMARK -> "Bookmark"
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ').lowercase()
            .replaceFirstChar { it.uppercase() }
    }
}

/** Home's rows, in the order chosen and without the ones switched off (Settings → Appearance → Home rows). */
object HomeRows {
    val ALL: List<Pair<String, String>> = listOf(
        "continue" to "Continue watching",
        "downloads" to "Downloads",
        "recordings" to "Recordings",
        "top" to "Top 10 on your playlist",
        "forThisHour" to "For this hour",
        "recentLive" to "Recently watched",
        "newMovies" to "New films",
        "newSeries" to "New series",
        "favLive" to "Favorite channels",
        "favVod" to "Favorite films & series",
        "lists" to "Your lists",
    )

    private val keys = ALL.map { it.first }

    fun label(key: String): String = ALL.firstOrNull { it.first == key }?.second ?: key

    /** Every row, in the order chosen (new rows from a later version go at the end). */
    fun order(s: Settings): List<String> {
        val chosen = s.homeRows.filter { it in keys }.distinct()
        return chosen + keys.filter { it !in chosen }
    }

    fun shown(s: Settings): List<String> = order(s).filter { it !in s.homeHidden }

    /** [key] moved [delta] places (−1 up, +1 down). */
    fun moved(s: Settings, key: String, delta: Int): List<String> {
        val list = order(s).toMutableList()
        val at = list.indexOf(key)
        val to = (at + delta).coerceIn(0, list.lastIndex)
        if (at < 0 || at == to) return list
        list.removeAt(at)
        list.add(to, key)
        return list
    }
}
