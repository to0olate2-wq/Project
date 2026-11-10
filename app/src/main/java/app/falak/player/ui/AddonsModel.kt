package app.falak.player.ui

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
 * The Add-ons page (8.12, GitHub version only): the add-ons added in Settings → their lists of
 * films and series, a search across them, a title's details (seasons and episodes) and its links.
 * Kept while the page is away, like the lists.
 */
class AddonsModel(private val client: AddonClient, private val scope: CoroutineScope) {
    /** One list of one add-on. */
    class Row(val addon: Addon, val catalog: AddonCatalog) {
        val key: String get() = addon.url + "|" + catalog.type + "|" + catalog.id
        val title: String get() = "${catalog.name} · ${addon.name}"
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

    /** Opened from Settings: Back goes back there. */
    var fromSettings = false

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
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var metaJob: Job? = null
    private var streamJob: Job? = null

    fun rowsToShow(): List<Row> = addons.flatMap { a -> a.catalogs.filter { !it.needsExtra }.map { Row(a, it) } }

    val canSearch: Boolean get() = addons.any { a -> a.catalogs.any { it.searchable } }

    /** Reads the add-ons (again when the links changed, or [force]), then their lists. */
    fun load(links: List<String>, force: Boolean = false) {
        if (!force && links == loadedLinks) return
        loadedLinks = links
        loadJob?.cancel()
        rows.clear()
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
            addons = got.mapNotNull { it.second.getOrNull() }
            broken = got.filter { it.second.isFailure }.associate { (l, r) -> l to reason(r.exceptionOrNull()) }
            loading = false
            for (r in rowsToShow()) {
                launch {
                    try {
                        rows[r.key] = client.catalog(r.addon, r.catalog)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        rowErrors[r.key] = reason(e)
                    }
                }
            }
        }
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

    fun openItem(item: AddonItem) {
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
            if (seasons.isNotEmpty()) season = seasons.firstOrNull { it > 0 } ?: seasons.first()
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
    }

    /** The episode's links closed. */
    fun closeLinks() {
        streamJob?.cancel()
        streamsFor = null
        streams = null
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
            streams = client.streams(addons, type, id)
        }
    }

    /** What the player plays: a one-off (not in Recent, no resume), with the add-on's headers. */
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
    }

    companion object {
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
