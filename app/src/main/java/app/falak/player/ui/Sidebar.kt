package app.falak.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.rememberCoroutineScope
import app.falak.player.data.mine
import app.falak.player.data.AppState
import kotlinx.coroutines.launch
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.R
import app.falak.player.data.Channel
import app.falak.player.data.Epg
import app.falak.player.data.Kind
import app.falak.player.data.Playlist
import app.falak.player.data.Program
import app.falak.player.data.SourceType
import app.falak.player.data.Watch
import app.falak.player.data.progressKey
import coil.compose.AsyncImage

/*
 * The "Sidebar" look (Settings → Appearance → Look): a side menu instead of tabs, a search bar on
 * top, big "Continue watching" cards, and every section as a grid with filter chips and counts.
 * It changes only the frame and the cards; the lists, search, groups and focus logic are the same
 * as in the classic look, which stays one tap away in Settings.
 */

/** The filter chip that shows only favorites of the open section (not a real group name). */
const val FAV_FILTER = "\u0000favorites"

/** Every film (or series) of the section as a grid: "All" picked on the Films or Series page. */
const val ALL_FILTER = "\u0000all"

/** Poster cards show their group, rating and picture quality under the title (Sidebar look). */
val LocalCardDetails = staticCompositionLocalOf { false }

/** "4K" / "FHD" / "HD" when the name or group says so. */
fun qualityTag(ch: Channel): String? {
    ch.tidyQuality?.let { return it }
    val s = ch.name + " " + ch.group
    return when {
        Regex("(?i)\\b(4k|uhd|2160p?|3840p?)\\b").containsMatchIn(s) -> "4K"
        Regex("(?i)\\b(fhd|1080p?)\\b").containsMatchIn(s) -> "FHD"
        Regex("(?i)\\b(hd|720p?)\\b").containsMatchIn(s) -> "HD"
        else -> null
    }
}

fun countLabel(n: Int): String = String.format(java.util.Locale.US, "%,d", n)

// ---------- the frame: side menu (or drawer on a phone held upright) + top bar ----------

@Composable
fun SidebarFrame(
    vm: LibraryViewModel,
    state: AppState,
    p: Playlist,
    sections: List<Section>,
    section: Section,
    /** The Films or Series front page, when it is the one open (see [MarqueeStage]). */
    landing: Section?,
    excluded: Set<String>,
    onPlay: (List<Channel>, Channel) -> Unit,
    /** There are downloads: a button for their page in the top bar. */
    hasDownloads: Boolean = false,
    body: @Composable (Modifier) -> Unit,
) {
    val tv = isTv()
    val index = vm.index
    val favIds = state.favorites[state.mine(p.id)].orEmpty()
    val recentIds = state.recents[state.mine(p.id)].orEmpty()
    val counts = remember(index, favIds, recentIds) {
        mapOf(
            Section.LIVE to index.live.size,
            Section.MOVIES to index.movies.size,
            Section.SERIES to index.series.size,
            Section.FAVORITES to favIds.size,
            Section.RECENT to recentIds.size,
        )
    }
    val recent = remember(recentIds, index, excluded) {
        recentIds.mapNotNull { index.byId[it] }.filter { it.group !in excluded }.take(6)
    }
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    val hasGuide = index.live.isNotEmpty()
    val openRecent: (Channel) -> Unit = { ch ->
        when {
            ch.isSeriesFolder -> vm.openSeries(ch)
            ch.kind == Kind.MOVIE -> vm.openDetails(ch)
            else -> onPlay(recent.filter { it.kind == Kind.LIVE }, ch)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // TV, tablets and phones on their side keep the menu open; upright phones slide it in.
        val permanent = tv || maxWidth >= 600.dp
        val side: @Composable (Modifier) -> Unit = { m ->
            Sidebar(
                p, sections, section, counts, recent, hasGuide,
                onSelect = { s ->
                    vm.section = s
                    vm.group = null
                    scope.launch { drawer.close() }
                },
                onRecent = { ch ->
                    scope.launch { drawer.close() }
                    openRecent(ch)
                },
                onGuide = { vm.screen = Screen.GUIDE },
                onPlaylists = { vm.screen = Screen.PLAYLISTS },
                onSettings = { vm.screen = Screen.SETTINGS },
                modifier = m,
            )
        }
        // TV, tablets, phones on their side: a slim rail of icons, so the page keeps the room.
        val rail: @Composable (Modifier) -> Unit = { m ->
            SideRail(
                sections, section, hasGuide,
                onSelect = { s ->
                    vm.section = s
                    vm.group = null
                },
                onGuide = { vm.screen = Screen.GUIDE },
                onPlaylists = { vm.screen = Screen.PLAYLISTS },
                onSettings = { vm.screen = Screen.SETTINGS },
                modifier = m,
            )
        }
        val openMenu: () -> Unit = { scope.launch { drawer.open() } }
        val main: @Composable (Modifier) -> Unit = { m ->
            // The Films and Series pages' picture goes behind the top bar too.
            MarqueeStage(vm, landing, excluded, m) {
            Column(Modifier.fillMaxSize().padding(horizontal = if (tv) 28.dp else 16.dp)) {
                SidebarTopBar(
                    query = vm.query,
                    scope = section.label,
                    onQuery = { vm.query = it },
                    onMenu = if (permanent) null else openMenu,
                    hasGuide = hasGuide,
                    loading = vm.loading,
                    onGuide = { vm.screen = Screen.GUIDE },
                    onRefresh = { vm.open(p, refresh = true) },
                    unread = state.notices.count { !it.read },
                    onNotices = { vm.screen = Screen.NOTICES },
                    onDownloads = if (hasDownloads) ({ vm.screen = Screen.DOWNLOADS }) else null,
                    profileButton = if (vm.profilesWanted(state)) {
                        { ProfileButton(state, vm.kidsActive) { vm.choosingProfile = true } }
                    } else {
                        null
                    },
                    kids = vm.kidsActive,
                    onSearchFocus = { vm.searchFocused = it },
                    voiceLang = VoiceSearch.language(state.settings.voiceLang),
                    onVoiceLang = { vm.switchVoiceLanguage() },
                )
                body(Modifier.fillMaxSize())
            }
            }
        }
        CompositionLocalProvider(LocalCardDetails provides true) {
            if (permanent) {
                Row(Modifier.fillMaxSize()) {
                    rail(Modifier.fillMaxHeight())
                    main(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                ModalNavigationDrawer(
                    drawerState = drawer,
                    drawerContent = {
                        ModalDrawerSheet(drawerContainerColor = Ink2, modifier = Modifier.width(290.dp)) { side(Modifier.fillMaxHeight()) }
                    },
                ) { main(Modifier.fillMaxSize()) }
            }
        }
    }
}

// ---------- slim side rail (TV, tablets, phones on their side) ----------

@Composable
private fun railWidth(): Dp = if (isTv()) 76.dp else 64.dp

/**
 * Icons only (no names or counts), so the page gets the width: the sections in the middle, the
 * playlist and Settings at the bottom. The section you're in is orange with a mark at the edge;
 * the one the remote is on turns white.
 */
@Composable
fun SideRail(
    sections: List<Section>,
    selected: Section,
    hasGuide: Boolean,
    onSelect: (Section) -> Unit,
    onGuide: () -> Unit,
    onPlaylists: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tv = isTv()
    Row(modifier.width(railWidth()).background(Ink2)) {
        // Short screens (a phone on its side): the icons shrink so every one stays in view.
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val slots = sections.size + (if (hasGuide) 1 else 0) + 2
            val fixed = (if (tv) 20 + 32 + 14 + 16 else 14 + 28 + 10 + 10) + 9
            val fit = (maxHeight - fixed.dp - 6.dp * (slots + 1)) / slots
            val box = fit.coerceIn(32.dp, if (tv) 46.dp else 44.dp)
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(if (tv) 20.dp else 14.dp))
                BrandMark(if (tv) 32.dp else 28.dp)
                Spacer(Modifier.height(if (tv) 14.dp else 10.dp))
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                ) {
                    sections.forEach { s -> RailIcon(navIcon(s), navLabel(s), s == selected, box) { onSelect(s) } }
                    if (hasGuide) RailIcon(rememberVectorPainter(Icons.Outlined.DateRange), "TV guide", false, box, onClick = onGuide)
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // A short rule sets the playlist and Settings apart from the sections.
                    Box(Modifier.padding(vertical = 4.dp).width(24.dp).height(1.dp).background(Hairline))
                    RailIcon(painterResource(R.drawable.ic_nav_playlists), "Playlists", false, box, onClick = onPlaylists)
                    RailIcon(rememberVectorPainter(Icons.Outlined.Settings), "Settings", false, box, onClick = onSettings)
                }
                Spacer(Modifier.height(if (tv) 16.dp else 10.dp))
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Hairline))
    }
}

/** One square icon button of the rail (its name is read out by TalkBack, not shown). */
@Composable
private fun RailIcon(icon: Painter, label: String, selected: Boolean, box: Dp, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(box * 0.3f)
    val bg by animateColorAsState(
        when {
            focused -> Paper
            selected -> Signal.copy(alpha = 0.16f)
            else -> Color.Transparent
        },
        tween(150),
        label = "railIconBg",
    )
    val tint = when {
        focused -> Ink
        selected -> Signal
        else -> Muted
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // The section you're in: a short orange mark on the rail's edge.
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                    .background(Signal),
            )
        }
        Box(
            Modifier
                .size(box)
                .onFocusChanged { focused = it.isFocused }
                .clip(shape)
                .background(bg)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size((box * 0.52f).coerceIn(18.dp, 24.dp)))
        }
    }
}

// ---------- side menu (the drawer on phones held upright) ----------

@Composable
internal fun navIcon(s: Section): Painter = when (s) {
    Section.HOME -> rememberVectorPainter(Icons.Outlined.Home)
    Section.LIVE -> painterResource(R.drawable.ic_nav_live)
    Section.MOVIES -> painterResource(R.drawable.ic_nav_films)
    Section.SERIES -> painterResource(R.drawable.ic_nav_series)
    Section.FAVORITES -> rememberVectorPainter(Icons.Outlined.Star)
    Section.RECENT -> painterResource(R.drawable.ic_nav_recent)
}

internal fun navLabel(s: Section): String = if (s == Section.LIVE) "Live TV" else s.label

@Composable
fun Sidebar(
    playlist: Playlist,
    sections: List<Section>,
    selected: Section,
    counts: Map<Section, Int>,
    recent: List<Channel>,
    hasGuide: Boolean,
    onSelect: (Section) -> Unit,
    onRecent: (Channel) -> Unit,
    onGuide: () -> Unit,
    onPlaylists: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tv = isTv()
    Column(modifier.fillMaxHeight().background(Ink2)) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = if (tv) 22.dp else 14.dp, bottom = 12.dp),
        ) {
            item(key = "brand") {
                Row(Modifier.padding(start = 6.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    BrandMark(if (tv) 34.dp else 30.dp)
                    Spacer(Modifier.width(10.dp))
                    Wordmark(if (tv) 21 else 19)
                }
            }
            items(sections, key = { "nav:" + it.name }) { s ->
                NavItem(navIcon(s), navLabel(s), counts[s], s == selected) { onSelect(s) }
            }
            if (hasGuide) {
                item(key = "guide") { NavItem(rememberVectorPainter(Icons.Outlined.DateRange), "TV guide", null, false, onClick = onGuide) }
            }
            if (recent.isNotEmpty()) {
                item(key = "recentTitle") {
                    Box(Modifier.padding(start = 10.dp, top = 22.dp, bottom = 6.dp)) { Kicker("Recently watched", color = Faint) }
                }
                items(recent, key = { "recent:" + it.id }) { ch -> RecentItem(ch, onClick = { onRecent(ch) }) }
            }
        }
        // The playlist and Settings stay at the bottom, however long the menu gets.
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val shape = RoundedCornerShape(10.dp)
            Row(
                Modifier
                    .weight(1f)
                    .focusRing(shape)
                    .clip(shape)
                    .background(Ink3)
                    .clickable(onClick = onPlaylists)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(playlist.name, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when (playlist.type) {
                            SourceType.XTREAM -> "Xtream"
                            SourceType.M3U_URL -> "M3U link"
                            SourceType.M3U_FILE -> "M3U file"
                        } + "  ·  Switch",
                        fontFamily = Sans,
                        fontSize = 11.sp,
                        color = Faint,
                        maxLines = 1,
                    )
                }
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = Muted)
            }
        }
    }
}

@Composable
internal fun NavItem(
    icon: Painter,
    label: String,
    count: Int?,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val bg by animateColorAsState(if (selected) Ink3 else Color.Transparent, tween(180), label = "navBg")
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .focusRing(shape)
            .clip(shape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) Signal else Muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            fontFamily = Sans,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) Paper else Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count != null && count > 0) {
            Text(countLabel(count), style = Tabular.copy(fontSize = 11.sp), color = Faint)
        }
    }
}

@Composable
private fun RecentItem(ch: Channel, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val live = ch.kind == Kind.LIVE
    Row(
        Modifier
            .fillMaxWidth()
            .focusRing(shape)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(if (live) 34.dp else 24.dp, if (live) 22.dp else 34.dp).clip(RoundedCornerShape(4.dp)).background(Ink3),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = ch.logo.ifBlank { null },
                contentDescription = null,
                contentScale = if (live) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize().padding(if (live) 2.dp else 0.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(ch.name, fontFamily = Sans, fontSize = 13.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (live) {
            Spacer(Modifier.width(6.dp))
            LiveDot(6.dp)
        }
    }
}

// ---------- top bar ----------

@Composable
fun SidebarTopBar(
    query: String,
    scope: String,
    onQuery: (String) -> Unit,
    onMenu: (() -> Unit)?,
    hasGuide: Boolean,
    loading: Boolean,
    onGuide: () -> Unit,
    onRefresh: () -> Unit,
    unread: Int = 0,
    onNotices: () -> Unit = {},
    /** Who's watching (when there are profiles). */
    profileButton: (@Composable () -> Unit)? = null,
    kids: Boolean = false,
    /** The search box gained or lost the focus. */
    onSearchFocus: (Boolean) -> Unit = {},
    /** Voice search's language ("ar" / "en"), and switching it. */
    voiceLang: String = "en",
    onVoiceLang: () -> Unit = {},
    /** The Downloads page, when there are downloads. */
    onDownloads: (() -> Unit)? = null,
) {
    val tv = isTv()
    Row(Modifier.fillMaxWidth().padding(top = if (tv) 18.dp else 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onMenu != null) {
            IconButton(onClick = onMenu, modifier = Modifier.padding(end = 4.dp)) {
                Icon(Icons.Outlined.Menu, contentDescription = "Menu", tint = Paper)
            }
        }
        val shape = RoundedCornerShape(12.dp)
        Row(
            // Takes the free width up to a comfortable 560 dp; the buttons stay at the far right.
            Modifier
                .weight(1f)
                .widthIn(max = 560.dp)
                .panel(shape)
                .padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = Faint, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                if (query.isEmpty()) {
                    Text(
                        if (scope == "Home") "Search channels, films and series" else "Search ${scope.lowercase()}",
                        color = Faint,
                        fontFamily = Sans,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(color = Paper, fontFamily = Sans, fontSize = 14.sp),
                    cursorBrush = SolidColor(Signal),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { onSearchFocus(it.isFocused) },
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Clear search", tint = Muted, modifier = Modifier.size(18.dp))
                }
            }
            // The microphone while the box is empty (typed text keeps the room).
            if (query.isEmpty()) LocalVoiceSearch.current?.let { speak -> VoiceButtons(voiceLang, speak, onVoiceLang) }
        }
        Row(Modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tv) Clock(Modifier.padding(horizontal = 8.dp))
            if (hasGuide && onMenu != null) {
                IconButton(onClick = onGuide) { Icon(Icons.Outlined.DateRange, contentDescription = "TV guide", tint = Paper) }
            }
            profileButton?.invoke()
            if (onDownloads != null) {
                IconButton(onClick = onDownloads) { Icon(downloadIcon(), contentDescription = "Downloads", tint = Paper) }
            }
            if (!kids) NoticeBell(unread, onNotices)
            IconButton(onClick = onRefresh, enabled = !loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh playlist", tint = Paper)
            }
        }
    }
}

// ---------- section header, sort, view, filters ----------

@Composable
fun SectionHead(
    title: String,
    count: Int,
    unit: String,
    sorts: List<Sort>,
    sort: Sort,
    onSort: (Sort) -> Unit,
    grid: Boolean?,
    onGrid: (Boolean) -> Unit,
) {
    val tv = isTv()
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                fontFamily = Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (tv) 26.sp else 22.sp,
                color = Paper,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "${countLabel(count)} $unit",
                style = Tabular.copy(fontSize = 12.sp),
                color = Muted,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.background(Ink3, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 3.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        if (sorts.size > 1) SortMenu(sorts, sort, onSort)
        if (grid != null) {
            Spacer(Modifier.width(8.dp))
            ViewToggle(grid, onGrid)
        }
    }
}

@Composable
private fun SortMenu(sorts: List<Sort>, sort: Sort, onSort: (Sort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box {
        Row(
            Modifier
                .focusRing(shape)
                .clip(shape)
                .border(1.dp, Hairline, shape)
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_sort), contentDescription = "Sort", tint = Muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            if (isTv()) {
                Text("Sort", fontFamily = Sans, fontSize = 12.sp, color = Faint)
                Spacer(Modifier.width(6.dp))
            }
            Text(sort.label, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Paper, maxLines = 1)
            Text("  ▾", fontSize = 11.sp, color = Muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sorts.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label, fontWeight = if (s == sort) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = {
                        onSort(s)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ViewToggle(grid: Boolean, onGrid: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.border(1.dp, Hairline, shape).padding(3.dp)) {
        listOf(true to R.drawable.ic_view_grid, false to R.drawable.ic_view_list).forEach { (g, icon) ->
            val sel = g == grid
            val inner = RoundedCornerShape(7.dp)
            Box(
                Modifier
                    .size(34.dp, 30.dp)
                    .focusRing(inner)
                    .clip(inner)
                    .background(if (sel) Ink3 else Color.Transparent)
                    .clickable { onGrid(g) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(icon),
                    contentDescription = if (g) "Show as grid" else "Show as list",
                    tint = if (sel) Paper else Faint,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

/** "All 1,452", "★ Favorites 3", then each group with how many items it has. */
@Composable
fun FilterChips(
    groups: Map<String, List<Channel>>,
    total: Int,
    favorites: Int,
    selected: String?,
    locked: Set<String>,
    onSelect: (String?) -> Unit,
    onOptions: (String) -> Unit,
    onManage: () -> Unit,
) {
    val names = remember(groups) { groups.keys.toList() }
    LazyRow(
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        item(key = "__all") { CountChip("All", total, selected == null) { onSelect(null) } }
        if (favorites > 0) {
            item(key = "__fav") { CountChip("★ Favorites", favorites, selected == FAV_FILTER) { onSelect(FAV_FILTER) } }
        }
        items(names, key = { "g:$it" }) { g ->
            CountChip(
                if (g in locked) "🔒 $g" else g,
                if (g in locked) null else groups[g]?.size,
                selected == g,
                onLongClick = { onOptions(g) },
            ) { onSelect(g) }
        }
        item(key = "__manage") { CountChip("Edit groups", null, false, onClick = onManage) }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun CountChip(label: String, count: Int?, selected: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val bg by animateColorAsState(if (selected) Paper else Ink3, tween(200), label = "countChipBg")
    val fg = if (selected) Ink else Paper
    Row(
        Modifier
            .focusRing(shape)
            .clip(shape)
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontFamily = Sans,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp),
        )
        if (count != null) {
            Spacer(Modifier.width(7.dp))
            Text(countLabel(count), style = Tabular.copy(fontSize = 11.sp), color = fg.copy(alpha = 0.55f), maxLines = 1)
        }
    }
}

// ---------- big "Continue watching" cards ----------

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun ContinueRow(
    items: List<Channel>,
    art: Map<String, String>,
    progress: Map<String, Watch>,
    playlistId: String,
    title: String = "Continue watching",
    width: Dp? = null,
    onOpen: (Channel) -> Unit,
) {
    val tv = isTv()
    val scroller = LocalHomeScroller.current
    val rowState = LocalRowStates.current?.invoke(title) ?: androidx.compose.foundation.lazy.rememberLazyListState()
    Column(Modifier.fillMaxWidth().pinToTop().padding(top = 6.dp)) {
        Box(Modifier.padding(start = 4.dp, bottom = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Paper)
        }
        // On a TV page that places whole rows itself (see HomeScroller), the row still scrolls
        // sideways to the card the remote moves to.
        CompositionLocalProvider(
            LocalBringIntoViewSpec provides (scroller?.inner ?: LocalBringIntoViewSpec.current),
            LocalRow provides title,
        ) {
            LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
                items(items, key = { "cw:" + it.id }) { ch ->
                    ContinueCard(ch, art[ch.id], progress[progressKey(playlistId, ch.id)], width ?: if (tv) 320.dp else 270.dp) { onOpen(ch) }
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(ch: Channel, backdrop: String?, watched: Watch?, width: Dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val row = LocalRow.current
    val tap = LocalCardTap.current
    Box(
        Modifier
            .width(width)
            .aspectRatio(16f / 9f)
            .comeBackHere(row, ch.id)
            .cardFocus(shape)
            .clip(shape)
            .background(Ink3)
            .clickable(onClick = { tap?.invoke(row, ch.id); onClick() }),
    ) {
        val picture = backdrop?.takeIf { it.isNotBlank() } ?: ch.logo.ifBlank { null }
        if (picture != null) {
            AsyncImage(
                model = picture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = if (backdrop.isNullOrBlank()) Alignment.TopCenter else Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xE6000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
            Text(ch.name, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val left = watched?.takeIf { it.dur > 0 }?.let { ((it.dur - it.pos) / 60_000).coerceAtLeast(1) }
            Text(
                listOfNotNull(ch.info.ifBlank { null }, left?.let { "$it min left" }).joinToString("  ·  ").ifBlank { ch.group },
                fontFamily = Sans,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (watched != null && watched.dur > 0) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(Color(0x55FFFFFF))) {
                Box(Modifier.fillMaxWidth((watched.pos.toFloat() / watched.dur).coerceIn(0f, 1f)).height(3.dp).background(Signal))
            }
        }
    }
}

// ---------- live channels as a grid ----------

@Composable
fun ChannelGrid(
    list: List<Channel>,
    epg: Map<String, List<Program>>,
    now: Long,
    state: LazyGridState,
    onOpen: (Channel) -> Unit,
    onLongPress: (Channel) -> Unit,
    modifier: Modifier,
) {
    if (list.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("Nothing here yet", color = Muted) }
        return
    }
    // The remote goes round: down on the last row comes back to the first channel.
    val loop = rememberFocusLoop()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (isTv()) 210.dp else 160.dp),
        state = state,
        modifier = modifier.focusLoop(loop, state, list.size),
        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(list, key = { _, ch -> ch.id }) { i, ch ->
            Box(Modifier.loopItem(loop, i)) {
                ChannelCard(
                    ch, if (ch.kind == Kind.LIVE) Epg.current(epg[ch.epgKey], now) else null, now,
                    onClick = { onOpen(ch) }, width = null, onLongClick = { onLongPress(ch) },
                )
            }
        }
    }
}

/** Small badge in a card's corner ("4K", "NEW"). */
@Composable
fun CardBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        fontFamily = Sans,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        color = color,
        modifier = modifier
            .background(Color(0xCC000000), RoundedCornerShape(5.dp))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
