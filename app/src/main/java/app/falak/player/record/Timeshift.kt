package app.falak.player.record

import android.content.Context

/**
 * The player's side of pause and rewind for one live channel: it plays the channel from the
 * buffer ([Captures]), a moment behind it. Pausing just stops reading (the buffer keeps filling);
 * rewinding or catching up opens the buffer again at another point.
 */
internal class Timeshift private constructor(private val context: Context, val capture: Capture, val url: String) {
    /** When the part the player opened at was live (what's showing = this + the player's position). */
    private var openedAt = System.currentTimeMillis()

    /** The buffer at about live: the address to play. */
    fun live(): String {
        val ring = capture.ring ?: return LocalStream.urlFor(capture, 0)
        val offset = ring.nearLive(2_000)
        openedAt = ring.timeAt(offset) ?: System.currentTimeMillis()
        return LocalStream.urlFor(capture, offset)
    }

    /** The buffer from about [time] (clamped to what's kept): the address to play. */
    fun at(time: Long): String {
        val ring = capture.ring ?: return live()
        val offset = ring.offsetAt(time)
        openedAt = ring.timeAt(offset) ?: time
        return LocalStream.urlFor(capture, offset)
    }

    /** When what's showing at [positionMs] into the opened part was live. */
    fun showingAt(positionMs: Long): Long = openedAt + positionMs.coerceAtLeast(0)

    /** How far behind live the picture is. */
    fun behind(positionMs: Long, now: Long = System.currentTimeMillis()): Long = (now - showingAt(positionMs)).coerceAtLeast(0)

    /** The oldest moment that can be rewound to. */
    fun oldest(): Long? = capture.ring?.oldestTime()

    val failed: Boolean get() = capture.state == Capture.State.FAILED

    fun release() = Captures.unwatch(context, capture)

    companion object {
        fun open(context: Context, url: String, ua: String, referrer: String, minutes: Int): Timeshift =
            Timeshift(context.applicationContext, Captures.watch(context.applicationContext, url, ua, referrer, minutes), url)
    }
}
