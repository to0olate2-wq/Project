package app.falak.player.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.data.AppState
import app.falak.player.data.Channel
import app.falak.player.data.Epg
import app.falak.player.data.Kind
import app.falak.player.data.Program
import app.falak.player.data.mine
import app.falak.player.data.progressKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The row under the search box: your recent searches while nothing is typed, then the best five
 * matches once two letters are in (pick one to open it straight away).
 */
@Composable
fun SearchAssist(
    query: String,
    recent: List<String>,
    showRecent: Boolean,
    top: List<Channel>,
    onRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
    onOpen: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val q = query.trim()
    val recentOn = q.isEmpty() && showRecent && recent.isNotEmpty()
    val topOn = q.length >= 2 && top.isNotEmpty()
    if (!recentOn && !topOn) return
    Row(
        modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (recentOn) "Recent" else "Top matches",
            fontFamily = Sans,
            fontSize = 12.sp,
            color = Faint,
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp, end = 2.dp),
        )
        if (recentOn) {
            recent.forEach { r -> AssistChip(r, null) { onRecent(r) } }
            AssistChip("Clear", null, quiet = true, onClick = onClearRecent)
        } else {
            top.forEach { ch -> AssistChip(ch.name, kindTag(ch)) { onOpen(ch) } }
        }
    }
}

private fun kindTag(ch: Channel): String = when (ch.kind) {
    Kind.LIVE -> "LIVE"
    Kind.MOVIE -> "FILM"
    Kind.SERIES -> "SERIES"
}

@Composable
private fun AssistChip(text: String, tag: String?, quiet: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .widthIn(max = 280.dp)
            .focusRing(shape)
            .clip(shape)
            .border(1.dp, Paper.copy(alpha = if (quiet) 0.14f else 0.26f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tag != null) {
            Text(tag, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, letterSpacing = 1.sp, color = Faint, maxLines = 1)
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text,
            fontFamily = Sans,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = if (quiet) Muted else Paper,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Search from Home: everything that matches, grouped (channels, films, series; the group with the
 * best match first), plus programmes on TV right now. "See all" opens the section with the same
 * search. [ranked] is every match, best first (null while searching).
 */
@Composable
fun SearchResults(
    vm: LibraryViewModel,
    state: AppState,
    query: String,
    ranked: List<Channel>?,
    excluded: Set<String>,
    epg: Map<String, List<Program>>,
    now: Long,
    onOpen: (List<Channel>, Channel) -> Unit,
) {
    val p = vm.playlist ?: return
    val me = state.mine(p.id)
    val index = vm.index
    val q = query.trim()
    val onNow by produceState<List<Pair<Channel, Program>>?>(null, q, epg, index, excluded, now) {
        value = withContext(Dispatchers.Default) {
            val channels = if (excluded.isEmpty()) index.live else index.live.filter { it.group !in excluded }
            searchGuide(channels, epg, q, now, limit = 30, onNowOnly = true)
        }
    }
    val groups = remember(ranked) {
        val byKind = LinkedHashMap<Kind, ArrayList<Channel>>()
        for (ch in ranked.orEmpty()) byKind.getOrPut(ch.kind) { ArrayList() } += ch
        byKind.entries.map { it.key to it.value }
    }
    val seeAll: (Kind) -> Unit = { k ->
        vm.searchFromHome = true
        vm.group = null
        vm.sort = Sort.DEFAULT
        vm.section = when (k) {
            Kind.LIVE -> Section.LIVE
            Kind.MOVIE -> Section.MOVIES
            Kind.SERIES -> Section.SERIES
        }
    }
    if (ranked == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    val live = onNow.orEmpty()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        if (ranked.isEmpty() && onNow != null && live.isEmpty()) {
            item(key = "none") { NothingFound(q) }
        }
        for ((kind, list) in groups) {
            item(key = "kind:" + kind.name) {
                val title = when (kind) {
                    Kind.LIVE -> "Channels"
                    Kind.MOVIE -> "Films"
                    Kind.SERIES -> "Series"
                } + "  ·  " + countLabel(list.size)
                Shelf(title, onSeeAll = { seeAll(kind) }) {
                    items(list.take(30), key = { it.id }) { ch ->
                        if (kind == Kind.LIVE) {
                            ChannelCard(
                                ch, Epg.current(epg[ch.epgKey], now), now,
                                onClick = { onOpen(list, ch) },
                                onLongClick = { vm.listSheetFor = ch },
                            )
                        } else {
                            PosterCard(
                                ch, state.progress[progressKey(me, ch.id)],
                                onClick = { onOpen(list, ch) },
                                onLongClick = { vm.listSheetFor = ch },
                            )
                        }
                    }
                }
            }
        }
        if (live.isNotEmpty()) {
            item(key = "onNow") {
                val channels = remember(live) { live.map { it.first }.distinctBy { it.id } }
                Shelf("On TV now  ·  " + countLabel(live.size)) {
                    items(live, key = { (ch, prog) -> "now:" + ch.id + "|" + prog.start }) { (ch, prog) ->
                        ChannelCard(ch, prog, now, onClick = { onOpen(channels, ch) }, onLongClick = { vm.listSheetFor = ch })
                    }
                }
            }
        }
    }
}

@Composable
private fun NothingFound(query: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Nothing found for “$query”",
            fontFamily = Serif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            color = Paper,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Check the spelling or try fewer words. Search looks through the names of channels, films and series, and what's on TV now.",
            fontFamily = Sans,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 460.dp),
        )
    }
}
