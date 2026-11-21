package app.falak.player.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

/**
 * Hand-off and the second screen (8.42): linked devices (Wi-Fi linking, see AutoSync) ask each other
 * what is playing, hand what plays here to the other one (at the same place), and the phone presses the
 * TV's keys. Everything goes sealed with the linked devices' key, like the sync itself.
 */
@Serializable
data class LinkAsk(
    /** "now" (what plays there), "play" (play [item] there) or "key" (press [key] there). */
    val op: String,
    /** When it was sent (wall clock): old asks are refused, so a copy can't be sent again later. */
    val sentAt: Long,
    /** The asking device's name ("Ahmed's phone"), for the message on the other side. */
    val from: String = "",
    val item: HandItem? = null,
    val key: String = "",
)

/** What plays, enough for the other device to play it from the same place. */
@Serializable
data class HandItem(
    /** The playlist's sync id (the same on every linked device; see SyncMerge.syncId). */
    val playlist: String,
    val channel: Channel,
    val pos: Long = 0,
    val dur: Long = 0,
    /** The series an episode belongs to (for "Continue watching" there). */
    val seriesId: String = "",
)

/** A programme of the guide, as the TV sends it to the phone's second screen. */
@Serializable
data class HandProgram(val start: Long, val stop: Long, val title: String, val desc: String = "")

/** What plays on a device now ("now"'s answer). */
@Serializable
data class NowState(
    val item: HandItem,
    val playing: Boolean = true,
    val now: HandProgram? = null,
    val next: HandProgram? = null,
)

@Serializable
data class LinkAnswer(
    val ok: Boolean,
    val message: String = "",
    /** The answering device's name. */
    val device: String = "",
    val now: NowState? = null,
)

object Handoff {
    /** Asks older (or newer: clocks differ a little) than this are refused. */
    const val MAX_AGE_MS = 5 * 60_000L

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** The keys the phone may press on the TV (the same names as the phone remote's page). */
    val KEYS = setOf("up", "down", "left", "right", "ok", "back", "playpause", "chup", "chdown", "info", "last")

    fun fresh(ask: LinkAsk, now: Long): Boolean = abs(now - ask.sentAt) <= MAX_AGE_MS

    /**
     * Whether [ch] can play on another device: not what only this device can open (a torrent or the
     * live buffer, served on 127.0.0.1 here). A MAC portal's links are 127.0.0.1 too, but the other
     * device has the same portal and makes its own ([portal] true for them).
     */
    fun canHand(ch: Channel, portal: Boolean): Boolean {
        val local = ch.url.startsWith("http://127.0.0.1") || ch.url.startsWith("http://localhost")
        return ch.url.isNotBlank() && (!local || portal)
    }

    /** Where to start on the other device: films and episodes a few seconds back (the hand-off takes a moment); live from now. */
    fun startAt(item: HandItem): Long =
        if (item.channel.kind == Kind.LIVE || item.channel.id.startsWith("cu:")) 0L else (item.pos - BACK_MS).coerceAtLeast(0L)

    private const val BACK_MS = 5_000L

    /** "12:04 / 1:45:10": where a film or episode is, on the second screen ("" without a length). */
    fun timeLine(pos: Long, dur: Long): String {
        if (dur <= 0) return ""
        return "${clock(pos)} / ${clock(dur)}"
    }

    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** A linked device's id from its network name ("tp-<group>-<first 8 of its id>"). */
    fun idPrefix(serviceName: String): String = serviceName.substringAfterLast('-')
}
