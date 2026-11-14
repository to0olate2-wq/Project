package app.falak.player.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Where a film's or an episode's intro, recap and end credits are (8.25; the owner: "like Netflix,
 * press to skip intro, press to skip outro, and if I didn't press the show will continue"), from
 * introdb.app: a free list kept by its users, asked by IMDb number, no key. Titles from add-ons
 * have that number in their id ("tt0903747:1:2"); the playlist's own series don't, and keep the
 * learned intro (see PlayerActivity.learnIntro). Only the number, the season and the episode are
 * sent. Nothing is skipped by itself: the player shows a button while a part plays.
 */
object SkipSegments {
    enum class Kind(val label: String) { INTRO("Skip intro"), RECAP("Skip recap"), OUTRO("Skip credits") }

    /** One part, in milliseconds from the start. [end] is Long.MAX_VALUE when it runs to the end. */
    data class Part(val kind: Kind, val start: Long, val end: Long)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val IMDB = Regex("tt\\d{5,}")

    /** The link to ask for [videoId] ("tt…:season:episode", or "tt…" for a film); null when it has no IMDb number. */
    fun url(videoId: String): String? {
        val parts = videoId.split(':')
        val imdb = parts[0].takeIf { IMDB.matches(it) } ?: return null
        val season = parts.getOrNull(1)?.toIntOrNull()
        val episode = parts.getOrNull(2)?.toIntOrNull()
        return when {
            parts.size == 1 -> "https://api.introdb.app/segments?imdb_id=$imdb&is_movie=true"
            season != null && episode != null -> "https://api.introdb.app/segments?imdb_id=$imdb&season=$season&episode=$episode"
            else -> null
        }
    }

    /**
     * {"intro":{"start_sec":2.5,"end_sec":58,"start_ms":2500,"end_ms":58000,…},"recap":null,"outro":{…}}:
     * the parts it has (a missing one is null), each at least five seconds long.
     */
    fun parse(text: String): List<Part> {
        val o = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        fun ms(part: JsonObject, name: String): Long? {
            (part["${name}_ms"] as? JsonPrimitive)?.doubleOrNull?.let { return it.toLong() }
            val sec = part["${name}_sec"] as? JsonPrimitive ?: return null
            sec.doubleOrNull?.let { return (it * 1000).toLong() }
            // ("1:59:00", "00:58")
            val bits = sec.contentOrNull?.split(':')?.map { it.toDoubleOrNull() ?: return null } ?: return null
            return (bits.fold(0.0) { total, b -> total * 60 + b } * 1000).toLong()
        }
        val out = ArrayList<Part>()
        for ((key, kind) in listOf("recap" to Kind.RECAP, "intro" to Kind.INTRO, "outro" to Kind.OUTRO)) {
            val part = o[key] as? JsonObject ?: continue
            val start = ms(part, "start") ?: continue
            val end = ms(part, "end") ?: if (kind == Kind.OUTRO) Long.MAX_VALUE else continue
            if (start >= 0 && end - start >= 5_000) out += Part(kind, start, end)
        }
        return out
    }

    /**
     * The part to offer a button for at [pos]: from a second before it starts until four seconds
     * before it ends (a button for the last moments is no use). [dur]: the length, 0 if not known.
     * The list is written by its users and has slips (seen in its real answers, 8.26: a film's
     * "intro" two hours in, inside its credits): an intro or recap counts only in the first half,
     * credits only in the second.
     */
    fun offer(parts: List<Part>, pos: Long, dur: Long): Part? = parts.firstOrNull { p ->
        val end = if (dur > 0) minOf(p.end, dur) else p.end
        val placed = dur <= 0 || if (p.kind == Kind.OUTRO) p.start > dur / 2 else p.start < dur / 2
        placed && pos >= p.start - 1_000 && pos < end - 4_000
    }

    /** Where the button jumps to: the part's end, or just before the very end (so what follows an episode still happens). */
    fun target(p: Part, dur: Long): Long = if (dur > 0) minOf(p.end, dur - 500) else p.end
}
