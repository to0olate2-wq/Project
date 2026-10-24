package app.falak.player.data

import kotlinx.serialization.Serializable

/** A programme about to start that the user wants to know about. */
@Serializable
data class ProgramAlert(
    val playlistId: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val start: Long,
    val stop: Long,
    /** The alert word that found it; null for a reminder set by hand. */
    val word: String? = null,
    /** How many other channels show the same programme at the same time. */
    val alsoOn: Int = 0,
) {
    val key: String get() = "$channelId|$start"
}

/**
 * What's coming up that the user wants to know about: reminders set by hand in the guide, and
 * programmes whose names contain one of the alert words ("Real Madrid", "الهلال"). Worked out from
 * the guide for the next day; only channels the user can see (no hidden or locked groups, the
 * kids' channels in kids mode).
 */
object Alerts {
    /** The reminder shows this long before the start… */
    const val LEAD_MS = 2 * 60_000L

    /** …and is still offered this long after it. */
    const val LATE_MS = 5 * 60_000L

    /** How far ahead alert words are looked for. */
    const val HORIZON_MS = 26 * 3_600_000L

    /** At most this many programmes found by alert words (the soonest). */
    const val MAX_FOUND = 60

    /**
     * Reminders and alert-word finds for [playlistId], soonest first. [channels] are the live
     * channels the user can see, in playlist order; the same programme on several of them comes
     * once, on a favorite channel if there is one ([favorites]), and says how many others show it.
     */
    fun upcoming(
        playlistId: String,
        reminders: List<Reminder>,
        words: List<String>,
        channels: List<Channel>,
        epg: Map<String, List<Program>>,
        now: Long,
        favorites: Set<String> = emptySet(),
        horizon: Long = now + HORIZON_MS,
    ): List<ProgramAlert> {
        val visible = HashMap<String, Channel>(channels.size * 2)
        for (ch in channels) visible[ch.id] = ch
        val out = ArrayList<ProgramAlert>()
        val taken = HashSet<String>()
        // Set by hand: kept as long as the channel is still there and can be seen.
        for (r in reminders) {
            if (r.playlistId != playlistId || r.start + LATE_MS <= now) continue
            val ch = visible[r.channelId] ?: continue
            out += ProgramAlert(playlistId, ch.id, ch.name, r.title, r.start, r.stop)
            taken += r.key
            taken += SearchText.normalize(r.title) + "|" + r.start
        }

        val queries = words.map { SearchQuery(it) to it.trim() }.filter { !it.first.isEmpty }
        if (queries.isNotEmpty()) {
            // Same title at the same time on several channels: one alert.
            class Found(val ch: Channel, val p: Program, val word: String, var count: Int)
            val found = LinkedHashMap<String, Found>()
            for (ch in channels) {
                val programs = epg[ch.epgKey] ?: continue
                for (p in programs) {
                    if (p.start + LATE_MS <= now || p.start >= horizon) continue
                    if ("${ch.id}|${p.start}" in taken) continue
                    val name = SearchText.normalize(p.title)
                    if (name.isEmpty()) continue
                    val padded = " $name "
                    var bare: String? = null
                    // Every word of the alert at the start of a word of the title (no guessing at typos).
                    val hit = queries.firstOrNull { (q, _) -> q.score(padded) { bare ?: SearchText.core(p.title).also { bare = it } } >= 400 }
                        ?: continue
                    val same = name + "|" + p.start
                    if (same in taken) continue
                    val prev = found[same]
                    if (prev == null) {
                        found[same] = Found(ch, p, hit.second, 1)
                    } else {
                        prev.count++
                        // A favorite channel is the better one to offer.
                        if (ch.id in favorites && prev.ch.id !in favorites) found[same] = Found(ch, p, hit.second, prev.count)
                    }
                }
            }
            found.values.sortedBy { it.p.start }.take(MAX_FOUND).forEach { f ->
                out += ProgramAlert(playlistId, f.ch.id, f.ch.name, f.p.title, f.p.start, f.p.stop, word = f.word, alsoOn = f.count - 1)
            }
        }
        out.sortWith(compareBy<ProgramAlert> { it.start }.thenBy { it.word != null }.thenBy { it.title })
        return out
    }

    /** "starts in 2 min", "starts now", "started 3 min ago". */
    fun whenLabel(start: Long, now: Long): String {
        val min = Math.round((start - now) / 60_000.0).toInt()
        return when {
            min > 0 -> "starts in $min min"
            min == 0 -> "starts now"
            else -> "started ${-min} min ago"
        }
    }
}
