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
    /** Each kind's groups and list in the playlist's own order, so a new order is applied without building again. */
    private val plainGroups: Map<Kind, Map<String, List<Channel>>> = emptyMap(),
    private val plainLists: Map<Kind, List<Channel>> = emptyMap(),
    /** Shared with the copies [rearranged] makes (the names to search don't change). */
    private val searchHolder: Lazy<SearchIndex> = lazy { SearchIndex(all) },
) {
    /** Search over every name, prepared on the first search (see [SearchIndex]). */
    val search: SearchIndex get() = searchHolder.value

    /**
     * The same library in order [a] (Arrange): only the groups with an order chosen are sorted
     * again, everything else is reused. Quick enough to do at once on every press.
     */
    fun rearranged(a: Arrangement): LibraryIndex {
        if (a == arrangement) return this
        fun groups(kind: Kind) = arrange(plainGroups[kind].orEmpty(), kind, a)
        fun list(kind: Kind, groups: Map<String, List<Channel>>) =
            if (a.has(kind)) groups.values.flatten() else plainLists[kind].orEmpty()
        val lg = groups(Kind.LIVE)
        val mg = groups(Kind.MOVIE)
        val sg = groups(Kind.SERIES)
        return LibraryIndex(
            all = all, byId = byId,
            live = list(Kind.LIVE, lg), movies = list(Kind.MOVIE, mg), series = list(Kind.SERIES, sg),
            liveGroups = lg, movieGroups = mg, seriesGroups = sg,
            newMovies = newMovies, newSeries = newSeries, trending = trending,
            raw = raw, tidy = tidy, versions = versions,
            arrangement = a, plainGroups = plainGroups, plainLists = plainLists, searchHolder = searchHolder,
        )
    }

    /** Group [group] of [kind] in the playlist's own order (before Arrange). */
    fun plainGroup(kind: Kind, group: String): List<Channel> = plainGroups[kind]?.get(group).orEmpty()

    /** The groups of [kind] in the playlist's own order (before Arrange). */
    fun plainGroupNames(kind: Kind): Collection<String> = plainGroups[kind]?.keys.orEmpty()

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
            val plainLive: Map<String, List<Channel>> = live.groupByTo(LinkedHashMap()) { it.group }
            val plainMovies: Map<String, List<Channel>> = movies.groupByTo(LinkedHashMap()) { it.group }
            val plainSeries: Map<String, List<Channel>> = series.groupByTo(LinkedHashMap()) { it.group }
            val liveGroups = arrange(plainLive, Kind.LIVE, arrangement)
            val movieGroups = arrange(plainMovies, Kind.MOVIE, arrangement)
            val seriesGroups = arrange(plainSeries, Kind.SERIES, arrangement)
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
                plainGroups = mapOf(Kind.LIVE to plainLive, Kind.MOVIE to plainMovies, Kind.SERIES to plainSeries),
                plainLists = mapOf(Kind.LIVE to live, Kind.MOVIE to movies, Kind.SERIES to series),
            )
        }

        /** Groups, and the channels in each, in the order chosen; the rest after, as the playlist has them. */
        internal fun arrange(groups: Map<String, List<Channel>>, kind: Kind, a: Arrangement): Map<String, List<Channel>> {
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
