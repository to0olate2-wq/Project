package app.falak.player.ui

import app.falak.player.torrent.Torrents
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.falak.player.data.Addons
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.falak.player.data.Addon
import app.falak.player.data.AddonItem
import app.falak.player.data.AddonStream
import app.falak.player.data.AddonStreams
import app.falak.player.data.AddonVideo
import app.falak.player.data.AppState
import app.falak.player.data.Channel
import app.falak.player.data.Kind
import kotlinx.coroutines.launch

/*
 * The Add-ons page (8.12, GitHub version only): Stremio-style add-ons the user added. Lists of
 * films and series from each, a search across them, a title's details, and the links that play
 * in the app (web links; torrent links are counted, not shown).
 */

@Composable
fun AddonsScreen(vm: LibraryViewModel, state: AppState, onPlay: (List<Channel>, Channel) -> Unit) {
    val m = vm.addons
    val links = state.settings.addons
    androidx.compose.runtime.LaunchedEffect(links) { m.load(links) }
    // Back in the app with the page still up (the player, another app, the TV woken up): lists
    // that have grown old are read again (load does nothing while they are fresh).
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle, links) {
        val watcher = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) m.load(links)
        }
        lifecycle.addObserver(watcher)
        onDispose { lifecycle.removeObserver(watcher) }
    }
    var adding by remember { mutableStateOf(false) }
    val leave = {
        val back = m.returnTo
        m.returnTo = null
        if (back != null) vm.screen = back else vm.closePage()
    }
    val open = m.open
    BackHandler {
        when {
            m.open != null -> m.closeItem()
            m.discover != null -> m.closeDiscover()
            else -> leave()
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val play = { s: AddonStream ->
        if (s.torrent != null && s.url.isEmpty()) {
            // A torrent link: got ready first (its window says how it is going), then played like any link.
            m.prepareTorrent(context, s)
        } else {
            val ch = m.channelFor(s)
            onPlay(listOf(ch), ch)
        }
    }
    // Leaving the Add-ons page for another one: a torrent still arriving is ended (not when the
    // screen is only being rebuilt, for example when the phone is turned).
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { if (vm.screen != Screen.ADDONS) m.stopTorrent() }
    }
    m.preparing?.let { s ->
        TorrentWindow(
            s,
            onReady = { url ->
                m.torrentStarted()
                val ch = m.channelFor(s.copy(url = url))
                onPlay(listOf(ch), ch)
            },
            onCancel = { m.stopTorrent() },
        )
    }
    val whole = m.discover
    if (open != null) {
        AddonDetails(m, open, onBack = { m.closeItem() }, onPlay = play)
    } else if (whole != null) {
        AddonDiscover(m, whole)
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = pageMargin())) {
            PageHeader(
                "Add-ons",
                onBack = leave,
                subtitle = when {
                    links.isEmpty() -> null
                    m.loading -> "Reading your add-ons…"
                    else -> m.addons.joinToString(" · ") { it.name }.ifBlank { null }
                },
            )
            // Adding and removing add-ons is with the playlists (the Playlists page, Settings → Playlists).
            if (links.isEmpty()) {
                AddonsIntro { adding = true }
            } else {
                ChipFlow(Modifier.padding(bottom = 8.dp)) {
                    TagChip("Reload", false) { m.load(links, force = true) }
                }
                // Add-ons that only find links (Torrentio and the like) need lists to pick films from:
                // the standard ones are a tap away (still the owner's choice: nothing is added by itself).
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                var listsNote by remember { mutableStateOf<String?>(null) }
                AddonsBrowse(
                    m,
                    listsNote = listsNote,
                    onAddLists = {
                        listsNote = "Adding…"
                        scope.launch {
                            m.check(Addons.STANDARD_LISTS).fold(
                                onSuccess = { a ->
                                    listsNote = null
                                    vm.addAddon(a)
                                },
                                onFailure = { e -> listsNote = "Couldn't add them: ${e.message ?: "no answer"}" },
                            )
                        }
                    },
                )
            }
        }
    }
    if (adding) {
        AddAddonDialog(
            m,
            already = links,
            onAdd = { a ->
                vm.addAddon(a)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
}

/** Adds an add-on checked by [AddAddonDialog] (it reaches linked devices with the settings). */
internal fun LibraryViewModel.addAddon(a: Addon) {
    addons.known[a.url] = a
    updateSettings { s -> if (a.url in s.addons) s else s.copy(addons = s.addons + a.url) }
}

internal fun LibraryViewModel.removeAddon(link: String) {
    updateSettings { s -> s.copy(addons = s.addons - link) }
}

/** What an add-on offers, for its line on the Playlists page and in Settings ("Films · Series"). */
internal fun addonLine(a: Addon?, link: String, broken: String? = null): String {
    if (broken != null) return "Can't be reached: $broken"
    if (a == null) return hostOf(link)
    // Nothing to browse in it: it finds links for the films of your list add-ons.
    if (a.catalogs.isEmpty()) return "Links only · needs a list add-on to pick films from"
    val kinds = a.types.map {
        when (it.lowercase()) {
            "movie" -> "Films"
            "series" -> "Series"
            "tv" -> "TV"
            "channel" -> "Channels"
            else -> it.replaceFirstChar { c -> c.uppercase() }
        }
    }.distinct()
    return if (kinds.isEmpty()) hostOf(link) else kinds.joinToString(" · ")
}

/** No add-on yet: what they are, and what plays. */
@Composable
private fun AddonsIntro(onAdd: () -> Unit) {
    val tv = isTv()
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Text("Films and series from add-ons", fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = Paper)
        Spacer(Modifier.size(10.dp))
        listOf(
            "Add-ons work like Stremio's: each one brings lists of films and series, and links to play them.",
            "Add one with its link (it ends in manifest.json). The app comes with none: you choose your own.",
            "Some add-ons bring the lists (Cinemeta is the standard one); others, like Torrentio, only find links for the films in those lists. You need one of each.",
            if (Torrents.available) "Web links and torrent links both play in the app." else "Only web links play in this version of the app: torrent links don't.",
        ).plus(if (tv) listOf("Easier on your phone: add it there, and it reaches this TV with Wi-Fi linking.") else emptyList())
            .forEach { line ->
                Text("•  $line", fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp, color = Muted, modifier = Modifier.padding(bottom = 6.dp))
            }
        Spacer(Modifier.size(14.dp))
        PaperButton("Add an add-on", Icons.Filled.Add, onClick = onAdd)
        Text(
            "Later: on the Playlists page, or Settings → Playlists.",
            fontFamily = Sans,
            fontSize = 12.sp,
            color = Faint,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** The add-ons' lists, and a search across those that can search. */
@Composable
private fun AddonsBrowse(m: AddonsModel, listsNote: String? = null, onAddLists: () -> Unit = {}) {
    var text by rememberSaveable { mutableStateOf(m.query) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        if (m.canSearch) {
            item(key = "search") {
                OutlinedTextField(
                    text,
                    { text = it.take(80) },
                    singleLine = true,
                    placeholder = { Text("Search your add-ons") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = if (text.isNotEmpty()) {
                        {
                            IconButton(onClick = {
                                text = ""
                                m.search("")
                            }) { Icon(Icons.Outlined.Close, contentDescription = "Clear") }
                        }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { m.search(text) }),
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp),
                )
            }
        }
        if (m.loading) item(key = "loading") { Note("Reading your add-ons…") }
        m.broken.forEach { (link, why) ->
            item(key = "broken:$link") { Note("${hostOf(link)}: $why", error = true) }
        }
        val results = m.results
        when {
            m.searching -> item(key = "searching") { Note("Searching…") }
            results != null -> item(key = "results") {
                if (results.isEmpty()) {
                    Note("Nothing found for “${m.query}”.")
                } else {
                    Shelf("Results for “${m.query}”") {
                        items(results, key = { "r:" + it.type + it.id }) { item -> AddonPoster(item) { m.openItem(item) } }
                    }
                }
            }
        }
        val rows = m.rowsToShow()
        // (Whose list it is, when there are several add-ons with lists.)
        val several = rows.distinctBy { it.addon.url }.size > 1
        for (r in rows) {
            item(key = r.key) {
                val list = m.rows[r.key]
                val err = m.rowErrors[r.key]
                when {
                    err != null -> RowNote(r.title, "Couldn't load it ($err).")
                    list == null -> RowNote(r.title, "Loading…")
                    list.isEmpty() -> RowNote(r.title, "Nothing here.")
                    // "See all": the whole list, page after page, with its genres (or years) to pick from.
                    else -> Shelf(r.title, onSeeAll = { m.openDiscover(r) }, key = r.key, count = r.addon.name.takeIf { several }) {
                        items(list, key = { it.type + "|" + it.id }) { item -> AddonPoster(item) { m.openItem(item) } }
                    }
                }
            }
        }
        if (!m.loading && m.addons.isNotEmpty() && rows.isEmpty()) {
            item(key = "norows") {
                if (m.canSearch) {
                    Note("Your add-ons have no lists to show: search them above.")
                } else {
                    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        Text("No films to pick from yet", fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = Paper)
                        Spacer(Modifier.size(6.dp))
                        Text(
                            "Your add-ons only find links: they have no lists of films and series of their own. " +
                                "Add a list add-on, pick a film from it, and their links show on the film's page.",
                            fontFamily = Sans,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = Muted,
                        )
                        Spacer(Modifier.size(14.dp))
                        PaperButton("Add the standard lists (Cinemeta)", Icons.Filled.Add, onClick = onAddLists)
                        if (listsNote != null) {
                            Spacer(Modifier.size(8.dp))
                            Text(listsNote, fontFamily = Sans, fontSize = 13.sp, color = Muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddonPoster(item: AddonItem, wide: Boolean = false, onClick: () -> Unit) {
    PosterCard(item.asChannel(), null, onClick = onClick, width = if (wide) null else if (isTv()) 132.dp else 112.dp)
}

/**
 * One list opened whole (8.22; the owner: "like we are in the Stremio app"; this is its Discover):
 * the picks the add-on offers for it (genres, or years for a "New" list) as chips, and the titles
 * as a grid that asks for the next page as its end comes near.
 */
@Composable
private fun AddonDiscover(m: AddonsModel, r: AddonsModel.Row) {
    val titles = m.discoverItems
    val grid = m.discoverGrid
    // The end is near: the next page (again after each page, while the end is still on screen).
    androidx.compose.runtime.LaunchedEffect(grid, r.key, m.discoverPick) {
        androidx.compose.runtime.snapshotFlow {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            (last >= m.discoverItems.size - 9) to m.discoverItems.size
        }.collect { (near, size) -> if (near && size > 0) m.loadMore() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = pageMargin())) {
        PageHeader(r.name, onBack = { m.closeDiscover() }, subtitle = r.addon.name)
        val picks = r.catalog.genres
        if (picks.isNotEmpty()) {
            androidx.compose.foundation.lazy.LazyRow(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
            ) {
                if (!r.catalog.genreRequired) item(key = "__all") { TagChip("All", m.discoverPick == null) { m.pickGenre(null) } }
                items(picks, key = { it }) { g -> TagChip(g, m.discoverPick == g) { m.pickGenre(g) } }
            }
        }
        val err = m.discoverError
        when {
            titles.isEmpty() && m.discoverLoading -> Note("Loading…")
            titles.isEmpty() && err != null -> {
                Note("Couldn't load it ($err).", error = true)
                TextButton(onClick = { m.retryMore() }) { Text("Try again") }
            }
            titles.isEmpty() -> Note("Nothing here.")
            else -> androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(if (isTv()) 132.dp else 104.dp),
                state = grid,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(titles.size, key = { titles[it].type + "|" + titles[it].id }) { i ->
                    val item = titles[i]
                    AddonPoster(item, wide = true) { m.openItem(item) }
                }
                if (m.discoverLoading || err != null) {
                    item(key = "__more", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        if (err != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Note("Couldn't load more ($err).", error = true)
                                TextButton(onClick = { m.retryMore() }) { Text("Try again") }
                            }
                        } else {
                            Note("Loading more…")
                        }
                    }
                }
            }
        }
    }
}

/** How a film or series of an add-on is drawn by the usual poster card. */
private fun AddonItem.asChannel() = Channel(
    id = "addon:$type:$id",
    name = name,
    group = "",
    url = "",
    logo = poster,
    kind = if (type == "series") Kind.SERIES else Kind.MOVIE,
    rating = rating,
)

@Composable
private fun RowNote(title: String, note: String) {
    Column(Modifier.fillMaxWidth().padding(top = 22.dp)) {
        SectionHeader(title)
        Note(note)
    }
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text,
        fontFamily = Sans,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = if (error) MaterialTheme.colorScheme.error else Muted,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

internal fun hostOf(link: String) = link.substringAfter("://").substringBefore('/')

/** A film or series: about it, its seasons and episodes, and its links. */
@Composable
private fun AddonDetails(m: AddonsModel, item: AddonItem, onBack: () -> Unit, onPlay: (AddonStream) -> Unit) {
    val meta = m.meta
    val tv = isTv()
    val series = item.type == "series"
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = pageMargin()), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "head") {
            PageHeader(
                meta?.name?.ifBlank { null } ?: item.name,
                onBack = onBack,
                subtitle = listOfNotNull(
                    (meta?.year ?: item.year).ifBlank { null },
                    meta?.runtime?.ifBlank { null },
                    meta?.genres?.take(3)?.joinToString(", ")?.ifBlank { null },
                ).joinToString("  ·  ").ifBlank { null },
            )
        }
        item(key = "about") {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp)) {
                Box(
                    Modifier.width(if (tv) 150.dp else 112.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Ink3),
                    contentAlignment = Alignment.Center,
                ) {
                    LogoOr(meta?.poster?.ifBlank { null } ?: item.poster, null, ContentScale.Crop, Modifier.fillMaxSize()) {
                        Initials(item.name, 24.sp)
                    }
                }
                Spacer(Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    val rating = (meta?.rating ?: item.rating).ifBlank { null }
                    if (rating != null) Text("★ $rating", fontFamily = Sans, fontSize = 14.sp, color = Paper, modifier = Modifier.padding(bottom = 6.dp))
                    Text(
                        meta?.description?.ifBlank { null } ?: if (m.metaLoading) "…" else "",
                        fontFamily = Sans,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = Muted,
                        maxLines = if (tv) 7 else 9,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (series) {
            val seasons = m.seasons()
            when {
                m.metaLoading -> item(key = "seasons-wait") { Note("Reading the seasons…") }
                seasons.isEmpty() -> item(key = "seasons-none") { Note("Your add-ons list no episodes for it.") }
                else -> {
                    item(key = "seasons") {
                        ChipFlow(Modifier.padding(bottom = 6.dp)) {
                            seasons.forEach { s -> TagChip(if (s == 0) "Extras" else "Season $s", s == m.season) { m.season = s } }
                        }
                    }
                    items(m.episodes(), key = { "ep:" + it.id }) { v -> EpisodeRow(v) { m.pickEpisode(v) } }
                }
            }
        } else {
            item(key = "links-head") { SectionHeader("Links") }
            linkItems(m.streams, onPlay)
        }
    }
    // A series: the episode's links over the page.
    if (series && m.streamsFor != null) {
        Dialog(onDismissRequest = { m.closeLinks() }) {
            Surface(shape = RoundedCornerShape(16.dp), color = Ink2) {
                LazyColumn(Modifier.heightIn(max = 560.dp).padding(16.dp)) {
                    item(key = "title") {
                        Text(m.streamsTitle, fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Paper, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    linkItems(m.streams, onPlay)
                    item(key = "close") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { m.closeLinks() }) { Text("Close") }
                        }
                    }
                }
            }
        }
    }
}

/** The links, or why there are none; and how many were left out. */
private fun androidx.compose.foundation.lazy.LazyListScope.linkItems(st: AddonStreams?, onPlay: (AddonStream) -> Unit) {
    if (st == null) {
        item(key = "links-wait") { Note("Asking your add-ons…") }
        return
    }
    // Torrent links play in the GitHub version (8.16); elsewhere they are counted and left out.
    val torrents = if (Torrents.available) st.torrentLinks.take(MAX_TORRENT_LINKS) else emptyList()
    if (Torrents.available) item(key = "torrent-now") { TorrentNow() }
    if (st.playable.isEmpty() && torrents.isEmpty()) item(key = "links-none") { Note("No links that play in the app.") }
    items(st.playable, key = { "s:" + it.url }) { s -> StreamRow(s) { onPlay(s) } }
    items(torrents, key = { "t:" + it.addonName + Torrents.keyOf(it.torrent!!) }) { s -> StreamRow(s) { onPlay(s) } }
    val left = listOfNotNull(
        st.torrents.takeIf { it > 0 && !Torrents.available }?.let { "$it torrent link${if (it == 1) "" else "s"} not shown: this version of the app can't play torrents." },
        st.others.takeIf { it > 0 }?.let { "$it link${if (it == 1) "" else "s"} for other apps not shown." },
        st.failed.takeIf { it.isNotEmpty() }?.let { "Didn't answer: ${it.joinToString(", ")}." },
    )
    if (left.isNotEmpty()) item(key = "links-left") { Note(left.joinToString("\n")) }
}

/**
 * While a torrent is playing (or was, and is still here): how it is going. Back from the player
 * with a film that wouldn't start, this line says which side is stuck.
 */
@Composable
private fun TorrentNow() {
    val st by Torrents.state.collectAsStateWithLifecycle()
    if (st.stage != Torrents.Stage.READY) return
    Text(
        "Torrent: connected to ${st.peers} of ${maxOf(st.found, st.peers)} people  ·  ${Torrents.speed(st.bytesPerSecond)}  ·  " +
            "${Torrents.gb(st.keptBytes)} here  ·  the player took ${Torrents.gb(st.playedBytes)}",
        fontFamily = Sans,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = Muted,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

/** No more torrent links than this in one list (an add-on can send hundreds). */
private const val MAX_TORRENT_LINKS = 60

/**
 * A torrent link being got ready (see Torrents): what is happening, how many people are sharing
 * it and how fast it is coming, then the film starts by itself. Cancel ends it.
 */
@Composable
private fun TorrentWindow(s: AddonStream, onReady: (String) -> Unit, onCancel: () -> Unit) {
    val st by Torrents.state.collectAsStateWithLifecycle()
    val key = s.torrent?.let { Torrents.keyOf(it) }
    val mine = st.key == key
    androidx.compose.runtime.LaunchedEffect(st.stage, st.url, mine) {
        if (mine && st.stage == Torrents.Stage.READY && st.url.isNotEmpty()) onReady(st.url)
    }
    val failed = mine && st.stage == Torrents.Stage.FAILED
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (failed) "This link didn't work" else "Getting the film ready") },
        text = {
            Column {
                when {
                    failed -> Text(st.error.ifBlank { "This link couldn't be played." })
                    mine && st.stage == Torrents.Stage.BUFFERING -> {
                        Text(st.name, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Paper, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.size(6.dp))
                        Text(
                            listOf(
                                Torrents.gb(st.size),
                                "connected to ${st.peers} of ${maxOf(st.found, st.peers)} people",
                                Torrents.speed(st.bytesPerSecond),
                            ).joinToString("  ·  "),
                            fontFamily = Sans,
                            fontSize = 13.sp,
                            color = Muted,
                        )
                        Spacer(Modifier.size(12.dp))
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { st.progress },
                            color = Signal,
                            trackColor = Hairline,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    else -> {
                        Text(if (mine) st.note.ifBlank { "Starting…" } else "Starting…")
                        if (mine && st.found > 0) {
                            Spacer(Modifier.size(6.dp))
                            Text(
                                "Found ${st.found} ${if (st.found == 1) "person" else "people"}, connected to ${st.peers}.",
                                fontFamily = Sans,
                                fontSize = 13.sp,
                                color = Muted,
                            )
                        }
                    }
                }
                if (!failed) {
                    Spacer(Modifier.size(14.dp))
                    Text(
                        "It starts by itself, and only the part you are watching is fetched. While it plays, parts of the film are also shared with other people, as torrents do.",
                        fontFamily = Sans,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = Faint,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text(if (failed) "Close" else "Cancel") } },
    )
}

@Composable
private fun StreamRow(s: AddonStream, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .focusRing(shape)
            .clip(shape)
            .background(Ink3)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Signal, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(
                    s.name.lines().joinToString(" ").trim().ifBlank { "Link" },
                    s.addonName.takeIf { it.isNotBlank() && !s.name.contains(it, ignoreCase = true) },
                    "Torrent".takeIf { s.torrent != null },
                ).joinToString("  ·  "),
                fontFamily = Sans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = Paper,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (s.description.isNotBlank()) {
                Text(s.description.trim(), fontFamily = Sans, fontSize = 12.sp, lineHeight = 16.sp, color = Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun EpisodeRow(v: AddonVideo, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .focusRing(shape)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (v.season > 0) "E${v.episode}" else "${v.episode}",
            fontFamily = Sans,
            fontSize = 14.sp,
            color = Faint,
            modifier = Modifier.width(44.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(v.title.ifBlank { "Episode ${v.episode}" }, fontFamily = Sans, fontSize = 15.sp, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val more = listOfNotNull(v.released.ifBlank { null }, v.overview.ifBlank { null }).joinToString("  ·  ")
            if (more.isNotEmpty()) Text(more, fontFamily = Sans, fontSize = 12.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Pasting an add-on's link: it's checked before it's added. */
@Composable
internal fun AddAddonDialog(
    m: AddonsModel,
    already: List<String>,
    onAdd: (Addon) -> Unit,
    onDismiss: () -> Unit,
    // The Add playlist window's choices (M3U link, M3U file, Xtream, Add-on) when opened from there.
    kinds: (@Composable () -> Unit)? = null,
) {
    var text by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val add: () -> Unit = {
        val base = Addons.normalize(text)
        when {
            base == null -> { error = "That isn't an add-on link." }
            base in already -> { error = "That add-on is already added." }
            !checking -> {
                checking = true
                error = null
                scope.launch {
                    val r = m.check(text)
                    checking = false
                    r.onSuccess(onAdd).onFailure { error = it.message }
                }
            }
        }
    }
    val support = error ?: if (checking) "Checking…" else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an add-on") },
        text = {
            Column {
                if (kinds != null) {
                    kinds()
                    Spacer(Modifier.size(10.dp))
                }
                Text(
                    "Paste the add-on's link (it ends in manifest.json).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                OutlinedTextField(
                    text,
                    {
                        text = it.trim().take(600)
                        error = null
                    },
                    singleLine = true,
                    label = { Text("Link") },
                    isError = error != null,
                    supportingText = if (support != null) {
                        { Text(support) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { add() }, enabled = text.isNotBlank() && !checking) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
