package app.falak.player.data

/**
 * Checks a guide source against another one for the channels both cover. The app can't see the
 * picture, but two independent guides for the same channel should list the same programmes at the
 * same times. When most shared channels line up only after moving one source by the same amount
 * (say, every show 1 hour later), that source was published in the wrong time zone and can be fixed.
 */
object GuideCheck {
    /** Offsets tried, in minutes (time-zone mistakes are whole or half hours). */
    private val OFFSETS = (-6..6).flatMap { h -> listOf(h * 60, h * 60 + 30) }.filter { it in -360..360 }.distinct()

    /** What one channel says about a source: the offset that lines it up with the reference, or null if unclear. */
    fun channelOffset(reference: List<Program>, other: List<Program>, now: Long): Int? {
        if (reference.isEmpty() || other.isEmpty()) return null
        val from = now - 6 * 3_600_000L
        val to = now + 24 * 3_600_000L
        // Reference programmes by title; only titles that say something (not "News" alone on every hour).
        val byTitle = HashMap<String, MutableList<Long>>()
        for (p in reference) {
            if (p.start !in from..to) continue
            val t = norm(p.title)
            if (t.length >= 4) byTitle.getOrPut(t) { ArrayList(2) } += p.start
        }
        if (byTitle.isEmpty()) return null
        val votes = HashMap<Int, Int>()
        for (p in other) {
            if (p.start !in from..to) continue
            val starts = byTitle[norm(p.title)] ?: continue
            for (s in starts) {
                val diffMin = ((s - p.start) / 60_000L).toInt()
                // Allow a few minutes either way (sources round start times differently).
                val near = OFFSETS.minByOrNull { kotlin.math.abs(it - diffMin) } ?: continue
                if (kotlin.math.abs(near - diffMin) <= 5) votes[near] = (votes[near] ?: 0) + 1
            }
        }
        val best = votes.maxByOrNull { it.value } ?: return null
        // At least two programmes agreeing, and clearly ahead of any other offset.
        val second = votes.filterKeys { it != best.key }.values.maxOrNull() ?: 0
        return if (best.value >= 2 && best.value > second) best.key else null
    }

    /** Summary over all shared channels: how many line up as they are, and a likely fix if any. */
    class Tally {
        val byOffset = HashMap<Int, Int>()
        var compared = 0
            private set

        fun add(offset: Int?) {
            if (offset == null) return
            compared++
            byOffset[offset] = (byOffset[offset] ?: 0) + 1
        }

        val agree: Int get() = byOffset[0] ?: 0

        /** The fix to suggest (minutes to add to this source), or 0 when it already lines up. */
        val suggestedShift: Int
            get() {
                val best = byOffset.filterKeys { it != 0 }.maxByOrNull { it.value } ?: return 0
                return if (best.value >= 3 && best.value > agree && best.value * 2 >= compared) best.key else 0
            }

        val shiftVotes: Int get() = byOffset[suggestedShift] ?: 0
    }

    private fun norm(title: String): String {
        val sb = StringBuilder(title.length)
        for (c in title) if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
        return if (sb.length > 32) sb.substring(0, 32) else sb.toString()
    }
}
