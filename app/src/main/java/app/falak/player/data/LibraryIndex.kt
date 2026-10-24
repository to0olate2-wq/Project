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

        fun build(list: List<Channel>, cleanNames: Boolean = false, mergeDuplicates: Boolean = false): LibraryIndex {
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
            val movieGroups = movies.groupByTo(LinkedHashMap()) { it.group }
            val seriesGroups = series.groupByTo(LinkedHashMap()) { it.group }
            return LibraryIndex(
                all = shown,
                // Merged-away channels stay findable (favorites, recents, the guide point at them).
                byId = (tidy?.all ?: list).associateBy { it.id },
                live = live,
                movies = movies,
                series = series,
                liveGroups = live.groupByTo(LinkedHashMap()) { it.group },
                movieGroups = movieGroups,
                seriesGroups = seriesGroups,
                newMovies = movies.filter { it.added > 0 }.sortedByDescending { it.added }.take(40),
                newSeries = series.filter { it.isSeriesFolder && it.added > 0 }.sortedByDescending { it.added }.take(40),
                trending = Trending.pick(movieGroups, seriesGroups, System.currentTimeMillis() / 1000),
                raw = list,
                tidy = cleanNames to mergeDuplicates,
                versions = tidy?.versions ?: emptyMap(),
            )
        }
    }
}
