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
import app.falak.player.data.ADULT_GROUP
import app.falak.player.data.AppState
import app.falak.player.data.Channel
import app.falak.player.data.DEFAULT_LIST
import app.falak.player.data.Epg
import app.falak.player.data.Kind
import app.falak.player.data.Program
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
    val favIds = state.favorites[p.id].orEmpty()
    val recentIds = state.recents[p.id].orEmpty()
    val lists = state.favLists[p.id].orEmpty()
    val savedList = vm.savedList.takeIf { id -> id == DEFAULT_LIST || lists.any { it.id == id } } ?: DEFAULT_LIST
    val savedIds = if (savedList == DEFAULT_LIST) favIds else lists.firstOrNull { it.id == savedList }?.ids.orEmpty()
    // The star lights up when an item is in any of your lists.
    val favSet = remember(favIds, lists) { HashSet<String>(favIds).apply { lists.forEach { addAll(it.ids) } } }
    var editList by remember { mutableStateOf<app.falak.player.data.FavList?>(null) }
    var newList by remember { mutableStateOf(false) }
    val locked = rememberLockedGroups(vm, state)
    val hidden = remember(state.settings.hiddenGroups, p.id) { state.settings.hiddenGroups[p.id].orEmpty().toHashSet() }
    val excluded = remember(locked, hidden) { if (hidden.isEmpty()) locked else locked + hidden }
    var pinForGroup by remember { mutableStateOf<String?>(null) }
    var groupMenu by remember { mutableStateOf<String?>(null) }
    val selectGroup: (String?) -> Unit = { g ->
        if (g != null && g in locked) {
            pinForGroup = g
        } else {
            vm.group = g
        }
    }

    val hasFav = favIds.isNotEmpty() || lists.isNotEmpty()
    val hasRecent = recentIds.isNotEmpty()
    val sections = remember(index, hasFav, hasRecent) {
        Section.entries.filter { s ->
            when (s) {
                Section.HOME -> index.all.isNotEmpty()
                Section.FAVORITES -> hasFav
                Section.RECENT -> hasRecent
                else -> s.kind != null && index.ofKind(s.kind).isNotEmpty()
            }
        }
    }
    val section = vm.section.takeIf { it in sections } ?: sections.firstOrNull() ?: Section.LIVE
    val kind = section.kind
    val showGroups = kind != null
    val group = if (showGroups) vm.group else null
    val q = vm.query.trim()
    val isVod = section == Section.MOVIES || section == Section.SERIES
    val sort = if (isVod) vm.sort else Sort.DEFAULT
    val base: List<Channel> = when (section) {
        Section.HOME -> index.all
        Section.FAVORITES -> remember(savedIds, index) { savedIds.mapNotNull { index.byId[it] } }
        Section.RECENT -> remember(recentIds, index) { recentIds.mapNotNull { index.byId[it] } }
        else -> index.ofKind(kind!!)
    }
    val allGroups: Map<String, List<Channel>> = if (kind != null) index.groupsOf(kind) else emptyMap()
    val groupMap = remember(allGroups, hidden) { if (hidden.isEmpty()) allGroups else allGroups.filterKeys { it !in hidden } }

    // Filtering and sorting run on a background thread, so taps stay instant on big playlists.
    val visible by produceState<List<Channel>?>(null, base, group, q, sort, excluded, groupMap) {
        if (q.isNotEmpty()) delay(160)
        value = withContext(Dispatchers.Default) {
            val src = if (q.isEmpty() && group != null) groupMap[group].orEmpty() else base
            val out = if (excluded.isEmpty() && q.isEmpty()) src else src.filter { ch ->
                (excluded.isEmpty() || ch.group !in excluded) && (q.isEmpty() || ch.name.contains(q, ignoreCase = true))
            }
            when (sort) {
                Sort.DEFAULT -> out
                Sort.NEWEST -> out.sortedByDescending { it.added }
                Sort.AZ -> out.sortedBy { it.name.lowercase() }
                Sort.RATING -> out.sortedByDescending { it.rating.toDoubleOrNull() ?: 0.0 }
            }
        }
    }
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

    var searching by rememberSaveable { mutableStateOf(vm.query.isNotEmpty()) }
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
    LaunchedEffect(focusId, visible) {
        val id = focusId ?: return@LaunchedEffect
        val items = visible ?: return@LaunchedEffect
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) {
            if (section != Section.HOME) vm.focusId = null
            return@LaunchedEffect
        }
        if (isVod) gridState.scrollToItem(idx) else listState.scrollToItem((idx - 2).coerceAtLeast(0))
        delay(400)
        vm.focusId = null
    }
    val openItem: (Channel) -> Unit = { ch ->
        when {
            ch.isSeriesFolder -> vm.openSeries(ch)
            ch.kind == Kind.MOVIE -> vm.openDetails(ch)
            else -> onPlay(list, ch)
        }
    }

    BackHandler(enabled = searching || group != null || (section != Section.HOME && Section.HOME in sections)) {
        when {
            searching -> {
                searching = false
                vm.query = ""
            }
            group != null -> vm.group = null
            else -> vm.section = Section.HOME
        }
    }

    val tv = isTv()
    Column(Modifier.fillMaxSize().padding(horizontal = if (tv) 40.dp else 16.dp)) {
        // Masthead
        Row(Modifier.fillMaxWidth().padding(top = if (tv) 18.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .focusRing(RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { vm.screen = Screen.PLAYLISTS }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandMark(if (tv) 38.dp else 32.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Wordmark(if (tv) 24 else 20)
                    Text(
                        "${p.name}  ⌄",
                        fontFamily = Sans,
                        fontSize = 12.sp,
                        color = Faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Clock(Modifier.padding(horizontal = 8.dp))
            if (Kind.LIVE in setOfNotNull(kind) || section == Section.HOME) {
                if (index.live.isNotEmpty()) {
                    IconButton(onClick = { vm.screen = Screen.GUIDE }) {
                        Icon(Icons.Outlined.DateRange, contentDescription = "TV guide", tint = Paper)
                    }
                }
            }
            IconButton(onClick = { searching = !searching; if (!searching) vm.query = "" }) {
                Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, contentDescription = "Search", tint = Paper)
            }
            IconButton(onClick = { vm.open(p, refresh = true) }, enabled = !vm.loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh playlist", tint = Paper)
            }
            IconButton(onClick = { vm.screen = Screen.SETTINGS }) {
                Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = Paper)
            }
        }

        if (sections.size > 1) {
            Spacer(Modifier.height(6.dp))
            NavTabs(sections, section, onSelect = { s -> vm.section = s; vm.group = null }, large = tv)
        }

        AnimatedVisibility(searching, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            SearchField(vm.query, section.label) { vm.query = it }
        }

        if ((vm.loading || vm.refreshing) && index.all.isNotEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp).height(2.dp), color = Signal, trackColor = Hairline)
        }
        vm.error?.takeIf { index.all.isNotEmpty() }?.let {
            Text("Couldn't refresh: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }

        when {
            vm.loading && index.all.isEmpty() -> Centered {
                CircularProgressIndicator(color = Signal, strokeWidth = 2.dp)
                Spacer(Modifier.height(14.dp))
                Text("Loading your playlist", fontFamily = Serif, fontSize = 18.sp, color = Paper)
                Text("Big playlists take a moment the first time.", fontSize = 13.sp, color = Muted)
            }
            vm.error != null && index.all.isEmpty() -> Centered {
                Text("Something went wrong", fontFamily = Serif, fontSize = 20.sp, color = Paper)
                Spacer(Modifier.height(6.dp))
                Text(vm.error.orEmpty(), color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                Spacer(Modifier.height(16.dp))
                PaperButton("Try again") { vm.open(p, refresh = true) }
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
                } else {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val wide = maxWidth >= 720.dp
                        val groupsVisible = showGroups && q.isEmpty() && groupMap.size > 1
                        val content: @Composable (Modifier) -> Unit = { m ->
                            when {
                                visible == null -> Box(m)
                                isVod -> Column(m) {
                                    if (sorts.size > 1) {
                                        Row(
                                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            sorts.forEach { s -> TagChip(s.label, s == sort) { vm.sort = s } }
                                        }
                                    }
                                    PosterGrid(
                                        list, vm.gridStateFor(shown), state.progress, p.id, focusId, openItem,
                                        { ch -> vm.listSheetFor = ch }, Modifier.weight(1f).fillMaxWidth(),
                                    )
                                }
                                shown == Section.FAVORITES -> Column(m) {
                                    if (noQuery) {
                                        ListChips(lists, favIds.size, savedList, { vm.savedList = it }, { editList = it }) { newList = true }
                                    }
                                    ChannelList(
                                        list = list, favIds = favSet, epg = epg, now = now, state = vm.listStateFor(shown),
                                        progress = state.progress, playlistId = p.id,
                                        onOpen = openItem, onFav = { ch -> vm.onStar(ch) }, onLongPress = { ch -> vm.listSheetFor = ch },
                                        focusId = focusId, modifier = Modifier.weight(1f).fillMaxWidth(),
                                        emptyText = if (savedList == DEFAULT_LIST) "Tap the star on any channel, film or series to save it here."
                                        else "This list is empty. Long-press any channel, film or series and tick this list.",
                                    )
                                }
                                else -> ChannelList(
                                    list = list, favIds = favSet, epg = epg, now = now, state = vm.listStateFor(shown),
                                    progress = state.progress, playlistId = p.id,
                                    onOpen = openItem, onFav = { ch -> vm.onStar(ch) }, onLongPress = { ch -> vm.listSheetFor = ch },
                                    focusId = focusId, modifier = m,
                                )
                            }
                        }
                        if (wide && groupsVisible) {
                            Row(Modifier.fillMaxSize()) {
                                GroupColumn(groupMap, base.size, group, locked, selectGroup, { groupMenu = it }, { vm.screen = Screen.GROUPS }, Modifier.width(250.dp).fillMaxHeight())
                                Spacer(Modifier.width(20.dp))
                                content(Modifier.weight(1f).fillMaxHeight())
                            }
                        } else {
                            Column(Modifier.fillMaxSize()) {
                                if (groupsVisible) GroupChips(groupMap, group, locked, selectGroup, { groupMenu = it }) { vm.screen = Screen.GROUPS }
                                content(Modifier.weight(1f).fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
    }

    editList?.let { l -> ListEditDialog(vm, l) { editList = null } }
    if (newList) {
        NewListDialog(onDismiss = { newList = false }) { name ->
            vm.createList(name, null)?.let { vm.savedList = it }
            newList = false
        }
    }

    groupMenu?.let { g ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { groupMenu = null },
            title = { Text(g, fontFamily = Serif) },
            text = { Text("Hidden groups disappear from lists, Home, the guide and the player. You can bring them back in Show & hide groups.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { vm.toggleHiddenGroup(g); groupMenu = null }) { Text("Hide group") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { groupMenu = null; vm.screen = Screen.GROUPS }) { Text("Show & hide groups…") }
            },
        )
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

@Composable
private fun SearchField(value: String, scope: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .panel(RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = Muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(if (scope == "Home") "Search everything" else "Search ${scope.lowercase()}", color = Faint, fontFamily = Sans, fontSize = 15.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Paper, fontFamily = Sans, fontSize = 15.sp),
                cursorBrush = SolidColor(Signal),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
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
private fun GroupColumn(
    groups: Map<String, List<Channel>>,
    total: Int,
    selected: String?,
    locked: Set<String>,
    onSelect: (String?) -> Unit,
    onOptions: (String) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier,
) {
    val names = remember(groups) { groups.keys.toList() }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "__all") { GroupLine("All", total, selected == null, false, {}) { onSelect(null) } }
        items(names, key = { "g:$it" }) { g ->
            GroupLine(g, groups[g]?.size ?: 0, selected == g, g in locked, { onOptions(g) }) { onSelect(g) }
        }
        item(key = "__manage") {
            Text(
                "Show & hide groups",
                fontFamily = Sans,
                fontSize = 13.sp,
                color = Muted,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .focusRing(RoundedCornerShape(6.dp))
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onManage)
                    .padding(horizontal = 13.dp, vertical = 8.dp),
            )
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
private fun GroupChips(
    groups: Map<String, List<Channel>>,
    selected: String?,
    locked: Set<String>,
    onSelect: (String?) -> Unit,
    onOptions: (String) -> Unit,
    onManage: () -> Unit,
) {
    val names = remember(groups) { listOf<String?>(null) + groups.keys }
    LazyRow(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(names, key = { it ?: "__all" }) { g ->
            TagChip(
                if (g != null && g in locked) "🔒 $g" else g ?: "All",
                selected == g,
                onLongClick = if (g != null) ({ onOptions(g) }) else null,
            ) { onSelect(g) }
        }
        item(key = "__manage") { TagChip("Edit groups", false, onClick = onManage) }
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
        columns = GridCells.Adaptive(if (isTv()) 140.dp else 108.dp),
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
) {
    if (list.isEmpty()) {
        Box(modifier.padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            Text(emptyText, color = Muted, textAlign = TextAlign.Center)
        }
        return
    }
    LazyColumn(modifier, state = state, contentPadding = PaddingValues(bottom = 32.dp)) {
        itemsIndexed(list, key = { _, ch -> ch.id }) { i, ch ->
            val current = if (ch.kind == Kind.LIVE) Epg.current(epg[ch.epgKey], now) else null
            val watched = if (ch.kind != Kind.LIVE) progress[progressKey(playlistId, ch.id)] else null
            ChannelRow(i + 1, ch, current, watched, ch.id in favIds, now, { onOpen(ch) }, { onFav(ch) }, { onLongPress(ch) }, ch.id == focusId, Modifier)
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
            AsyncImage(
                model = ch.logo.ifBlank { null },
                contentDescription = null,
                contentScale = if (isLive) ContentScale.Fit else ContentScale.Crop,
                modifier = if (isLive) Modifier.fillMaxSize().padding(5.dp) else Modifier.fillMaxSize(),
            )
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
