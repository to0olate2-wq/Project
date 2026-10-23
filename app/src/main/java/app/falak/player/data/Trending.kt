package app.falak.player.data

/**
 * "Trending now" for Home, worked out on the device (nothing goes online): first what the
 * provider itself lists as popular (groups named "Trending", "Popular", "Top 10", "Most watched",
 * "الأكثر مشاهدة"…), then films and series that are new and well rated, where a high rating counts
 * for less the older the item is. Films and series take turns, so both are in the row.
 */
object Trending {
    /** Provider groups that list what's popular. */
    val GROUP = Regex(
        "(?i)(trend|popular|\\btop\\s*-?\\s*\\d{1,3}\\b|most\\s+(watched|viewed|popular)|الأكثر|ترند|تريند|رائج)",
    )

    /** Older than this, an item no longer counts as trending. */
    private const val WINDOW_DAYS = 120.0

    /** Days after which a rating counts for half. */
    private const val HALF_DAYS = 45.0

    /** Enough from each source to fill the row. */
    private const val PER_LIST = 60

    fun pick(
        movieGroups: Map<String, List<Channel>>,
        seriesGroups: Map<String, List<Channel>>,
        nowSeconds: Long,
        max: Int = 30,
    ): List<Channel> {
        fun usable(ch: Channel) = (ch.kind != Kind.SERIES || ch.isSeriesFolder) && !ADULT_GROUP.containsMatchIn(ch.group)

        // 1. The provider's own lists, a turn from each.
        val providerLists = (movieGroups.entries.toList() + seriesGroups.entries.toList())
            .filter { (group, _) -> GROUP.containsMatchIn(group) && !ADULT_GROUP.containsMatchIn(group) }
            .map { (_, list) -> list.asSequence().filter(::usable).take(PER_LIST).toList() }
        val provider = takeTurns(providerLists)

        // 2. New and well rated.
        fun rated(groups: Map<String, List<Channel>>): List<Channel> {
            val scored = ArrayList<Pair<Channel, Double>>()
            for ((group, list) in groups) {
                if (ADULT_GROUP.containsMatchIn(group)) continue
                for (ch in list) {
                    // (The group was checked above: only the series test is left.)
                    if (ch.added <= 0 || (ch.kind == Kind.SERIES && !ch.isSeriesFolder)) continue
                    val r = rating(ch.rating) ?: continue
                    val days = ((nowSeconds - ch.added) / 86_400.0).coerceAtLeast(0.0)
                    if (days > WINDOW_DAYS) continue
                    scored += ch to r / (1 + days / HALF_DAYS)
                }
            }
            scored.sortByDescending { it.second }
            return scored.take(PER_LIST).map { it.first }
        }
        val fresh = takeTurns(listOf(rated(movieGroups), rated(seriesGroups)))

        // The same title is often in several groups: show it once.
        val seen = HashSet<String>()
        return (provider + fresh).filter { seen.add(it.name.trim().lowercase()) }.take(max)
    }

    /** "7.4" → 7.4, "74" (out of 100) → 7.4; null when missing or zero. */
    fun rating(raw: String): Double? {
        val v = raw.trim().replace(',', '.').toDoubleOrNull() ?: return null
        return when {
            v <= 0 -> null
            v <= 10 -> v
            v <= 100 -> v / 10
            else -> null
        }
    }

    /** First of each list, then the second of each, and so on. */
    private fun <T> takeTurns(lists: List<List<T>>): List<T> {
        val out = ArrayList<T>()
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) for (l in lists) if (i < l.size) out += l[i]
        return out
    }
}
