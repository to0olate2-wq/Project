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
    /** The names and logos chosen by hand this was built with (channel id -> the change). */
    val edits: Map<String, ChannelEdit> = emptyMap(),
    /** Films and series listed more than once (8.34, see [Versions]): id → what its copies share. */
    val titleKeys: Map<String, String> = emptyMap(),
) {
    private val copyIds: Map<String, List<String>> by lazy { titleKeys.entries.groupBy({ it.value }, { it.key }) }

    /** The other copies of a film or series (itself included, the playlist's order); empty when it has none. */
    fun copiesOf(ch: Channel): List<Channel> =
        titleKeys[ch.id]?.let { k -> copyIds[k].orEmpty().mapNotNull { byId[it] } }.orEmpty()

    /** Collections of films found in their names (see [Collections]), made the first time they're asked for. */
    val collections: List<Collections.Collection> by lazy { runCatching { Collections.find(movies) }.getOrDefault(emptyList()) }

    private val collectionOf: Map<String, Collections.Collection> by lazy {
        val out = HashMap<String, Collections.Collection>()
        for (c in collections) for (f in c.films) out.putIfAbsent(f.id, c)
        out
    }

    /** The collection a film is in (any of its copies). */
    fun collectionFor(ch: Channel): Collections.Collection? =
        collectionOf[ch.id] ?: copiesOf(ch).firstNotNullOfOrNull { collectionOf[it.id] }

    /** Search over every name, prepared on the first search (see [SearchIndex]). */
    val search: SearchIndex get() = searchHolder.value

    /**
     * The same library in order [a] (Arrange): only the groups with an order chosen are sorted
     * again, everything else is reused. Quick enough to do at once on every press.
     */
    fun rearranged(a: Arrangement): LibraryIndex {
        if (a == arrangement) return this
        fun groups(kind: Kind) = arrange(plainGroups[kind].orEmpty(), kind, a)
        fun list(kind: Kind, groups: Map<String, List<Channel>>) = oneEach(
            kind,
            if (a.has(kind)) groups.values.flatten() else plainLists[kind].orEmpty(),
            titleKeys,
        )
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
            edits = edits, titleKeys = titleKeys,
        )
    }

    /** Built with `later` (8.57): the copies and the Top 10 aren't in it yet ([withCopies]). */
    val waiting: Boolean get() = titleKeys.isEmpty() && trending.isEmpty() && (movies.isNotEmpty() || series.isNotEmpty())

    /** Films and series listed more than once (see [Versions.keys]): the slow part [withCopies] needs. */
    fun findCopies(): Map<String, String> =
        runCatching { Versions.keys(plainLists[Kind.MOVIE].orEmpty() + plainLists[Kind.SERIES].orEmpty()) }.getOrDefault(emptyMap())

    /**
     * What [findCopies] answers depends on: every film's and series' id, name and kind, and the year (a
     * name's year is read against it). Kept with the answer, so the next opening can use it as it is.
     */
    fun copiesPrint(thisYear: Int = TitleMatch.currentYear()): Long {
        var h = 1125899906842597L * COPIES_VERSION + thisYear
        for (kind in arrayOf(Kind.MOVIE, Kind.SERIES)) {
            for (ch in plainLists[kind].orEmpty()) {
                h = 31 * h + ch.id.hashCode()
                h = 31 * h + ch.name.hashCode()
                h = 31 * h + (if (ch.url.isEmpty()) 0 else 1) + (if (ch.isSeriesFolder) 2 else 0)
            }
        }
        return h
    }

    /** This library with [keys] from [findCopies] and the Top 10 worked out (the second half of a `later` build). */
    fun withCopies(keys: Map<String, String>): LibraryIndex {
        val mg = movieGroups
        val sg = seriesGroups
        fun list(kind: Kind, groups: Map<String, List<Channel>>) = oneEach(
            kind,
            if (arrangement.has(kind)) groups.values.flatten() else plainLists[kind].orEmpty(),
            keys,
        )
        val films = plainLists[Kind.MOVIE].orEmpty()
        val shows = plainLists[Kind.SERIES].orEmpty()
        return LibraryIndex(
            all = all, byId = byId,
            live = live, movies = list(Kind.MOVIE, mg), series = list(Kind.SERIES, sg),
            liveGroups = liveGroups, movieGroups = mg, seriesGroups = sg,
            newMovies = Versions.oneEach(films.filter { it.added > 0 }.sortedByDescending { it.added }, keys).take(40),
            newSeries = Versions.oneEach(shows.filter { it.isSeriesFolder && it.added > 0 }.sortedByDescending { it.added }, keys).take(40),
            trending = Versions.oneEach(Trending.pick(mg, sg, System.currentTimeMillis() / 1000), keys),
            raw = raw, tidy = tidy, versions = versions,
            arrangement = arrangement, plainGroups = plainGroups, plainLists = plainLists, searchHolder = searchHolder,
            edits = edits, titleKeys = keys,
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
        /** Bump when [Versions.keys] changes what it answers: kept answers are then worked out again. */
        private const val COPIES_VERSION = 1

        val EMPTY = build(emptyList())

        /** Lists of everything show each film and series once (live channels as they are). */
        private fun oneEach(kind: Kind, list: List<Channel>, keys: Map<String, String>) =
            if (kind == Kind.LIVE) list else Versions.oneEach(list, keys)

        fun build(
            list: List<Channel>,
            cleanNames: Boolean = false,
            mergeDuplicates: Boolean = false,
            arrangement: Arrangement = Arrangement.NONE,
            edits: Map<String, ChannelEdit> = emptyMap(),
            /** (8.57) How long each step took, for timing a big playlist: step name, milliseconds. */
            took: ((String, Long) -> Unit)? = null,
            /**
             * (8.57, a big playlist took long to open) Leave the slow parts for [withCopies]: finding the
             * films and series listed more than once (it cleans every name: 4–27 s for 48,000 on the
             * emulators) and the Top 10. The lists show at once; each title once and the Top 10 a moment later.
             */
            later: Boolean = false,
        ): LibraryIndex {
            var mark = System.nanoTime()
            fun lap(step: String) {
                val t = System.nanoTime()
                took?.invoke(step, (t - mark) / 1_000_000)
                mark = t
            }
            val tidy = if (cleanNames || mergeDuplicates) Tidy.apply(list, cleanNames, mergeDuplicates) else null
            lap("tidy")
            // Names and logos chosen by hand go over whatever Tidy made of them.
            fun edit(l: List<Channel>): List<Channel> =
                if (edits.isEmpty()) l else l.map { ch -> edits[ch.id]?.let { ch.edited(it) } ?: ch }
            val shown = edit(tidy?.shown ?: list)
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
            lap("groups")
            // A kind with an order chosen lists everything in that order ("All channels" too).
            // Films and series the provider lists more than once: one of each in the lists of everything.
            val keys = if (later) emptyMap() else runCatching { Versions.keys(movies + series) }.getOrDefault(emptyMap())
            lap("copies")
            fun inOrder(kind: Kind, plain: List<Channel>, groups: Map<String, List<Channel>>) =
                oneEach(kind, if (arrangement.has(kind)) groups.values.flatten() else plain, keys)
            val byId = (if (tidy == null) shown else edit(tidy.all)).associateBy { it.id }
            val liveAll = inOrder(Kind.LIVE, live, liveGroups)
            val moviesAll = inOrder(Kind.MOVIE, movies, movieGroups)
            val seriesAll = inOrder(Kind.SERIES, series, seriesGroups)
            val newMovies = Versions.oneEach(movies.filter { it.added > 0 }.sortedByDescending { it.added }, keys).take(40)
            val newSeries = Versions.oneEach(series.filter { it.isSeriesFolder && it.added > 0 }.sortedByDescending { it.added }, keys).take(40)
            lap("lists")
            val trending = if (later) emptyList() else Versions.oneEach(Trending.pick(movieGroups, seriesGroups, System.currentTimeMillis() / 1000), keys)
            lap("top 10")
            return LibraryIndex(
                all = shown,
                // Merged-away channels stay findable (favorites, recents, the guide point at them).
                byId = byId,
                live = liveAll,
                movies = moviesAll,
                series = seriesAll,
                liveGroups = liveGroups,
                movieGroups = movieGroups,
                seriesGroups = seriesGroups,
                newMovies = newMovies,
                newSeries = newSeries,
                trending = trending,
                raw = list,
                tidy = cleanNames to mergeDuplicates,
                versions = tidy?.versions ?: emptyMap(),
                arrangement = arrangement,
                edits = edits,
                titleKeys = keys,
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
