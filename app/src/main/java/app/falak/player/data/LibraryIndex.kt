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
) {
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

        fun build(list: List<Channel>): LibraryIndex {
            val live = ArrayList<Channel>()
            val movies = ArrayList<Channel>()
            val series = ArrayList<Channel>()
            for (ch in list) {
                when (ch.kind) {
                    Kind.LIVE -> live += ch
                    Kind.MOVIE -> movies += ch
                    Kind.SERIES -> series += ch
                }
            }
            return LibraryIndex(
                all = list,
                byId = list.associateBy { it.id },
                live = live,
                movies = movies,
                series = series,
                liveGroups = live.groupByTo(LinkedHashMap()) { it.group },
                movieGroups = movies.groupByTo(LinkedHashMap()) { it.group },
                seriesGroups = series.groupByTo(LinkedHashMap()) { it.group },
                newMovies = movies.filter { it.added > 0 }.sortedByDescending { it.added }.take(40),
                newSeries = series.filter { it.isSeriesFolder && it.added > 0 }.sortedByDescending { it.added }.take(40),
            )
        }
    }
}
