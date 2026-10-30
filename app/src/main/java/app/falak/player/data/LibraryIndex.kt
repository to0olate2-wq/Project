package app.falak.player.data

/**
 * Everything the screens need, pre-sorted once on a background thread so switching tabs
 * and groups never re-scans tens of thousands of items on the UI thread.
 */
class LibraryIndex(
    val all: List<Channel>,
    val byId: Map<String, Channel>,
    val live: List<Channel>,
    val movies: List<Channel>,
    val series: List<Channel>,
    private val liveGroups: Map<String, List<Channel>>,
    private val movieGroups: Map<String, List<Channel>>,
    private val seriesGroups: Map<String, List<Channel>>,
    val newMovies: List<Channel>,
    val newSeries: List<Channel>,
    /** Home's "Trending now": films and series (see [Trending]). */
    val trending: List<Channel>,
    /** The playlist as it came, before Tidy channel list (rebuilt from when the choice changes). */
    val raw: List<Channel> = all,
    /** Tidy channel list as it was built: names cleaned, duplicates merged. */
    val tidy: Pair<Boolean, Boolean> = false to false,
    /** Merged duplicates: a shown channel's versions, best quality first (see [Tidy]). */
    val versions: Map<String, List<Channel>> = emptyMap(),
    /** The order chosen (Arrange) this was built with; [all] stays in the playlist's own order. */
    val arrangement: Arrangement = Arrangement.NONE,
) {
    /** Search over every name, prepared on the first search (see [SearchIndex]). */
    val search: SearchIndex by lazy { SearchIndex(all) }

    fun ofKind(kind: Kind): List<Channel> = when (kind) {
        Kind.LIVE -> live
        Kind.MOVIE -> movies
        Kind.SERIES -> series
    }

    fun groupsOf(kind: Kind): Map<String, List<Channel>> = when (kind) {
        Kind.LIVE -> liveGroups
        Kind.MOVIE -> movieGroups
        Kind.SERIES -> seriesGroups
    }

    companion object {
        val EMPTY = build(emptyList())

        fun build(
            list: List<Channel>,
            cleanNames: Boolean = false,
            mergeDuplicates: Boolean = false,
            arrangement: Arrangement = Arrangement.NONE,
        ): LibraryIndex {
            val tidy = if (cleanNames || mergeDuplicates) Tidy.apply(list, cleanNames, mergeDuplicates) else null
            val shown = tidy?.shown ?: list
            val live = ArrayList<Channel>()
            val movies = ArrayList<Channel>()
            val series = ArrayList<Channel>()
            for (ch in shown) {
                when (ch.kind) {
                    Kind.LIVE -> live += ch
                    Kind.MOVIE -> movies += ch
                    Kind.SERIES -> series += ch
                }
            }
            val liveGroups = arrange(live.groupByTo(LinkedHashMap()) { it.group }, Kind.LIVE, arrangement)
            val movieGroups = arrange(movies.groupByTo(LinkedHashMap()) { it.group }, Kind.MOVIE, arrangement)
            val seriesGroups = arrange(series.groupByTo(LinkedHashMap()) { it.group }, Kind.SERIES, arrangement)
            // A kind with an order chosen lists everything in that order ("All channels" too).
            fun inOrder(kind: Kind, plain: List<Channel>, groups: Map<String, List<Channel>>) =
                if (arrangement.has(kind)) groups.values.flatten() else plain
            return LibraryIndex(
                all = shown,
                // Merged-away channels stay findable (favorites, recents, the guide point at them).
                byId = (tidy?.all ?: list).associateBy { it.id },
                live = inOrder(Kind.LIVE, live, liveGroups),
                movies = inOrder(Kind.MOVIE, movies, movieGroups),
                series = inOrder(Kind.SERIES, series, seriesGroups),
                liveGroups = liveGroups,
                movieGroups = movieGroups,
                seriesGroups = seriesGroups,
                newMovies = movies.filter { it.added > 0 }.sortedByDescending { it.added }.take(40),
                newSeries = series.filter { it.isSeriesFolder && it.added > 0 }.sortedByDescending { it.added }.take(40),
                trending = Trending.pick(movieGroups, seriesGroups, System.currentTimeMillis() / 1000),
                raw = list,
                tidy = cleanNames to mergeDuplicates,
                versions = tidy?.versions ?: emptyMap(),
                arrangement = arrangement,
            )
        }

        /** Groups, and the channels in each, in the order chosen; the rest after, as the playlist has them. */
        internal fun arrange(groups: LinkedHashMap<String, MutableList<Channel>>, kind: Kind, a: Arrangement): Map<String, List<Channel>> {
            if (!a.has(kind)) return groups
            val out = LinkedHashMap<String, List<Channel>>(groups.size * 2)
            for (g in a.groups[kind.name].orEmpty()) groups[g]?.let { out[g] = it }
            for ((g, l) in groups) if (g !in out) out[g] = l
            for (e in out.entries) {
                val ids = a.channels[Arrangement.channelsKey(kind, e.key)]
                if (ids.isNullOrEmpty()) continue
                val byId = e.value.associateBy { it.id }
                val first = ids.mapNotNull { byId[it] }.distinct()
                if (first.isEmpty()) continue
                val firstIds = first.mapTo(HashSet()) { it.id }
                e.setValue(first + e.value.filter { it.id !in firstIds })
            }
            return out
        }
    }
}
