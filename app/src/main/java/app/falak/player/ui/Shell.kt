package app.falak.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.R
import app.falak.player.data.AppState
import app.falak.player.data.mine
import app.falak.player.record.RecState

/*
 * The app's menu around its pages (Sidebar look, with a playlist open): a slim rail of icons on
 * the left (TV, tablets, phones on their side) or a bar along the bottom (phones held upright:
 * Home, Live, Films, Series, More). Settings, Recordings, Downloads and What's new open inside
 * it, so the menu never goes away while you move between them.
 */

/** Where the menu is: a rail on the left, a bar at the bottom, or none (classic look, full pages). */
enum class ShellMode { NONE, RAIL, BAR }

/** The menu around the page on show; pages use it for Back (to More) and to leave out what the menu already has. */
val LocalShell = staticCompositionLocalOf { ShellMode.NONE }

/** Height of the bottom bar (phones held upright). */
val BAR_HEIGHT = 64.dp

/** The pages drawn inside the menu: the lists, More, and the pages opened from them. */
val SHELL_SCREENS: Set<Screen> = setOf(Screen.LIBRARY, Screen.MORE, Screen.RECORDINGS, Screen.DOWNLOADS, Screen.NOTICES) + SETTINGS_SCREENS

/** The menu for [screen]: none outside the Sidebar look, before a playlist is open, or on full-screen pages. */
fun shellMode(look: String, hasPlaylist: Boolean, screen: Screen, tv: Boolean, width: Dp): ShellMode = when {
    look != "sidebar" || !hasPlaylist || screen !in SHELL_SCREENS -> ShellMode.NONE
    tv || width >= 600.dp -> ShellMode.RAIL
    else -> ShellMode.BAR
}

/** The sections the open playlist has (Home, Live, Films, Series; Favorites and Recent once used). */
@Composable
fun rememberSections(vm: LibraryViewModel, state: AppState): List<Section> {
    val p = vm.playlist
    val index = vm.index
    val hasFav = p != null && (state.favorites[state.mine(p.id)].orEmpty().isNotEmpty() || state.favLists[state.mine(p.id)].orEmpty().isNotEmpty())
    val hasRecent = p != null && state.recents[state.mine(p.id)].orEmpty().isNotEmpty()
    return remember(index, hasFav, hasRecent) {
        Section.entries.filter { s ->
            when (s) {
                Section.HOME -> index.all.isNotEmpty()
                Section.FAVORITES -> hasFav
                Section.RECENT -> hasRecent
                else -> s.kind != null && index.ofKind(s.kind).isNotEmpty()
            }
        }
    }
}

/** "Good morning", "Good afternoon" or "Good evening" (the TV's Home). */
fun greeting(hour: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

/**
 * The page with the menu beside or under it. The page keeps its place in the composition whatever
 * the menu does, so turning a phone (bar ↔ rail) doesn't reset it.
 */
@Composable
fun AppShell(vm: LibraryViewModel, state: AppState, mode: ShellMode, content: @Composable () -> Unit) {
    val start = if (mode == ShellMode.RAIL) railWidth() else 0.dp
    val bottom = if (mode == ShellMode.BAR) BAR_HEIGHT else 0.dp
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(start = start, bottom = bottom)) {
            CompositionLocalProvider(LocalShell provides mode) { content() }
        }
        when (mode) {
            ShellMode.RAIL -> ShellRail(vm, state, Modifier.align(Alignment.CenterStart).fillMaxHeight())
            ShellMode.BAR -> BottomBar(vm, state, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            ShellMode.NONE -> Unit
        }
    }
}

/** Opens a section of the lists (from Settings, Recordings… too). */
private fun LibraryViewModel.openSection(s: Section) {
    screen = Screen.LIBRARY
    section = s
    group = null
}

@Composable
private fun ShellRail(vm: LibraryViewModel, state: AppState, modifier: Modifier) {
    val sections = rememberSections(vm, state)
    val screen = vm.screen
    val current = vm.section.takeIf { it in sections } ?: sections.firstOrNull()
    val recordingNow = rememberRecordings().any { it.state == RecState.RECORDING }
    val hasLive = vm.index.live.isNotEmpty()
    SideRail(
        sections = sections,
        selected = if (screen == Screen.LIBRARY) current else null,
        hasGuide = hasLive,
        onSelect = { s -> vm.openSection(s) },
        onGuide = { vm.screen = Screen.GUIDE },
        onPlaylists = { vm.screen = Screen.PLAYLISTS },
        onSettings = { vm.screen = Screen.SETTINGS },
        settingsOpen = screen in SETTINGS_SCREENS,
        // Recordings: live channels to record, and never in the Kids profile.
        onRecordings = if (hasLive && !vm.kidsActive) ({ vm.screen = Screen.RECORDINGS }) else null,
        recordingsOpen = screen == Screen.RECORDINGS,
        recordingNow = recordingNow,
        modifier = modifier,
    )
}

// ---------- the bottom bar (phones held upright) ----------

private val BAR_SECTIONS = listOf(Section.HOME, Section.LIVE, Section.MOVIES, Section.SERIES)

@Composable
private fun BottomBar(vm: LibraryViewModel, state: AppState, modifier: Modifier) {
    val sections = rememberSections(vm, state)
    val tabs = BAR_SECTIONS.filter { it in sections }
    val current = vm.section.takeIf { it in sections } ?: sections.firstOrNull()
    // Favorites, Recent and the pages opened from More light up More.
    val selected = current.takeIf { vm.screen == Screen.LIBRARY && it in tabs }
    val unread = if (vm.kidsActive) 0 else state.notices.count { !it.read }
    val recordingNow = rememberRecordings().any { it.state == RecState.RECORDING }
    Column(modifier.background(Ink2)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
        Row(Modifier.fillMaxWidth().height(BAR_HEIGHT - 1.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { s ->
                BarTab(
                    icon = { tint -> Icon(navIcon(s), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)) },
                    label = s.label,
                    selected = s == selected,
                    modifier = Modifier.weight(1f),
                ) { vm.openSection(s) }
            }
            BarTab(
                icon = { tint -> MoreDots(tint) },
                label = "More",
                selected = selected == null,
                // A red dot: something new in What's new, or a recording under way.
                dot = unread > 0 || recordingNow,
                modifier = Modifier.weight(1f),
            ) { vm.screen = Screen.MORE }
        }
    }
}

@Composable
private fun BarTab(
    icon: @Composable (Color) -> Unit,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    dot: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (selected) Signal else Muted
    Column(
        modifier
            .fillMaxHeight()
            .focusRing(RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            // TalkBack says which tab is open.
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab, onClickLabel = label, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            icon(tint)
            if (dot) Box(Modifier.align(Alignment.TopEnd).padding(start = 18.dp).size(7.dp).clip(CircleShape).background(Signal))
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            fontFamily = Sans,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) Paper else Muted,
            maxLines = 1,
        )
    }
}

/** Three dots side by side: More. */
@Composable
private fun MoreDots(tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        val r = size.minDimension / 11f
        val y = size.height / 2f
        for (i in -1..1) drawCircle(tint, r, Offset(size.width / 2f + i * size.width / 3.4f, y))
    }
}

// ---------- More (phones held upright) ----------

/** What the bottom bar has no room for: your lists, the guide, recordings, downloads, What's new, playlists, Settings. */
@Composable
fun MoreScreen(vm: LibraryViewModel, state: AppState) {
    val p = vm.playlist ?: return
    BackHandler { vm.screen = Screen.LIBRARY }
    val sections = rememberSections(vm, state)
    val favCount = remember(state.favorites, state.favLists, p.id) {
        (state.favorites[state.mine(p.id)].orEmpty() + state.favLists[state.mine(p.id)].orEmpty().flatMap { it.ids }).toSet().size
    }
    val recordings = rememberRecordings()
    val recordingNow = recordings.any { it.state == RecState.RECORDING }
    val savedRecordings = recordings.count { it.state == RecState.DONE }
    val downloads = rememberDownloads()
    val unread = state.notices.count { !it.read }
    val hasLive = vm.index.live.isNotEmpty()
    val kids = vm.kidsActive
    // A page opened from here: Back there comes back here.
    fun open(screen: Screen) {
        vm.backToMore = true
        vm.screen = screen
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
    ) {
        item(key = "head") {
            Column(Modifier.padding(start = 4.dp, bottom = 14.dp)) {
                Text("More", fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, color = Paper)
                Text(p.name, fontFamily = Sans, fontSize = 13.sp, color = Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (Section.FAVORITES in sections) {
            item(key = "fav") {
                MoreRow(rememberVectorPainter(Icons.Outlined.Star), "Favorites & lists", count = favCount) { vm.openSection(Section.FAVORITES) }
            }
        }
        if (hasLive) {
            item(key = "guide") { MoreRow(rememberVectorPainter(Icons.Outlined.DateRange), "TV guide") { open(Screen.GUIDE) } }
        }
        if (Section.RECENT in sections) {
            item(key = "recent") { MoreRow(painterResource(R.drawable.ic_nav_recent), "Recently watched") { vm.openSection(Section.RECENT) } }
        }
        if (hasLive && !kids) {
            item(key = "rec") {
                MoreRow(
                    painterResource(R.drawable.ic_pl_record),
                    "Recordings",
                    count = savedRecordings.takeIf { !recordingNow },
                    badge = if (recordingNow) "REC" else null,
                ) { open(Screen.RECORDINGS) }
            }
        }
        if (!kids) {
            item(key = "downloads") {
                MoreRow(rememberVectorPainter(downloadIcon()), "Downloads", count = downloads.size) { open(Screen.DOWNLOADS) }
            }
            item(key = "news") {
                MoreRow(rememberVectorPainter(Icons.Outlined.Notifications), "What's new", badge = if (unread > 0) unread.toString() else null) { open(Screen.NOTICES) }
            }
        }
        item(key = "gap") { Spacer(Modifier.height(14.dp)) }
        item(key = "playlists") {
            MoreRow(painterResource(R.drawable.ic_nav_playlists), "Playlists & devices", detail = p.name) { vm.screen = Screen.PLAYLISTS }
        }
        item(key = "settings") { MoreRow(rememberVectorPainter(Icons.Outlined.Settings), "Settings") { open(Screen.SETTINGS) } }
    }
}

@Composable
private fun MoreRow(
    icon: Painter,
    title: String,
    count: Int? = null,
    badge: String? = null,
    detail: String? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .focusRing(shape)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, fontFamily = Sans, fontSize = 12.sp, color = Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (badge != null) {
            Text(
                badge,
                fontFamily = Sans,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = OnSignal,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Signal).padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(8.dp))
        } else if (count != null && count > 0) {
            Text(countLabel(count), style = Tabular.copy(fontSize = 13.sp), color = Faint)
            Spacer(Modifier.width(8.dp))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Faint, modifier = Modifier.size(20.dp))
    }
}
