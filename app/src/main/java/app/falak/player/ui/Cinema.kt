package app.falak.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.R
import app.falak.player.data.Addons
import app.falak.player.data.AppState
import app.falak.player.record.RecState

/*
 * The Cinema look (8.18; Settings → Appearance → Look, this device's). What differs from Spotlight:
 *  - TV: the menu is words along the top of the screen (ShellMode.TOP) instead of the rail, with
 *    the tools (search, guide, recordings…) as icons beside them that show their name while the
 *    remote is on them. The page's own top bar is only drawn while searching.
 *  - TV, Home / Films / Series: the picture fills the screen behind everything; the panel under
 *    the menu (CinemaPanel, Spotlight.kt) shows the card the remote is on, with Play, More info and
 *    My list; the rows move under it.
 *  - Everywhere: "Continue watching" is a row of big cards named "Up next", on Home with the
 *    live channel watched last in it.
 *  - A phone held upright: a taller picture and a My list button; the bar along the bottom stays.
 * Tablets and phones on their side keep the rail. Live TV, the player, Settings and the other
 * pages are the same in both looks.
 */

/** The height of the menu along the top of a TV (the Cinema look). */
val TOP_MENU_HEIGHT = 56.dp

/** The Cinema look on a TV: the search box is open under the menu (it is also shown while there is something typed). */
internal var cinemaSearchOpen by mutableStateOf(false)

/**
 * The menu along the top of a TV: the playlist's sections as words (where you are is underlined;
 * the one the remote is on turns white), then the tools as icons. No background of its own: the
 * page's picture shows behind it.
 */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun TopMenu(vm: LibraryViewModel, state: AppState, modifier: Modifier = Modifier) {
    val sections = rememberSections(vm, state)
    val screen = vm.screen
    val current = vm.section.takeIf { it in sections } ?: sections.firstOrNull()
    val searching = screen == Screen.LIBRARY && (cinemaSearchOpen || vm.query.isNotEmpty())
    val playlist = vm.playlist
    val hasLive = vm.index.live.isNotEmpty()
    val kids = vm.kidsActive
    val recordingNow = rememberRecordings().any { it.state == RecState.RECORDING }
    val hasDownloads = !kids && rememberDownloads().isNotEmpty()
    val unread = if (kids) 0 else state.notices.count { !it.read }
    // 8.55 (the owner picked "C, floating dock"): the logo on the left, every section and tool as an icon in
    // one see-through capsule in the middle, the clock on the right. No names: the icons speak for themselves.
    Row(modifier.fillMaxWidth().height(TOP_MENU_HEIGHT).padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        BrandMark(28.dp)
        Box(Modifier.weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Row(
                Modifier
                    .glass(RoundedCornerShape(50))
                    // (Scrolls sideways if a narrow screen can't hold every icon: none is ever out of reach.)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 8.49: the remote resting on a section opens it; coming up from the page lands on the one open.
                val requesters = remember(sections) { sections.associateWith { androidx.compose.ui.focus.FocusRequester() } }
                val fallback = requesters[current] ?: androidx.compose.ui.focus.FocusRequester.Default
                Row(
                    Modifier.focusRestorer(fallback).notesSideways(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    sections.forEach { s ->
                        val selected = screen == Screen.LIBRARY && !searching && s == current
                        // (Not while searching, 8.51: passing over the sections lost the search and its results.)
                        MenuTool(navIcon(s), navLabel(s), selected = selected, mark = true, requester = requesters.getValue(s), openOnFocus = !selected && !searching) {
                            cinemaSearchOpen = false
                            vm.openSection(s)
                        }
                    }
                }
                Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(22.dp).background(Paper.copy(alpha = 0.25f)))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    MenuTool(painterResource(R.drawable.ic_nav_search), "Search", selected = searching) {
                        // On the lists: their own search (this section's). From any other page (8.48): everything.
                        if (screen == Screen.LIBRARY) cinemaSearchOpen = true else vm.openSearch()
                    }
                    if (hasLive) MenuTool(painterResource(R.drawable.ic_nav_guide), "TV guide") { vm.screen = Screen.GUIDE }
                    // Recordings and Add-ons: never in the Kids profile (as on the rail).
                    if (hasLive && !kids) {
                        MenuTool(painterResource(R.drawable.ic_pl_record), "Recordings", selected = screen == Screen.RECORDINGS, dot = recordingNow) {
                            vm.screen = Screen.RECORDINGS
                        }
                    }
                    if (Addons.enabled && !kids) {
                        MenuTool(painterResource(R.drawable.ic_nav_addons), "Add-ons", selected = screen == Screen.ADDONS) {
                            vm.addons.returnTo = null
                            vm.screen = Screen.ADDONS
                        }
                    }
                    if (hasDownloads) {
                        MenuTool(rememberVectorPainter(downloadIcon()), "Downloads", selected = screen == Screen.DOWNLOADS) { vm.screen = Screen.DOWNLOADS }
                    }
                    if (!kids) {
                        MenuTool(rememberVectorPainter(Icons.Outlined.Notifications), "What's new", selected = screen == Screen.NOTICES, dot = unread > 0) {
                            vm.screen = Screen.NOTICES
                        }
                    }
                    if (playlist != null) {
                        MenuTool(rememberVectorPainter(Icons.Outlined.Refresh), "Update playlist", enabled = !vm.loading) { vm.open(playlist, refresh = true) }
                    }
                    MenuTool(painterResource(R.drawable.ic_nav_playlists), "Playlists") { vm.screen = Screen.PLAYLISTS }
                    MenuTool(rememberVectorPainter(Icons.Outlined.Settings), "Settings", selected = screen in SETTINGS_SCREENS) { vm.screen = Screen.SETTINGS }
                    if (vm.profilesWanted(state)) ProfileButton(state, kids) { vm.choosingProfile = true }
                }
            }
        }
        // (Settings, Recordings and the like show the time in their own heading.)
        if (screen == Screen.LIBRARY) Clock() else Spacer(Modifier.width(28.dp))
    }
}

/**
 * One icon of the menu (8.55: no name, the owner): a see-through circle grows round it while the remote is on
 * it, and a small dot under it marks the section that is open. Its name is read out (contentDescription).
 */
@Composable
private fun MenuTool(
    icon: Painter,
    label: String,
    selected: Boolean = false,
    dot: Boolean = false,
    enabled: Boolean = true,
    /** A section (8.42): where you are also gets the small orange dot under it. */
    mark: Boolean = false,
    requester: androidx.compose.ui.focus.FocusRequester? = null,
    /** A section not open yet (8.49): the remote resting on it opens it. */
    openOnFocus: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // (8.48) Only while the focus moves; read in the draw phase.
    val lit by androidx.compose.animation.core.animateFloatAsState(
        if (focused) 1f else 0f,
        androidx.compose.animation.core.tween(if (focused) FOCUS_IN_MS else FOCUS_OUT_MS),
        label = "menuTool",
    )
    val paper = Paper
    val signal = Signal
    val markIt = mark && selected
    Box(
        Modifier
            .size(40.dp)
            .guideAnchor(label)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .openOnRest(openOnFocus && enabled && mark, onClick)
            .onFocusChanged { focused = it.isFocused }
            .drawBehind {
                if (lit > 0.01f) {
                    drawCircle(paper.copy(alpha = GLASS_LIT_FILL * lit))
                    drawCircle(paper.copy(alpha = 0.8f * lit), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                }
                if (markIt) drawCircle(signal, radius = 2.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height - 4.dp.toPx()))
            }
            .clip(CircleShape)
            .semantics { this.selected = selected }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) paper.copy(alpha = if (focused || selected) 1f else 0.82f) else Faint,
            modifier = Modifier.size(20.dp),
        )
        if (dot) Box(Modifier.align(Alignment.TopEnd).offset(x = (-7).dp, y = 7.dp).size(7.dp).background(Signal, CircleShape))
    }
}

/** Settings → Appearance → Look: one of the two looks, with a small drawing of it. */
@Composable
internal fun LookOption(label: String, about: String, selected: Boolean, cinema: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val block = RoundedCornerShape(3.dp)
    Column(
        modifier
            .focusRing(shape)
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) Signal else Hairline, shape)
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(8.dp)).background(if (cinema) Ink3 else Ink2).padding(7.dp)) {
            if (cinema) {
                // Words along the top, a name over a full picture, big cards along the bottom.
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(4) { i -> Box(Modifier.width(14.dp).height(3.dp).clip(block).background(if (i == 0) Paper else Faint)) }
                    }
                    Box(Modifier.width(54.dp).height(7.dp).clip(block).background(Paper))
                    Row(Modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(3) { Box(Modifier.weight(1f).fillMaxHeight().clip(block).background(Ink)) }
                    }
                }
            } else {
                // The menu at the side, a panel at the top, rows of cards under it.
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.width(9.dp).fillMaxHeight().clip(block).background(Ink3))
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.fillMaxWidth(0.6f).height(22.dp).clip(block).background(Ink3))
                        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            repeat(4) { i -> Box(Modifier.weight(1f).fillMaxHeight().clip(block).background(if (i == 0) Paper else Ink3)) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, fontFamily = Sans, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Paper else Muted, maxLines = 1, modifier = Modifier.padding(start = 2.dp))
        Text(about, fontFamily = Sans, fontSize = 12.sp, lineHeight = 16.sp, color = Muted, modifier = Modifier.padding(start = 2.dp, top = 2.dp))
    }
}
