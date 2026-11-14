package app.falak.player.ui

import app.falak.player.torrent.Torrents
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.falak.player.data.Addon
import app.falak.player.data.AddonCatalog
import app.falak.player.data.AddonClient
import app.falak.player.data.AddonItem
import app.falak.player.data.AddonMeta
import app.falak.player.data.AddonStream
import app.falak.player.data.AddonStreams
import app.falak.player.data.AddonVideo
import app.falak.player.data.Addons
import app.falak.player.data.Channel
import app.falak.player.data.Kind
import app.falak.player.data.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/**
 * The Add-ons page (8.12, GitHub version only): the add-ons added (with the playlists) → their lists of
 * films and series, a search across them, a title's details (seasons and episodes) and its links.
 * Kept while the page is away, like the lists.
 */
class AddonsModel(private val client: AddonClient, private val scope: CoroutineScope) {
    /** The portal's pages (8.23): what a playlist has, without Live, and your own list. */
    enum class Tab(val label: String) { HOME("Home"), FILMS("Films"), SERIES("Series"), MINE("My list") }

    var tab by mutableStateOf(Tab.HOME)

    /**
     * The add-on whose portal is open (it was clicked on the Playlists page): links come from it
     * alone. Null: every add-on's links (the portal opened from the menu).
     */
    var only by mutableStateOf<String?>(null)

    /** The add-ons asked for links. */
    private fun linkAddons(): List<Addon> = addons.filter { it.url == only }.ifEmpty { addons }

    /** What the portal is called: the add-on's name, or "Add-ons" for all of them. */
    fun portalName(): String = only?.let { known[it]?.name } ?: "Add-ons"

    /** The lists of this page: films on Films, series on Series, everything on Home. */
    fun rowsFor(tab: Tab): List<Row> = rowsToShow().filter {
        when (tab) {
            Tab.FILMS -> it.catalog.type == "movie"
            Tab.SERIES -> it.catalog.type == "series"
            else -> true
        }
    }

    /** One list of one add-on. */
    class Row(val addon: Addon, val catalog: AddonCatalog) {
        val key: String get() = addon.url + "|" + catalog.type + "|" + catalog.id

        /** A list that needs a pick is shown with its first one (Cinemeta's "New": this year). */
        val firstPick: String? get() = if (catalog.genreRequired) catalog.genres.firstOrNull() else null

        /** "Popular · Films", "New · Series": an add-on names its film and series lists alike. */
        val name: String
            get() {
                val (kind, said) = when (catalog.type) {
                    "movie" -> "Films" to listOf("film", "movie")
                    "series" -> "Series" to listOf("series", "show")
                    else -> catalog.type.replaceFirstChar { it.uppercase() } to listOf(catalog.type)
                }
                // (Unless its name says so already: "Top movies".)
                return if (said.any { catalog.name.contains(it, ignoreCase = true) }) catalog.name else "${catalog.name} · $kind"
            }

        /** The row's heading: with its pick when it has to have one ("New · Films · 2026"). */
        val title: String get() = firstPick?.let { "$name · $it" } ?: name
    }

    var addons by mutableStateOf<List<Addon>>(emptyList())
        private set

    /** Links that couldn't be read (the add-on is down, or the link is wrong): link → why. */
    var broken by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var loading by mutableStateOf(false)
        private set

    /** Each list's titles (missing while loading). */
    val rows = mutableStateMapOf<String, List<AddonItem>>()
    val rowErrors = mutableStateMapOf<String, String>()

    var query by mutableStateOf("")

    /** Search results across the add-ons that can search (null: no search). */
    var results by mutableStateOf<List<AddonItem>?>(null)
        private set
    var searching by mutableStateOf(false)
        private set

    /** The page it was opened from when that isn't the lists (the Playlists page): Back goes back there. */
    var returnTo: Screen? = null

    /**
     * Add-ons read so far, by link: their names for the Playlists page and Settings. Those only read
     * the add-on itself ([readNames]), not its lists.
     */
    val known = mutableStateMapOf<String, Addon>()
    private val naming = mutableSetOf<String>()

    /** Reads the add-ons not known yet (just each add-on's own description, once). */
    fun readNames(links: List<String>) {
        for (l in links) {
            if (l in known || !naming.add(l)) continue
            scope.launch {
                try {
                    known[l] = client.manifest(l)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Shown by its address; the Add-ons page says why when it's opened.
                } finally {
                    naming.remove(l)
                }
            }
        }
    }

    // The title open.
    var open by mutableStateOf<AddonItem?>(null)
        private set
    var meta by mutableStateOf<AddonMeta?>(null)
        private set
    var metaLoading by mutableStateOf(false)
        private set
    var season by mutableStateOf<Int?>(null)

    /** The film or episode whose links are shown (its id), and its name. */
    var streamsFor by mutableStateOf<String?>(null)
        private set
    var streamsTitle by mutableStateOf("")
        private set
    var streams by mutableStateOf<AddonStreams?>(null)
        private set

    private var loadedLinks: List<String>? = null
    private var loadedAt = 0L
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var metaJob: Job? = null
    private var streamJob: Job? = null

    fun rowsToShow(): List<Row> = addons.flatMap { a -> a.catalogs.filter { !it.needsExtra }.map { Row(a, it) } }

    val canSearch: Boolean get() = addons.any { a -> a.catalogs.any { it.searchable } }

    /**
     * Reads the add-ons, then their lists: when the links changed, when asked to ([force], the
     * Reload chip) and, since 8.22, whenever the page is opened and the lists are older than
     * [FRESH_MS]. (They used to be read once for as long as the app stayed open, days on a TV:
     * the owner saw the same titles every time.) A refresh keeps what is shown until the new
     * answer is here.
     */
    fun load(links: List<String>, force: Boolean = false, now: Long = System.currentTimeMillis()) {
        val same = links == loadedLinks
        if (!force && same && now - loadedAt < FRESH_MS) return
        loadedLinks = links
        loadedAt = now
        loadJob?.cancel()
        if (!same) rows.clear()
        rowErrors.clear()
        if (links.isEmpty()) {
            addons = emptyList()
            broken = emptyMap()
            loading = false
            return
        }
        loadJob = scope.launch {
            loading = true
            val got = links.map { l -> async { l to runCatching { client.manifest(l) } } }.awaitAll()
            // (An add-on that doesn't answer a refresh stays as it was read before, with a line saying so.)
            addons = got.mapNotNull { (l, r) -> r.getOrNull() ?: known[l].takeIf { same } }
            for (a in addons) known[a.url] = a
            broken = got.filter { it.second.isFailure }.associate { (l, r) -> l to reason(r.exceptionOrNull()) }
            loading = false
            val show = rowsToShow()
            // (Lists an add-on no longer has.)
            val keys = show.mapTo(HashSet()) { it.key }
            rows.keys.filter { it !in keys }.forEach { rows.remove(it) }
            for (r in show) {
                launch {
                    try {
                        rows[r.key] = client.catalog(r.addon, r.catalog, genre = r.firstPick)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // (A refresh that fails leaves the list as it was.)
                        if (rows[r.key] == null) rowErrors[r.key] = reason(e)
                    }
                }
            }
        }
    }

    // ---------- one list opened whole ("See all"): its picks (genre, year) and page after page ----------

    /** The list open whole (null: the rows). */
    var discover by mutableStateOf<Row?>(null)
        private set

    /** What it is narrowed to (null: everything). */
    var discoverPick by mutableStateOf<String?>(null)
        private set
    var discoverItems by mutableStateOf<List<AddonItem>>(emptyList())
        private set
    var discoverLoading by mutableStateOf(false)
        private set

    /** Nothing more to ask for. */
    var discoverEnd by mutableStateOf(false)
        private set
    var discoverError by mutableStateOf<String?>(null)
        private set

    /** The grid's place, kept while a title of it is open. */
    var discoverGrid = androidx.compose.foundation.lazy.grid.LazyGridState()
        private set
    private var discoverJob: Job? = null

    fun openDiscover(r: Row) {
        discover = r
        // The row's titles are its first page already.
        showPick(r.firstPick, first = rows[r.key].orEmpty())
    }

    fun pickGenre(genre: String?) {
        val r = discover ?: return
        if (genre == discoverPick) return
        showPick(genre, first = if (genre == r.firstPick) rows[r.key].orEmpty() else emptyList())
    }

    private fun showPick(genre: String?, first: List<AddonItem>) {
        discoverJob?.cancel()
        discoverPick = genre
        discoverItems = first
        discoverLoading = false
        discoverEnd = false
        discoverError = null
        discoverGrid = androidx.compose.foundation.lazy.grid.LazyGridState()
        if (first.isEmpty()) loadMore()
    }

    /** The next page (asked for as the end of the grid comes near). */
    fun loadMore() {
        val r = discover ?: return
        if (discoverLoading || discoverEnd) return
        val pick = discoverPick
        val have = discoverItems
        discoverLoading = true
        discoverJob = scope.launch {
            try {
                val got = client.catalog(r.addon, r.catalog, skip = have.size, genre = pick)
                val known = have.mapTo(HashSet()) { it.type + "|" + it.id }
                val fresh = got.filter { known.add(it.type + "|" + it.id) }
                discoverItems = have + fresh
                // (An add-on without pages answers the same list again: nothing new is the end.)
                if (fresh.isEmpty() || !r.catalog.paged) discoverEnd = true
                discoverError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                discoverError = reason(e)
                discoverEnd = true
            }
            discoverLoading = false
        }
    }

    /** "Try again" after a page that failed. */
    fun retryMore() {
        discoverEnd = false
        discoverError = null
        loadMore()
    }

    fun closeDiscover() {
        discoverJob?.cancel()
        discover = null
        discoverItems = emptyList()
        discoverLoading = false
    }

    /** Checks a pasted link before it's added: the add-on, or why not. */
    suspend fun check(link: String): Result<Addon> {
        val base = Addons.normalize(link) ?: return Result.failure(IllegalArgumentException("That isn't an add-on link."))
        return try {
            val a = client.manifest(base)
            when {
                a.configurationRequired -> Result.failure(
                    IllegalStateException("This add-on has to be set up on its own web page first. Paste the link it gives you then."),
                )
                a.resources.none { it.name == "catalog" || it.name == "stream" || it.name == "meta" } ->
                    Result.failure(IllegalStateException("This add-on has no films, series or links to offer."))
                else -> Result.success(a)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Couldn't read the add-on (${reason(e)}). Check the link."))
        }
    }

    fun search(text: String) {
        query = text
        searchJob?.cancel()
        if (text.isBlank()) {
            results = null
            searching = false
            return
        }
        searchJob = scope.launch {
            searching = true
            val targets = addons.flatMap { a -> a.catalogs.filter { it.searchable }.map { a to it } }
            val found = targets.map { (a, c) -> async { runCatching { client.catalog(a, c, search = text) }.getOrDefault(emptyList()) } }
                .awaitAll().flatten().distinctBy { it.type + "|" + it.id }
            results = found
            searching = false
        }
    }

    /** Opens a title's page; a series on [onSeason] if it has it (the one left off in), else on its first. */
    fun openItem(item: AddonItem, onSeason: Int? = null) {
        open = item
        meta = null
        season = null
        streamsFor = null
        streams = null
        metaJob?.cancel()
        streamJob?.cancel()
        metaJob = scope.launch {
            metaLoading = true
            val m = runCatching { client.meta(addons, item) }.getOrNull()
            meta = m
            metaLoading = false
            val seasons = m?.videos?.map { it.season }?.distinct().orEmpty()
            if (seasons.isNotEmpty()) season = onSeason?.takeIf { it in seasons } ?: seasons.firstOrNull { it > 0 } ?: seasons.first()
        }
        // A film: its links at once (a series: once an episode is picked).
        if (item.type != "series") loadStreams(item.type, item.id, item.name)
    }

    fun closeItem() {
        metaJob?.cancel()
        streamJob?.cancel()
        open = null
        meta = null
        streams = null
        streamsFor = null
        stopTorrent()
    }

    /** The torrent link being got ready to play (8.16): its window is up while this is set. */
    var preparing by mutableStateOf<AddonStream?>(null)
        private set

    /** A torrent link was picked: see Torrents, whose state the window shows. */
    fun prepareTorrent(context: android.content.Context, s: AddonStream) {
        val source = s.torrent ?: return
        preparing = s
        Torrents.prepare(context, source)
    }

    @androidx.annotation.VisibleForTesting
    internal fun showPreparingForTest(s: AddonStream) {
        preparing = s
    }

    /** The link is ready and handed to the player (the film carries on arriving behind it). */
    fun torrentStarted() {
        preparing = null
    }

    /** Leaving the film, its links or the page (or Cancel in the window): nothing more is fetched, and it is deleted. */
    fun stopTorrent() {
        preparing = null
        if (Torrents.available) Torrents.stop()
    }

    /** The episode's links closed. */
    fun closeLinks() {
        streamJob?.cancel()
        streamsFor = null
        streams = null
        stopTorrent()
    }

    fun seasons(): List<Int> = meta?.videos?.map { it.season }?.distinct().orEmpty()

    fun episodes(): List<AddonVideo> = meta?.videos?.filter { it.season == season }.orEmpty()

    fun pickEpisode(v: AddonVideo) {
        val show = open ?: return
        loadStreams("series", v.id, episodeTitle(show.name, v))
    }

    private fun loadStreams(type: String, id: String, title: String) {
        streamJob?.cancel()
        streamsFor = id
        streamsTitle = title
        streams = null
        streamJob = scope.launch {
            streams = client.streams(linkAddons(), type, id)
        }
    }

    /** What is about to play, for the portal's Continue watching (see Addons.noteStart). */
    fun watchEntry(): app.falak.player.data.AddonWatch? {
        val item = open ?: return null
        val video = streamsFor ?: item.id
        val name = meta?.name?.ifBlank { null } ?: item.name
        return app.falak.player.data.AddonWatch(
            type = item.type,
            id = item.id,
            name = name,
            poster = meta?.poster?.ifBlank { null } ?: item.poster,
            videoId = video,
            // ("Bonanza · S1 E2 · Its name" without the series' own name.)
            label = if (item.type == "series") streamsTitle.removePrefix(item.name).trim(' ', '·') else "",
        )
    }

    /** What the player plays: its id says which film or episode it is (the place it is left at is kept by it), with the add-on's headers. */
    fun channelFor(s: AddonStream): Channel {
        val item = open
        val id = streamsFor ?: item?.id.orEmpty()
        return Channel(
            id = "addon:$id",
            name = streamsTitle.ifBlank { item?.name.orEmpty() },
            group = "Add-ons",
            url = s.url,
            logo = meta?.poster?.ifBlank { null } ?: item?.poster.orEmpty(),
            kind = Kind.MOVIE,
            userAgent = s.userAgent.ifBlank { Repository.DEFAULT_UA },
            referrer = s.referrer,
        )
    }

    /** Screenshot tests: a page filled in without any network. */
    internal fun showForTest(
        addons: List<Addon>,
        rows: Map<String, List<AddonItem>>,
        open: AddonItem? = null,
        meta: AddonMeta? = null,
        streams: AddonStreams? = null,
        streamsTitle: String = "",
    ) {
        loadJob?.cancel()
        this.addons = addons
        for (a in addons) known[a.url] = a
        this.broken = emptyMap()
        this.loading = false
        this.rows.clear()
        this.rows.putAll(rows)
        this.open = open
        this.meta = meta
        this.season = meta?.videos?.firstOrNull { it.season > 0 }?.season
        this.streams = streams
        this.streamsFor = streams?.let { open?.id }
        this.streamsTitle = streamsTitle
        loadedLinks = addons.map { it.url }
        loadedAt = System.currentTimeMillis()
    }

    /** Screenshot tests: a list opened whole, as far as it goes (nothing is asked for). */
    internal fun showDiscoverForTest(r: Row, pick: String?, items: List<AddonItem>) {
        discoverJob?.cancel()
        discover = r
        discoverPick = pick
        discoverItems = items
        discoverLoading = false
        discoverEnd = true
        discoverError = null
    }

    companion object {
        /** Lists older than this are read again when the page is opened. */
        const val FRESH_MS = 20 * 60_000L

        fun episodeTitle(show: String, v: AddonVideo): String {
            val n = if (v.season > 0) "S${v.season} E${v.episode}" else "Extra ${v.episode}"
            return listOfNotNull(show, n, v.title.ifBlank { null }).joinToString(" · ")
        }

        private fun reason(e: Throwable?): String = when (e) {
            is java.net.UnknownHostException -> "no such address"
            is java.net.SocketTimeoutException -> "no answer"
            is java.io.IOException -> e.message ?: "couldn't be reached"
            else -> e?.message ?: "couldn't be read"
        }
    }
}
