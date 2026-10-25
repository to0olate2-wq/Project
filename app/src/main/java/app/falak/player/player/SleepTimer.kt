package app.falak.player.player

import kotlin.math.ceil

/**
 * Sleep timer (the player's "Sleep" button): playback fades out over the last minute and stops,
 * after a set time or at the end of the programme on now, or stops when the film or episode
 * playing ends (the next one doesn't start). Shared by the player and Listen mode, so it carries
 * on when the sound goes on with the screen off. Times are [android.os.SystemClock.elapsedRealtime].
 */
object SleepTimer {
    enum class Mode { OFF, AT_TIME, END_OF_ITEM }

    @Volatile var mode: Mode = Mode.OFF
        private set

    /** When it stops ([Mode.AT_TIME]). */
    @Volatile var endsAt: Long = 0L
        private set

    /** What it waits for ([Mode.END_OF_ITEM]): the item's id. */
    @Volatile var itemId: String? = null
        private set

    /** What was chosen, for the button and the dialog ("30 min", "End of the programme"). */
    @Volatile var label: String = ""
        private set

    const val FADE_MS = 60_000L

    val isOn: Boolean get() = mode != Mode.OFF

    /** Stops [minutes] from [now]. */
    fun after(minutes: Int, now: Long) {
        mode = Mode.AT_TIME
        endsAt = now + minutes * 60_000L
        itemId = null
        label = "$minutes min"
    }

    /** Stops at [at] (the end of the programme on now). */
    fun at(at: Long, label: String) {
        mode = Mode.AT_TIME
        endsAt = at
        itemId = null
        this.label = label
    }

    /** Stops when [id] (a film or episode) ends. */
    fun endOf(id: String, label: String) {
        mode = Mode.END_OF_ITEM
        endsAt = 0L
        itemId = id
        this.label = label
    }

    fun cancel() {
        mode = Mode.OFF
        endsAt = 0L
        itemId = null
        label = ""
    }

    /** Time left ([Mode.AT_TIME]), at least 0; null otherwise. */
    fun remaining(now: Long): Long? = if (mode == Mode.AT_TIME) (endsAt - now).coerceAtLeast(0L) else null

    /** It's time to stop now. */
    fun due(now: Long): Boolean = mode == Mode.AT_TIME && now >= endsAt

    /** Volume for the fade: 1 until the last minute, then down to 0. */
    fun volume(now: Long): Float {
        val left = remaining(now) ?: return 1f
        return if (left >= FADE_MS) 1f else (left.toFloat() / FADE_MS).coerceIn(0f, 1f)
    }

    /** The item that just ended was the one waited for: stop instead of going on. */
    fun stopsAfter(id: String?): Boolean = mode == Mode.END_OF_ITEM && id != null && id == itemId

    /** "Sleep 28 min" / "Sleep · end" for the player's button. */
    fun buttonLabel(now: Long): String = when (mode) {
        Mode.OFF -> "Sleep"
        Mode.END_OF_ITEM -> "Sleep · end"
        Mode.AT_TIME -> {
            val left = remaining(now) ?: 0L
            if (left < 60_000L) "Sleep <1 min" else "Sleep ${ceil(left / 60_000.0).toInt()} min"
        }
    }
}
