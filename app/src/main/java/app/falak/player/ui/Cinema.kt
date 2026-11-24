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
    Row(modifier.fillMaxWidth().height(TOP_MENU_HEIGHT).padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        BrandMark(28.dp)
        Spacer(Modifier.width(12.dp))
        // (Scrolls sideways if a narrow screen can't hold every section: none is ever out of reach.)
        // 8.49: the remote resting on a section opens it; coming up from the page lands on the one open.
        val requesters = remember(sections) { sections.associateWith { androidx.compose.ui.focus.FocusRequester() } }
        val fallback = requesters[current] ?: androidx.compose.ui.focus.FocusRequester.Default
        Row(
            Modifier.weight(1f).focusRestorer(fallback).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Icons (8.42, the owner: "just the logo is enough"): the name shows on the one the remote is on.
            sections.forEach { s ->
                val selected = screen == Screen.LIBRARY && !searching && s == current
                MenuTool(navIcon(s), navLabel(s), selected = selected, mark = true, requester = requesters.getValue(s), openOnFocus = !selected) {
                    cinemaSearchOpen = false
                    vm.openSection(s)
                }
            }
            Spacer(Modifier.width(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            // (Settings, Recordings and the like show the time in their own heading.)
            if (screen == Screen.LIBRARY) Clock(Modifier.padding(start = 8.dp))
        }
    }
}

/** One tool of the menu: its icon, and its name beside it while the remote is on it. */
@Composable
private fun MenuTool(
    icon: Painter,
    label: String,
    selected: Boolean = false,
    dot: Boolean = false,
    enabled: Boolean = true,
    /** A section (8.42): where you are also gets the short orange line under it. */
    mark: Boolean = false,
    requester: androidx.compose.ui.focus.FocusRequester? = null,
    /** A section not open yet (8.49): the remote resting on it opens it. */
    openOnFocus: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    // (8.48) The pill fades in and the name slides out of it; only while the focus moves.
    val lit by androidx.compose.animation.core.animateFloatAsState(
        if (focused) 1f else 0f,
        androidx.compose.animation.core.tween(if (focused) FOCUS_IN_MS else FOCUS_OUT_MS),
        label = "menuTool",
    )
    val tint by androidx.compose.animation.animateColorAsState(
        when {
            focused -> Ink
            selected -> Signal
            !enabled -> Faint
            else -> Paper
        },
        androidx.compose.animation.core.tween(if (focused) FOCUS_IN_MS else FOCUS_OUT_MS),
        label = "menuToolTint",
    )
    val paper = Paper
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Row(
        Modifier
            .height(36.dp)
            .guideAnchor(label)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .openOnRest(openOnFocus && enabled && mark, onClick)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .drawBehind { if (lit > 0.01f) drawRect(paper.copy(alpha = lit)) }
            .semantics { this.selected = selected }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            if (dot) Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(7.dp).background(Signal, CircleShape))
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = focused,
            enter = androidx.compose.animation.expandHorizontally(androidx.compose.animation.core.tween(FOCUS_IN_MS)) +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(FOCUS_IN_MS)),
            exit = androidx.compose.animation.shrinkHorizontally(androidx.compose.animation.core.tween(FOCUS_OUT_MS)) +
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(FOCUS_OUT_MS)),
        ) {
            Row {
                Spacer(Modifier.width(6.dp))
                Text(label, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Ink, maxLines = 1)
            }
        }
    }
    if (mark) {
        Box(
            Modifier
                .padding(top = 2.dp)
                .width(16.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (selected && !focused) Signal else Color.Transparent),
        )
    }
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
