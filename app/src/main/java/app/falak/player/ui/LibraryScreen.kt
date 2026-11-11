package app.falak.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.falak.player.data.mine
import app.falak.player.data.ADULT_GROUP
import app.falak.player.data.AppState
import app.falak.player.data.Channel
import app.falak.player.data.Downloads
import app.falak.player.data.DEFAULT_LIST
import app.falak.player.data.Epg
import app.falak.player.data.Kind
import app.falak.player.data.Program
import app.falak.player.data.SearchIndex
import app.falak.player.data.Watch
import app.falak.player.data.progressKey
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(vm: LibraryViewModel, state: AppState, onPlay: (List<Channel>, Channel) -> Unit) {
    val p = vm.playlist ?: return
    val index = vm.index
    val epg by vm.epg.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val favIds = state.favorites[state.mine(p.id)].orEmpty()
    val recentIds = state.recents[state.mine(p.id)].orEmpty()
    val lists = state.favLists[state.mine(p.id)].orEmpty()
    val savedList = vm.savedList.takeIf { id -> id == DEFAULT_LIST || lists.any { it.id == id } } ?: DEFAULT_LIST
    val savedIds = if (savedList == DEFAULT_LIST) favIds else lists.firstOrNull { it.id == savedList }?.ids.orEmpty()
    // The star lights up when an item is in any of your lists.
    val favSet = remember(favIds, lists) { HashSet<String>(favIds).apply { lists.forEach { addAll(it.ids) } } }
    var editList by remember { mutableStateOf<app.falak.player.data.FavList?>(null) }
    var newList by remember { mutableStateOf(false) }
    val locked = rememberLockedGroups(vm, state)
    val downloads = rememberDownloads()
    // Hidden groups, plus (in kids mode) every group not chosen for kids.
    val hidden = remember(state.settings.hiddenGroups, state.settings.kidsMode, state.settings.kidsGroups, vm.kidsActive, p.id, index) {
        state.settings.hiddenGroups[p.id].orEmpty().toHashSet().apply { addAll(vm.kidsHidden(state.settings, index)) }
    }
    val excluded = remember(locked, hidden) { if (hidden.isEmpty()) locked else locked + hidden }
    var pinForGroup by remember { mutableStateOf<String?>(null) }
    var groupMenu by remember { mutableStateOf<String?>(null) }

    val sections = rememberSections(vm, state)
    val section = vm.section.takeIf { it in sections } ?: sections.firstOrNull() ?: Section.LIVE
    val kind = section.kind
    val showGroups = kind != null
    val group = if (showGroups) vm.group else null
    val q = vm.query.trim()
    val isVod = section == Section.MOVIES || section == Section.SERIES
    val sort = if (isVod) vm.sort else Sort.DEFAULT
    // Films and Series: no group is their front page; "All" is every title as a grid.
    val selectGroup: (String?) -> Unit = { g ->
        if (g != null && g in locked) {
            pinForGroup = g
        } else {
            vm.group = g ?: if (isVod) ALL_FILTER else null
        }
    }
    // The group the chips and the group list show as picked ("All" for every title).
    val pickedGroup = group.takeUnless { it == ALL_FILTER }
    // Home, or the Films or Series front page, is open: its featured picture goes behind the bars too.
    val landing = section.takeIf { q.isEmpty() && (section == Section.HOME || (isVod && group == null)) }
    val base: List<Channel> = when (section) {
        Section.HOME -> index.all
        Section.FAVORITES -> remember(savedIds, index) { savedIds.mapNotNull { index.byId[it] } }
        Section.RECENT -> remember(recentIds, index) { recentIds.mapNotNull { index.byId[it] } }
        else -> index.ofKind(kind!!)
    }
    val allGroups: Map<String, List<Channel>> = if (kind != null) index.groupsOf(kind) else emptyMap()
    val groupMap = remember(allGroups, hidden) { if (hidden.isEmpty()) allGroups else allGroups.filterKeys { it !in hidden } }

    // Filtering and sorting run on a background thread, so taps stay instant on big playlists.
    val favOfKind: List<Channel> = remember(favIds, index, kind) {
        if (kind == null) emptyList() else favIds.mapNotNull { index.byId[it] }.filter { it.kind == kind }
    }
    val visibleTotal = remember(groupMap) { groupMap.values.sumOf { it.size } }
    val gridView = section.name in state.settings.gridSections
    // With a search: every match in the section (all of them from Home), best first (see
    // SearchIndex), and the best five for the row under the search box.
    val result by produceState<Pair<List<Channel>, List<Channel>>?>(null, base, group, q, sort, excluded, groupMap, favOfKind, section) {
        if (q.isNotEmpty()) delay(160)
        value = withContext(Dispatchers.Default) {
            val allowed: (Channel) -> Boolean = { ch -> excluded.isEmpty() || ch.group !in excluded }
            val out = when {
                q.isNotEmpty() && (section == Section.HOME || kind != null) ->
                    index.search.find(q) { ch -> (kind == null || ch.kind == kind) && allowed(ch) }
                q.isNotEmpty() -> SearchIndex.rank(base, q, allowed)
                else -> {
                    val src = when (group) {
                        null, ALL_FILTER -> base
                        FAV_FILTER -> favOfKind
                        else -> groupMap[group].orEmpty()
                    }
                    if (excluded.isEmpty()) src else src.filter(allowed)
                }
            }
            val top = if (q.isEmpty()) emptyList() else out.take(5)
            val sorted = when (sort) {
                Sort.DEFAULT -> out
                Sort.NEWEST -> out.sortedByDescending { it.added }
                // Compare without making a lowercase copy of a name on every comparison: on 100,000 films
                // that churned millions of strings.
                Sort.AZ -> out.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                Sort.RATING -> out.sortedByDescending { it.rating.toDoubleOrNull() ?: 0.0 }
            }
            sorted to top
        }
    }
    val visible = result?.first
    val list = visible.orEmpty()
    val sorts = remember(base, isVod) {
        if (!isVod) emptyList() else Sort.entries.filter { s ->
            when (s) {
                Sort.NEWEST -> base.any { it.added > 0 }
                Sort.RATING -> base.any { (it.rating.toDoubleOrNull() ?: 0.0) > 0 }
                else -> true
            }
        }
    }

    // The search box opened: names' sounds get worked out meanwhile, for searches in the other script.
    val searchOpen = vm.searchFocused || vm.query.isNotEmpty()
    LaunchedEffect(searchOpen, index) { if (searchOpen) withContext(Dispatchers.Default) { index.search.warmSounds() } }
    // The search bar is always there: "searching" just means something is typed.
    val searchOn = vm.query.isNotEmpty()
    val listState = vm.listStateFor(section)
    val gridState = vm.gridStateFor(section)
    LaunchedEffect(section, group, q, sort, savedList) {
        val key = listOf(section, group, q, sort, savedList)
        if (vm.lastListKey != null && vm.lastListKey != key) {
            listState.scrollToItem(0)
            gridState.scrollToItem(0)
        }
        vm.lastListKey = key
    }
    // Coming back from the player: scroll to (and on TV focus) what was playing.
    val focusId = vm.focusId
    // Back on the lists: the remote goes back to the card it came from (TV; see ReturnFocus).
    LaunchedEffect(Unit) { vm.onLibraryShown() }
    LaunchedEffect(focusId, visible) {
        val id = focusId ?: return@LaunchedEffect
        // Home and the Films and Series front pages put the remote back themselves.
        if (landing != null) {
            vm.focusId = null
            return@LaunchedEffect
        }
        val items = visible ?: return@LaunchedEffect
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) {
            if (section != Section.HOME) vm.focusId = null
            return@LaunchedEffect
        }
        val usesGrid = kind != null && gridView
        if (usesGrid) gridState.scrollToItem(idx) else listState.scrollToItem((idx - 2).coerceAtLeast(0))
        delay(400)
        vm.focusId = null
    }
    // Opening something found by a search remembers the search (Recent, under the search box).
    val openFrom: (List<Channel>, Channel) -> Unit = { row, ch ->
        if (q.isNotEmpty()) vm.rememberSearch(q)
        when {
            ch.isSeriesFolder -> vm.openSeries(ch)
            ch.kind == Kind.MOVIE -> vm.openDetails(ch)
            // The player steps through items of the same kind (never from a channel into a film).
            else -> onPlay(if (row.all { it.kind == ch.kind }) row else row.filter { it.kind == ch.kind }, ch)
        }
    }
    val openItem: (Channel) -> Unit = { ch -> openFrom(list, ch) }
    LaunchedEffect(q.isEmpty()) { if (q.isEmpty()) vm.searchFromHome = false }
    // Recent searches show while the search bar (or the row itself) has the focus.
    var assistFocused by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { vm.searchFocused = false } }
    val showRecent = !vm.kidsActive && (vm.searchFocused || assistFocused)

    BackHandler(enabled = searchOn || group != null || (section != Section.HOME && Section.HOME in sections)) {
        when {
            // "See all" from Home's results: back to them, search kept.
            searchOn && vm.searchFromHome && section != Section.HOME -> {
                vm.searchFromHome = false
                vm.section = Section.HOME
            }
            searchOn -> vm.query = ""
            group != null -> {
                // The Films or Series front page again, with the remote on the chip picked.
                if (isVod) vm.returnFocus = ReturnFocus(GROUP_ROW, group.takeUnless { it == ALL_FILTER } ?: ALL_CHIP)
                vm.group = null
            }
            else -> vm.section = Section.HOME
        }
    }

    val tv = isTv()

    // Everything under the top bar: progress, errors, Home or the section's lists.
    @Composable
    fun Body(modifier: Modifier) {
        Column(modifier) {
            // A playlist update (asked for or automatic) says so above the lists, which stay usable;
            // then "Playlist updated" for a few seconds, or why it didn't work.
            val err = vm.error?.takeIf { index.all.isNotEmpty() }
            val note = vm.updateNote
            when {
                (vm.loading || vm.refreshing) && index.all.isNotEmpty() -> {
                    UpdateLine("Updating playlist…", Muted)
                    BusyLine(Modifier.fillMaxWidth().padding(top = 4.dp).height(2.dp))
                }
                err != null -> UpdateLine("Couldn't update the playlist: $err", MaterialTheme.colorScheme.error)
                note != null && note.ok ->
                    UpdateLine(if (note.added > 0) "Playlist updated · ${note.added} new" else "Playlist updated", Muted)
                note != null && vm.loadedAt > 0 ->
                    UpdateLine("Couldn't update the playlist · last updated ${ageText(vm.loadedAt)}", MaterialTheme.colorScheme.error)
            }
            if (index.all.isNotEmpty()) {
                SearchAssist(
                    query = vm.query,
                    recent = vm.recentSearches,
                    showRecent = showRecent,
                    top = result?.second.orEmpty(),
                    onRecent = { vm.query = it },
                    onClearRecent = { vm.clearRecentSearches() },
                    onOpen = { ch -> openFrom(listOf(ch), ch) },
                    modifier = Modifier.onFocusChanged { assistFocused = it.hasFocus },
                )
            }

            when {
                vm.loading && index.all.isEmpty() -> Centered {
                    val step by vm.loadProgress.collectAsStateWithLifecycle()
                    CircularProgressIndicator(color = Signal, strokeWidth = 2.dp)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        if (vm.fromSaved) "Opening your playlist" else "Downloading your playlist",
                        fontFamily = Serif, fontSize = 18.sp, color = Paper,
                    )
                    Text(
                        when {
                            // The provider's server failed part-way: say so, rather than seem stuck.
                            step.retry > 0 -> "Your provider's server is slow to answer. Trying again (${step.retry} of ${Downloads.TRIES})…"
                            step.count > 0 -> "${countLabel(step.count)} so far"
                            vm.fromSaved -> "From the copy saved on this device."
                            else -> "Big playlists take a moment the first time."
                        },
                        fontSize = 13.sp, lineHeight = 19.sp, color = Muted, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
                vm.error != null && index.all.isEmpty() -> Centered {
                    Text("Something went wrong", fontFamily = Serif, fontSize = 20.sp, color = Paper)
                    Spacer(Modifier.height(6.dp))
                    Text(vm.error.orEmpty(), color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                    Spacer(Modifier.height(16.dp))
                    PaperButton("Try again") { vm.open(p, refresh = true) }
                    // No internet: what's downloaded still plays.
                    if (!vm.kidsActive && downloads.any { it.state == app.falak.player.data.OfflineState.DONE }) {
                        Spacer(Modifier.height(10.dp))
                        GhostButton("Watch your downloads", downloadIcon()) { vm.screen = Screen.DOWNLOADS }
                    }
                }
                else -> AnimatedContent(
                    targetState = section to (q.isEmpty()),
                    transitionSpec = {
                        if (tv) {
                            // TV: one quick fade; the old page goes at once (no two full pages drawn together).
                            fadeIn(tween(150)).togetherWith(ExitTransition.None)
                        } else {
                            (fadeIn(tween(260, delayMillis = 60)) + slideInVertically(tween(320)) { it / 40 })
                                .togetherWith(fadeOut(tween(140)))
                        }
                    },
                    label = "section",
                    modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                ) { (shown, noQuery) ->
                    if (shown == Section.HOME && noQuery) {
                        HomeContent(vm, state, excluded, epg, now, onPlay)
                    } else if (shown == Section.HOME) {
                        SearchResults(vm, state, q, visible, excluded, epg, now, openFrom)
                    } else if (noQuery && group == null && (shown == Section.MOVIES || shown == Section.SERIES)) {
                        MarqueeBrowse(
                            vm, state, shown, groupMap, excluded, locked,
                            onOpen = openFrom,
                            onPlay = onPlay,
                            onGroup = selectGroup,
                            onGroupOptions = { if (!vm.kidsActive) groupMenu = it },
                        )
                    } else {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val wide = maxWidth >= 720.dp
                            val groupsVisible = showGroups && q.isEmpty() && groupMap.size > 1
                            val content: @Composable (Modifier) -> Unit = { m ->
                                when {
                                    visible == null -> Box(m)
                                    // TV, Live TV as a list: the channel the remote is on, beside it.
                                    shown == Section.LIVE && tv && wide && !(shown.name in state.settings.gridSections) -> {
                                        var focusedId by remember { mutableStateOf<String?>(null) }
                                        var here by remember { mutableStateOf(false) }
                                        // Opened full screen from here: no preview until the remote moves on.
                                        var opened by remember { mutableStateOf(false) }
                                        LaunchedEffect(focusedId) { opened = false }
                                        val focusedCh = remember(list, focusedId) { list.firstOrNull { it.id == focusedId } ?: list.firstOrNull() }
                                        Row(m.onFocusChanged { here = it.hasFocus }) {
                                            ChannelList(
                                                list = list, favIds = favSet, epg = epg, now = now, state = vm.listStateFor(shown),
                                                progress = state.progress, playlistId = state.mine(p.id),
                                                onOpen = { ch -> opened = true; openItem(ch) }, onFav = { ch -> vm.onStar(ch) }, onLongPress = { ch -> vm.listSheetFor = ch },
                                                focusId = focusId, modifier = Modifier.weight(0.6f).fillMaxHeight(),
                                                onFocus = { ch -> focusedId = ch.id },
                                            )
                                            Spacer(Modifier.width(24.dp))
                                            LivePreviewPane(
                                                vm = vm,
                                                ch = focusedCh,
                                                list = list,
                                                epg = epg,
                                                now = now,
                                                active = here && !opened,
                                                livePreview = state.settings.livePreview,
                                                onPlay = { l, ch -> opened = true; openFrom(l, ch) },
                                                modifier = Modifier.weight(0.4f).fillMaxHeight(),
                                            )
                                        }
                                    }
                                    shown.kind != null && !(shown.name in state.settings.gridSections) -> ChannelList(
                                        list = list, favIds = favSet, epg = epg, now = now, state = vm.listStateFor(shown),
                                        progress = state.progress, playlistId = state.mine(p.id),
                                        onOpen = openItem, onFav = { ch -> vm.onStar(ch) }, onLongPress = { ch -> vm.listSheetFor = ch },
                                        focusId = focusId, modifier = m,
                                    )
                                    shown == Section.LIVE -> ChannelGrid(
                                        list, epg, now, vm.gridStateFor(shown), openItem, { ch -> vm.listSheetFor = ch }, m,
                                    )
                                    isVod -> PosterGrid(
                                        list, vm.gridStateFor(shown), state.progress, p.id, focusId, openItem,
                                        { ch -> vm.listSheetFor = ch }, m,
                                    )
                                    shown == Section.FAVORITES -> Column(m) {
                                        if (noQuery) {
                                            ListChips(lists, favIds.size, savedList, { vm.savedList = it }, { editList = it }) { newList = true }
                                        }
                                        ChannelList(
                                            list = list, favIds = favSet, epg = epg, now = now, state = vm.listStateFor(shown),
                                            progress = state.progress, playlistId = state.mine(p.id),
                                            onOpen = openItem, onFav = { ch -> vm.onStar(ch) }, onLongPress = { ch -> vm.listSheetFor = ch },
                                            focusId = focusId, modifier = Modifier.weight(1f).fillMaxWidth(),
                                            emptyText = if (savedList == DEFAULT_LIST) "Tap the star on any channel, film or series to add it to Favorites."
                                            else "This list is empty. Long-press any channel, film or series and tick this list.",
                                        )
                                    }
                                    else -> ChannelList(
                                        list = list, favIds = favSet, epg = epg, now = now, state = vm.listStateFor(shown),
                                        progress = state.progress, playlistId = state.mine(p.id),
                                        onOpen = openItem, onFav = { ch -> vm.onStar(ch) }, onLongPress = { ch -> vm.listSheetFor = ch },
                                        focusId = focusId, modifier = m,
                                    )
                                }
                            }
                            // A header with the count, sort and grid/list, then the groups as chips
                            // with counts (the menu already takes the left).
                            Column(Modifier.fillMaxSize()) {
                                SectionHead(
                                    title = when {
                                        !noQuery -> "Results"
                                        shown == Section.LIVE -> "All channels"
                                        shown == Section.MOVIES -> "All films"
                                        shown == Section.SERIES -> "All series"
                                        shown == Section.RECENT -> "Recently watched"
                                        else -> shown.label
                                    },
                                    count = list.size,
                                    unit = when (shown) {
                                        Section.LIVE -> if (list.size == 1) "channel" else "channels"
                                        Section.MOVIES -> if (list.size == 1) "film" else "films"
                                        Section.SERIES -> "series"
                                        else -> if (list.size == 1) "item" else "items"
                                    },
                                    sorts = sorts,
                                    sort = sort,
                                    onSort = { vm.sort = it },
                                    grid = if (shown.kind != null) (shown.name in state.settings.gridSections) else null,
                                    onGrid = { vm.setGridView(shown, it) },
                                )
                                if (groupsVisible || (showGroups && q.isEmpty() && favOfKind.isNotEmpty())) {
                                    FilterChips(groupMap, visibleTotal, favOfKind.size, pickedGroup, locked, selectGroup, { if (!vm.kidsActive) groupMenu = it }) { vm.screen = Screen.GROUPS }
                                }
                                content(Modifier.weight(1f).fillMaxWidth())
                            }
                        }
                    }
                }
            }

        }
    }

    SidebarFrame(vm, state, p, sections, section, landing, excluded, onPlay, hasDownloads = !vm.kidsActive && downloads.isNotEmpty()) { m -> Body(m) }

    editList?.let { l -> ListEditDialog(vm, l) { editList = null } }
    if (newList) {
        NewListDialog(onDismiss = { newList = false }) { name ->
            vm.createList(name, null)?.let { vm.savedList = it }
            newList = false
        }
    }

    groupMenu?.let { g ->
        GroupMenu(vm, kind, g) { groupMenu = null }
    }

    pinForGroup?.let { g ->
        PinDialog(
            title = "Enter PIN to open “$g”",
            onDismiss = { pinForGroup = null },
            onSubmit = { pin ->
                val ok = vm.tryUnlock(pin)
                if (ok) {
                    vm.group = g
                    pinForGroup = null
                }
                ok
            },
        )
    }
}

/** Groups currently hidden behind the parental PIN for the open playlist. */
@Composable
fun rememberLockedGroups(vm: LibraryViewModel, state: AppState): Set<String> {
    val p = vm.playlist
    val s = state.settings
    val active = p != null && s.pin.isNotEmpty() && !vm.unlocked
    val explicit = if (p != null) s.lockedGroups[p.id].orEmpty() else emptyList()
    val all = vm.channels
    return remember(all, active, explicit, s.lockAdult) {
        if (!active) {
            emptySet()
        } else {
            all.mapTo(LinkedHashSet()) { it.group }
                .filterTo(HashSet()) { g -> g in explicit || (s.lockAdult && ADULT_GROUP.containsMatchIn(g)) }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun GroupLine(name: String, count: Int, selected: Boolean, locked: Boolean, onLongClick: () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .focusRing(shape)
            .clip(shape)
            .background(if (selected) Ink3 else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .padding(end = 10.dp)
                .width(3.dp)
                .height(18.dp)
                .background(if (selected) Signal else Color.Transparent, RoundedCornerShape(2.dp)),
        )
        Text(
            name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = Sans,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Paper else Muted,
            modifier = Modifier.weight(1f),
        )
        if (locked) {
            Icon(Icons.Filled.Lock, contentDescription = "Locked", tint = Faint, modifier = Modifier.padding(end = 8.dp).size(14.dp))
        } else {
            Text("$count", style = Tabular.copy(fontSize = 12.sp), color = Faint, modifier = Modifier.padding(end = 8.dp))
        }
    }
}

@Composable
private fun PosterGrid(
    list: List<Channel>,
    state: LazyGridState,
    progress: Map<String, Watch>,
    playlistId: String,
    focusId: String?,
    onOpen: (Channel) -> Unit,
    onLongPress: (Channel) -> Unit,
    modifier: Modifier,
) {
    if (list.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("Nothing here yet", color = Muted) }
        return
    }
    LazyVerticalGrid(
        // The Sidebar look fits more, smaller posters per row (with their details underneath).
        columns = GridCells.Adaptive(
            when {
                LocalCardDetails.current -> if (isTv()) 116.dp else 100.dp
                isTv() -> 140.dp
                else -> 108.dp
            },
        ),
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        gridItems(list, key = { it.id }) { ch ->
            PosterCard(
                ch, progress[progressKey(playlistId, ch.id)], onClick = { onOpen(ch) }, width = null,
                focusMe = ch.id == focusId, onLongClick = { onLongPress(ch) },
            )
        }
    }
}

@Composable
private fun ChannelList(
    list: List<Channel>,
    favIds: Set<String>,
    epg: Map<String, List<Program>>,
    now: Long,
    state: LazyListState,
    progress: Map<String, Watch>,
    playlistId: String,
    onOpen: (Channel) -> Unit,
    onFav: (Channel) -> Unit,
    onLongPress: (Channel) -> Unit,
    focusId: String?,
    modifier: Modifier,
    emptyText: String = "Nothing here yet",
    /** The remote moved onto a row. */
    onFocus: ((Channel) -> Unit)? = null,
) {
    if (list.isEmpty()) {
        Box(modifier.padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            Text(emptyText, color = Muted, textAlign = TextAlign.Center)
        }
        return
    }
    // The remote goes round: down on the last one comes back to the first.
    val loop = rememberFocusLoop()
    LazyColumn(modifier.focusLoop(loop, state, list.size), state = state, contentPadding = PaddingValues(bottom = 32.dp)) {
        itemsIndexed(list, key = { _, ch -> ch.id }) { i, ch ->
            val current = if (ch.kind == Kind.LIVE) Epg.current(epg[ch.epgKey], now) else null
            val watched = if (ch.kind != Kind.LIVE) progress[progressKey(playlistId, ch.id)] else null
            ChannelRow(
                i + 1, ch, current, watched, ch.id in favIds, now, { onOpen(ch) }, { onFav(ch) }, { onLongPress(ch) }, ch.id == focusId,
                Modifier.loopItem(loop, i).then(if (onFocus != null) Modifier.onFocusChanged { if (it.isFocused) onFocus(ch) } else Modifier),
            )
            Rule()
        }
    }
}

/** Editorial list row: number, logo, name, what's on now with time left, a quiet star. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun ChannelRow(
    number: Int,
    ch: Channel,
    current: Program?,
    watched: Watch?,
    isFav: Boolean,
    now: Long,
    onClick: () -> Unit,
    onFav: () -> Unit,
    onLongClick: () -> Unit,
    focusMe: Boolean,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    val isLive = ch.kind == Kind.LIVE
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusMe) {
        if (focusMe) {
            delay(120)
            runCatching { focus.requestFocus() }
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(if (focusMe) Signal.copy(alpha = 0.10f) else Color.Transparent, shape)
            .focusRequester(focus)
            .focusRing(shape)
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isLive) {
            Text(
                "$number",
                style = Tabular.copy(fontSize = 13.sp),
                color = Faint,
                textAlign = TextAlign.End,
                modifier = Modifier.width(34.dp).padding(end = 10.dp),
            )
        }
        Box(
            Modifier
                .size(if (isLive) 64.dp else 46.dp, if (isLive) 40.dp else 68.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Ink3),
            contentAlignment = Alignment.Center,
        ) {
            LogoOr(
                url = ch.logo,
                contentDescription = null,
                contentScale = if (isLive) ContentScale.Fit else ContentScale.Crop,
                modifier = if (isLive) Modifier.fillMaxSize().padding(5.dp) else Modifier.fillMaxSize(),
            ) { Initials(ch.name, if (isLive) 14.sp else 13.sp) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ch.name,
                    fontFamily = Sans,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = Paper,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Tidy channel list: the quality the provider's name gave.
                ch.tidyQuality?.let {
                    Text("  $it", fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, letterSpacing = 1.sp, color = Amber)
                }
                if (ch.hasCatchup) {
                    Text("  REPLAY", fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, letterSpacing = 1.sp, color = Muted)
                }
            }
            when {
                current != null -> {
                    Text(
                        "${current.title}  ·  ${minutesLeft(current, now)}",
                        fontFamily = Sans,
                        fontSize = 12.sp,
                        color = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val length = (current.stop - current.start).coerceAtLeast(1L).toFloat()
                    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(2.dp).background(Hairline)) {
                        Box(Modifier.fillMaxWidth(((now - current.start) / length).coerceIn(0f, 1f)).height(2.dp).background(Signal))
                    }
                }
                !isLive -> {
                    Text(
                        ch.info.ifBlank { listOfNotNull(ratingLabel(ch.rating), ch.group).joinToString("  ·  ") },
                        fontFamily = Sans,
                        fontSize = 12.sp,
                        color = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (watched != null && watched.dur > 0) {
                        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(2.dp).background(Hairline)) {
                            Box(Modifier.fillMaxWidth((watched.pos.toFloat() / watched.dur).coerceIn(0f, 1f)).height(2.dp).background(Signal))
                        }
                    }
                }
                else -> Text(ch.group, fontFamily = Sans, fontSize = 12.sp, color = Faint, maxLines = 1)
            }
        }
        IconButton(onClick = onFav) {
            Icon(
                Icons.Filled.Star,
                contentDescription = if (isFav) "In your lists" else "Save",
                tint = if (isFav) Amber else Faint.copy(alpha = 0.45f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** One quiet line above the lists about the playlist's update. */
@Composable
private fun UpdateLine(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp),
    )
}
