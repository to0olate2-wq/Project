package app.falak.player.record

import app.falak.player.data.Program
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
enum class RecState { SCHEDULED, RECORDING, DONE, FAILED }

/** A recording: planned, being made, or saved on this device (kept in recordings.json). */
@Serializable
data class Recording(
    val id: String,
    val playlistId: String,
    val channelId: String,
    val channelName: String,
    val logo: String = "",
    /** The programme's name (or the channel's, recorded without a programme). */
    val title: String,
    val desc: String = "",
    /** When recording starts and stops (with the minutes early and late). */
    val start: Long,
    val stop: Long,
    /** The programme's own times, to show; 0 when it was recorded without one. */
    val progStart: Long = 0,
    val progStop: Long = 0,
    /** Record from this time when the buffer still has it (a programme already under way); 0 = from now. */
    val from: Long = 0,
    val url: String,
    val userAgent: String = "",
    val referrer: String = "",
    /** A replay saved from the provider's archive (not live). */
    val replay: Boolean = false,
    val state: RecState = RecState.SCHEDULED,
    val file: String = "",
    val size: Long = 0,
    /** Why it stopped early or failed, shown under it. */
    val note: String = "",
    /** The series rule that planned it, if any. */
    val ruleId: String = "",
    val startedAt: Long = 0,
    val endedAt: Long = 0,
    val addedAt: Long = 0,
) {
    /** "this programme", for keeping a series rule from planning it twice. */
    val slot: String get() = "$channelId|${if (progStart > 0) progStart else start}"
}

/** "Record series": every programme with this name on this channel. */
@Serializable
data class SeriesRule(
    val id: String,
    val playlistId: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val logo: String = "",
    val addedAt: Long = 0,
)

@Serializable
data class RecordBook(
    val recordings: List<Recording> = emptyList(),
    val rules: List<SeriesRule> = emptyList(),
    /** Programmes a rule planned that were cancelled by hand (their [Recording.slot]): not planned again. */
    val skipped: List<String> = emptyList(),
)

internal object RecordBookStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun read(file: File): RecordBook =
        app.falak.player.data.SafeFile.read(file) { json.decodeFromString(RecordBook.serializer(), it) } ?: RecordBook()

    /** (Saved from the main thread too: no waiting for the disk, the `.bak` copy is the safety net.) */
    fun write(file: File, book: RecordBook) =
        app.falak.player.data.SafeFile.write(file, json.encodeToString(RecordBook.serializer(), book), sync = false)
}

/** Series rules: which programmes in the guide they mean. */
object RecordRules {
    /** A programme name to compare: small letters and digits, single spaces. */
    fun key(title: String): String = title.lowercase()
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")

    /** The programme is the rule's show: the same name, or its name and then more ("The Show: Part 2"). */
    fun matches(rule: SeriesRule, prog: Program): Boolean {
        val want = key(rule.title)
        if (want.isEmpty()) return false
        val have = key(prog.title)
        return have == want || have.startsWith("$want ")
    }

    /**
     * Recordings the rules ask for that aren't planned yet: programmes coming up in the next
     * [horizonMs] (or under way) on each rule's channel, from [programsOf] (the guide).
     */
    fun plan(
        book: RecordBook,
        playlistId: String,
        programsOf: (String) -> List<Program>?,
        now: Long,
        horizonMs: Long,
        make: (SeriesRule, Program) -> Recording?,
    ): List<Recording> {
        val taken = HashSet<String>()
        book.recordings.forEach { taken += it.slot }
        taken += book.skipped
        val out = ArrayList<Recording>()
        for (rule in book.rules) {
            if (rule.playlistId != playlistId) continue
            val programs = programsOf(rule.channelId) ?: continue
            for (p in programs) {
                if (p.stop <= now || p.start > now + horizonMs) continue
                if (!matches(rule, p)) continue
                val slot = "${rule.channelId}|${p.start}"
                if (slot in taken) continue
                val r = make(rule, p) ?: continue
                taken += slot
                out += r
            }
        }
        return out
    }
}
