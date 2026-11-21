package app.falak.player.data

/**
 * "What can I finish?" (8.42): of what you started (Continue watching), what ends within the time you
 * have. A film or an episode started: its time left. The next episode of a series, not started yet:
 * as long as the episodes of that series watched before (the provider rarely gives a length before
 * it plays); without any, it can't be told and is left out. Live channels have no end.
 */
object Finish {
    data class Pick(val id: String, val leftMs: Long, val guessed: Boolean)

    /** The times offered, in minutes. */
    val CHOICES = listOf(30, 45, 60, 90, 120, 180)

    /**
     * [progress] the app's watch progress ("<playlist[@profile]>|<id>" → Watch), [me] this playlist's
     * key part; what fits in [minutes], the one that fills the time best first.
     */
    fun fits(progress: Map<String, Watch>, me: String, minutes: Int): List<Pick> {
        val mine = progress.filter { it.key.startsWith("$me|") }
        // A series' episodes' length, from the ones whose length is known (the middle one).
        val lengths = HashMap<String, MutableList<Long>>()
        for (w in mine.values) {
            if (w.kind == Kind.SERIES && w.seriesId.isNotEmpty() && w.dur > 0) lengths.getOrPut(w.seriesId) { ArrayList() } += w.dur
        }
        val limit = minutes * 60_000L
        return mine.mapNotNull { (key, w) ->
            if (w.kind == Kind.LIVE || w.done) return@mapNotNull null
            val id = key.substringAfter('|')
            val pick = when {
                w.dur > 0 -> Pick(id, (w.dur - w.pos).coerceAtLeast(0), false)
                w.kind == Kind.SERIES -> lengths[w.seriesId]?.sorted()?.let { l -> Pick(id, l[l.size / 2], true) }
                else -> null
            } ?: return@mapNotNull null
            pick.takeIf { it.leftMs in MIN_LEFT_MS..limit }
        }.sortedByDescending { it.leftMs }
    }

    /** Less than this left: as good as finished (the credits). */
    private const val MIN_LEFT_MS = 60_000L

    /** "47 min" / "1 h 05 min". */
    fun length(ms: Long): String {
        val m = ((ms + 59_999) / 60_000).toInt()
        return if (m < 60) "$m min" else "${m / 60} h %02d min".format(m % 60)
    }
}
