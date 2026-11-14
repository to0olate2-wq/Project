package app.falak.player.data

import java.util.Calendar
import java.util.TimeZone

/**
 * The playlist's "Top 10", worked out on the device (nothing goes online): the best of what is
 * **new** (8.22; the owner: "based on recent movies, not all time high", his row never changed).
 * A title counts by how well it is rated and how lately the provider added it, the date counting
 * for more: a rating is worth half after 30 days and nothing after 120. A film whose name carries
 * an older year ("Heat (1995)") is not new, whenever the provider added it. A title in one of the
 * provider's own popular lists (groups named "Trending", "Popular", "Top 10", "Most watched",
 * "الأكثر مشاهدة"…) counts a little more, but it has to be new as well: until 8.22 those lists
 * came first in the provider's order, which is the same every day. They only fill the row now,
 * when the playlist has too little that is new. Films and series take turns, so both are in the row.
 */
object Trending {
    /** Provider groups that list what's popular. */
    val GROUP = Regex(
        "(?i)(trend|popular|\\btop\\s*-?\\s*\\d{1,3}\\b|most\\s+(watched|viewed|popular)|الأكثر|ترند|تريند|رائج)",
    )

    /** Added longer ago than this, a title no longer counts as new. */
    private const val WINDOW_DAYS = 120.0

    /** Days after which a rating counts for half. */
    private const val HALF_DAYS = 30.0

    /** What a title without a rating counts as (new films often have none yet): under a good one. */
    private const val NO_RATING = 5.5

    /** In one of the provider's popular lists. */
    private const val POPULAR_BONUS = 1.25

    /** No date from the provider (an M3U playlist, or a day it stamped everything): the age by the year in the name. */
    private const val THIS_YEAR_DAYS = 45.0
    private const val LAST_YEAR_DAYS = 100.0

    /** In a popular list, with no date and no year at all. */
    private const val POPULAR_DAYS = 60.0

    /** Enough from each source to fill the row. */
    private const val PER_LIST = 60

    /**
     * A day on which the provider "added" this share of everything (and this many titles) is not a
     * day of new titles: it moved or re-stamped its library, and those dates say nothing.
     */
    private const val BULK_SHARE = 0.15
    private const val BULK_MIN = 200

    private val YEAR_IN_BRACKETS = Regex("[(\\[]\\s*((?:19|20)\\d{2})\\s*[)\\]]")
    private val YEAR = Regex("(?<!\\d)((?:19|20)\\d{2})(?!\\d)")

    fun pick(
        movieGroups: Map<String, List<Channel>>,
        seriesGroups: Map<String, List<Channel>>,
        nowSeconds: Long,
        max: Int = 30,
    ): List<Channel> {
        val thisYear = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowSeconds * 1000 }.get(Calendar.YEAR)
        val fresh = takeTurns(listOf(recent(movieGroups, nowSeconds, thisYear, films = true), recent(seriesGroups, nowSeconds, thisYear, films = false)))

        // Too little that is new: the provider's own lists fill the row, a turn from each.
        fun usable(ch: Channel) = ch.kind != Kind.SERIES || ch.isSeriesFolder
        val providerLists = (movieGroups.entries.toList() + seriesGroups.entries.toList())
            .filter { (group, _) -> GROUP.containsMatchIn(group) && !ADULT_GROUP.containsMatchIn(group) }
            .map { (_, list) -> list.asSequence().filter(::usable).take(PER_LIST).toList() }

        // The same title is often in several groups: show it once.
        val seen = HashSet<String>()
        return (fresh + takeTurns(providerLists)).filter { seen.add(key(it)) }.take(max)
    }

    private fun key(ch: Channel) = ch.name.trim().lowercase()

    /** The new titles of one kind, the best first. */
    private fun recent(groups: Map<String, List<Channel>>, now: Long, thisYear: Int, films: Boolean): List<Channel> {
        // Days the provider stamped a big part of its library with, and the names in its popular lists.
        val perDay = HashMap<Long, Int>()
        var dated = 0
        val popular = HashSet<String>()
        for ((group, list) in groups) {
            if (ADULT_GROUP.containsMatchIn(group)) continue
            val listed = GROUP.containsMatchIn(group)
            for (ch in list) {
                if (ch.added > 0) {
                    perDay[ch.added / 86_400] = (perDay[ch.added / 86_400] ?: 0) + 1
                    dated++
                }
                if (listed) popular += key(ch)
            }
        }
        val bulk = maxOf(BULK_MIN.toDouble(), dated * BULK_SHARE)

        val scored = ArrayList<Pair<Channel, Double>>()
        for ((group, list) in groups) {
            if (ADULT_GROUP.containsMatchIn(group)) continue
            for (ch in list) {
                if (ch.kind == Kind.SERIES && !ch.isSeriesFolder) continue
                val listed = key(ch) in popular
                val dateKnown = ch.added > 0 && (perDay[ch.added / 86_400] ?: 0) < bulk
                var days = if (dateKnown) ((now - ch.added) / 86_400.0).coerceAtLeast(0.0) else -1.0
                if (dateKnown && days > WINDOW_DAYS) continue
                // (A series' year is when it began: a new season is still new.)
                val year = if (films) yearIn(ch.name, thisYear) else null
                if (year != null && year < thisYear - 1) continue
                if (!dateKnown) {
                    days = when {
                        year == thisYear -> THIS_YEAR_DAYS
                        year == thisYear - 1 -> LAST_YEAR_DAYS
                        listed && year == null -> POPULAR_DAYS
                        else -> continue
                    }
                }
                val score = (rating(ch.rating) ?: NO_RATING) * Math.pow(0.5, days / HALF_DAYS) * (if (listed) POPULAR_BONUS else 1.0)
                scored += ch to score
            }
        }
        // (The newer one first when two count the same.)
        scored.sortWith(compareByDescending<Pair<Channel, Double>> { it.second }.thenByDescending { it.first.added })
        return scored.take(PER_LIST).map { it.first }
    }

    /**
     * The year a film's name carries: "Heat (1995)", "Dune Part Two 2024", "Sinners [2025] 4K".
     * In brackets if there is one (the last), else the last one written; never one still to come
     * ("Blade Runner 2049" has none). Null when the name has no year.
     */
    fun yearIn(name: String, thisYear: Int): Int? {
        // (Most names have none: no need to look closer.)
        if (name.indexOf("20") < 0 && name.indexOf("19") < 0) return null
        val found = YEAR_IN_BRACKETS.findAll(name).lastOrNull() ?: YEAR.findAll(name).lastOrNull { it.groupValues[1].toInt() <= thisYear + 1 }
        return found?.groupValues?.get(1)?.toInt()?.takeIf { it <= thisYear + 1 }
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
