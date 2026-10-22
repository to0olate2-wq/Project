package app.falak.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.data.AppState
import app.falak.player.data.Channel
import app.falak.player.data.Kind
import app.falak.player.data.Notice
import app.falak.player.data.NoticeGroup
import app.falak.player.data.progressKey
import kotlinx.coroutines.delay

/**
 * What's new: every playlist refresh that found new channels, films or series, newest first, with
 * the groups they went into and a few of them to open straight away.
 */
@Composable
fun NoticesScreen(vm: LibraryViewModel, state: AppState, onPlay: (List<Channel>, Channel) -> Unit) {
    val back: () -> Unit = { if (!vm.leaveDetour()) vm.screen = if (vm.playlist != null) Screen.LIBRARY else Screen.PLAYLISTS }
    BackHandler { back() }
    val notices = state.notices
    // Seen: the bell's count goes once the page has been looked at for a moment.
    LaunchedEffect(notices.count { !it.read }) {
        delay(1_500)
        vm.markNoticesRead()
    }
    Column(Modifier.fillMaxSize().padding(horizontal = pageMargin())) {
        PageHeader(
            "What's new",
            onBack = back,
            subtitle = "New in your playlists",
            actions = {
                if (notices.isNotEmpty()) TextButton(onClick = { vm.clearNotices() }) { Text("Clear") }
            },
        )
        if (notices.isEmpty()) {
            Centered {
                Text("Nothing new yet", fontFamily = Serif, fontSize = 20.sp, color = Paper)
                Spacer(Modifier.height(6.dp))
                Text(
                    "When a playlist refresh finds new channels, films or series, they appear here and as a notification.",
                    color = Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(top = 6.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(notices, key = { it.id }) { n -> NoticeCard(vm, state, n, onPlay) }
        }
    }
}

@Composable
private fun NoticeCard(vm: LibraryViewModel, state: AppState, n: Notice, onPlay: (List<Channel>, Channel) -> Unit) {
    val tv = isTv()
    Column(Modifier.fillMaxWidth().panel(RoundedCornerShape(14.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!n.read) {
                Box(Modifier.size(8.dp).background(Signal, CircleShape))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                "New in ${n.playlistName}",
                fontFamily = Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (tv) 20.sp else 18.sp,
                color = Paper,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(noticeAge(n.id), fontFamily = Sans, fontSize = 12.sp, color = Faint)
        }
        Spacer(Modifier.height(4.dp))
        Text(n.summary(), fontFamily = Sans, fontSize = 14.sp, color = Muted)
        // Everything here opens straight away: channels and films play, a series opens its episodes.
        // The group chips only narrow the row below (they don't take you to the group's list).
        val open = vm.playlist?.id == n.playlistId
        val items = if (open) n.items.mapNotNull { vm.index.byId[it] } else emptyList()
        var pick by remember(n.id) { mutableStateOf<NoticeGroup?>(null) }
        val shown = pick?.let { g -> items.filter { it.kind == g.kind && it.group == g.group } } ?: items
        if (open && n.groups.size > 1 && items.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "all") { CountChip("All", items.size, selected = pick == null) { pick = null } }
                items(n.groups.filter { g -> items.any { it.kind == g.kind && it.group == g.group } }, key = { it.kind.name + it.group }) { g ->
                    CountChip(g.group, g.count, selected = pick == g) { pick = if (pick == g) null else g }
                }
            }
        }
        if (!open) {
            // Another playlist: open it here to see (and play) what's new in it.
            val p = state.playlists.firstOrNull { it.id == n.playlistId }
            if (p != null) {
                Spacer(Modifier.height(12.dp))
                GhostButton("Open ${p.name} to watch them") { vm.open(p) }
            }
        } else if (shown.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            val live = shown.filter { it.kind == Kind.LIVE }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(shown, key = { "n:" + it.id }) { ch ->
                    if (ch.kind == Kind.LIVE) {
                        ChannelCard(ch, null, System.currentTimeMillis(), onClick = { onPlay(live, ch) }, width = if (tv) 200.dp else 160.dp)
                    } else {
                        PosterCard(
                            ch, state.progress[progressKey(n.playlistId, ch.id)],
                            onClick = { if (ch.isSeriesFolder) vm.openSeries(ch) else onPlay(listOf(ch), ch) },
                            width = if (tv) 120.dp else 100.dp,
                        )
                    }
                }
            }
            // More arrived in that group than the card keeps: the whole group, on request.
            val g = pick
            if (g != null && g.count > shown.size) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { vm.showNoticeGroup(n, g) }) { Text("See all ${g.count} in ${g.group}", color = Signal) }
            }
        }
    }
}

private fun noticeAge(at: Long): String {
    val mins = (System.currentTimeMillis() - at) / 60_000
    return when {
        mins < 2 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 48 * 60 -> "${mins / 60} h ago"
        else -> "${mins / (24 * 60)} days ago"
    }
}

/** The bell in the top bar, with how many What's new entries haven't been seen. */
@Composable
fun NoticeBell(unread: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box {
            Icon(Icons.Outlined.Notifications, contentDescription = if (unread > 0) "What's new, $unread unseen" else "What's new", tint = Paper)
            if (unread > 0) {
                Text(
                    if (unread > 9) "9+" else "$unread",
                    fontFamily = Sans,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-5).dp)
                        .background(Signal, CircleShape)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** Kind of a notice group → the section that shows it. */
internal fun sectionFor(g: NoticeGroup): Section = when (g.kind) {
    Kind.LIVE -> Section.LIVE
    Kind.MOVIE -> Section.MOVIES
    Kind.SERIES -> Section.SERIES
}
