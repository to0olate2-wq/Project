package app.falak.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.falak.player.data.AppState
import app.falak.player.data.Channel
import app.falak.player.data.FootballFeed
import app.falak.player.data.FootballMatch
import app.falak.player.data.Kind
import app.falak.player.data.Matches
import app.falak.player.data.TableRow
import app.falak.player.data.TvMatch
import app.falak.player.data.mine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date

/**
 * The Match centre, a page of its own: matches the TV guide lists for today and tomorrow (live
 * first, with the score when the scores feed knows it; OK watches a live one or sets a reminder
 * for a later one), and the Premier League's round and table.
 */
@Composable
fun MatchCentreScreen(vm: LibraryViewModel, state: AppState, onPlay: (List<Channel>, Channel) -> Unit) {
    val p = vm.playlist ?: return
    val back: () -> Unit = { if (!vm.leaveDetour()) vm.screen = Screen.LIBRARY }
    BackHandler { back() }
    val epg by vm.epg.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    // Scores now, then every minute while the page is open.
    LaunchedEffect(Unit) {
        while (true) {
            vm.loadFootball()
            delay(60_000)
        }
    }
    val feed = vm.football
    val index = vm.index
    val excluded = remember(state.settings, index, vm.kidsActive, vm.unlocked) { vm.currentExclusions() }
    val favorites = remember(state.favorites, p.id, state.activeProfile) { state.favorites[state.mine(p.id)].orEmpty().toHashSet() }
    val endOfTomorrow = remember(now / 3_600_000L) {
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 2)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val found by produceState<List<TvMatch>?>(null, epg, index, excluded, favorites, feed, now) {
        value = withContext(Dispatchers.Default) {
            val live = if (excluded.isEmpty()) index.live else index.live.filter { it.group !in excluded }
            Matches.inGuide(live, epg, now, endOfTomorrow, favorites, feed?.today.orEmpty())
        }
    }
    val league = feed?.takeIf { it.configured && (it.pl.table.isNotEmpty() || it.pl.matches.isNotEmpty()) }
    val shown = if (league == null) 0 else vm.matchTab
    val tv = isTv()
    val timeFormat = rememberTimeFormat()

    Column(Modifier.fillMaxSize().padding(horizontal = pageMargin())) {
        PageHeader(
            "Match centre",
            onBack = back,
            subtitle = when {
                feed?.configured == true && feed.stale -> "Couldn't refresh the scores · showing the last ones"
                else -> null
            },
        ) {
            IconButton(onClick = { vm.loadFootball(force = true) }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh scores", tint = Paper)
            }
        }
        if (league != null) {
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TagChip("On TV", shown == 0) { vm.matchTab = 0 }
                TagChip("Premier League", shown == 1) { vm.matchTab = 1 }
            }
        }
        if (shown == 0 || league == null) {
            OnTv(found, epg.isEmpty(), now, timeFormat, state, onWatch = { m ->
                val list = index.groupsOf(Kind.LIVE)[m.channel.group] ?: listOf(m.channel)
                onPlay(list, m.channel)
            }, onRemind = { m -> vm.toggleReminder(m.channel, m.program) })
        } else {
            PremierLeague(league, now, timeFormat, tv)
        }
    }
}

@Composable
private fun OnTv(
    found: List<TvMatch>?,
    noGuide: Boolean,
    now: Long,
    timeFormat: java.text.DateFormat,
    state: AppState,
    onWatch: (TvMatch) -> Unit,
    onRemind: (TvMatch) -> Unit,
) {
    when {
        found == null -> Centered { CircularProgressIndicator(color = Signal, strokeWidth = 2.dp) }
        found.isEmpty() -> Centered {
            Text("No matches in your TV guide for today and tomorrow", fontFamily = Serif, fontSize = 19.sp, color = Paper, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(
                if (noGuide) "The TV guide hasn't loaded yet. Matches show here once it has."
                else "Matches show here when the guide lists them as “Team A v Team B”.",
                fontFamily = Sans,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 460.dp).padding(horizontal = 16.dp),
            )
        }
        else -> {
            val startOfTomorrow = remember(now / 3_600_000L) {
                Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }
            val liveNow = found.filter { it.program.start <= now }
            val today = found.filter { it.program.start in (now + 1)..<startOfTomorrow }
            val tomorrow = found.filter { it.program.start >= startOfTomorrow }
            val reminded = remember(state.reminders) { state.reminders.mapTo(HashSet()) { "${it.channelId}|${it.start}" } }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                fun section(title: String, list: List<TvMatch>) {
                    if (list.isEmpty()) return
                    item(key = "h:$title") { Box(Modifier.padding(start = 4.dp, top = 14.dp, bottom = 4.dp)) { Kicker(title) } }
                    items(list, key = { "m:" + it.channel.id + "|" + it.program.start }) { m ->
                        val isLive = m.program.start <= now
                        MatchRow(
                            m, isLive, "${m.channel.id}|${m.program.start}" in reminded, now, timeFormat,
                        ) { if (isLive) onWatch(m) else onRemind(m) }
                        Rule()
                    }
                }
                section("Live now", liveNow)
                section("Later today", today)
                section("Tomorrow", tomorrow)
            }
        }
    }
}

@Composable
private fun MatchRow(m: TvMatch, live: Boolean, reminded: Boolean, now: Long, timeFormat: java.text.DateFormat, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val score = m.score
    Row(
        Modifier
            .fillMaxWidth()
            .focusRing(shape)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(86.dp)) {
            if (live) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(7.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("LIVE", fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 1.sp, color = Signal)
                }
            } else {
                Text(timeFormat.format(Date(m.program.start)), style = Tabular.copy(fontSize = 15.sp), color = Paper)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                "${m.home}  v  ${m.away}",
                fontFamily = Sans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = Paper,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val more = when (m.alsoOn) {
                0 -> null
                1 -> "also on 1 other channel"
                else -> "also on ${m.alsoOn} other channels"
            }
            Text(
                listOfNotNull(score?.competition?.takeIf { it.isNotBlank() }, m.channel.name, more).joinToString("  ·  "),
                fontFamily = Sans,
                fontSize = 12.sp,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        when {
            score?.score != null -> Column(horizontalAlignment = Alignment.End) {
                Text(score.score!!, style = Tabular.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold), color = Paper)
                score.state?.let { Text(it, fontFamily = Sans, fontSize = 11.sp, color = if (score.live) Signal else Muted) }
            }
            live -> Text("Watch  ▶", fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Paper)
            reminded -> Text("🔔 Reminder set", fontFamily = Sans, fontSize = 13.sp, color = Color(0xFFF2C14E))
            else -> Text(
                Matches.untilLabel(m.program.start, now),
                fontFamily = Sans,
                fontSize = 12.sp,
                color = Muted,
            )
        }
    }
}

/** The Premier League: this round's matches and the table (side by side on a TV). */
@Composable
private fun PremierLeague(feed: FootballFeed, now: Long, timeFormat: java.text.DateFormat, tv: Boolean) {
    val round = feed.pl
    val footer = "Scores and table: football-data.org · updated ${agoLabel(feed.at, now)}. Scores can be a few minutes behind the live picture."
    if (tv) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(bottom = 32.dp)) {
                fixtures(round.matches, round.matchday, timeFormat, now)
            }
            LazyColumn(Modifier.weight(1.1f).fillMaxHeight(), contentPadding = PaddingValues(bottom = 32.dp)) {
                table(round.table, wide = true)
                item(key = "footer") { FootNote(footer) }
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            fixtures(round.matches, round.matchday, timeFormat, now)
            table(round.table, wide = false)
            item(key = "footer") { FootNote(footer) }
        }
    }
}

private fun LazyListScope.fixtures(matches: List<FootballMatch>, matchday: Int?, timeFormat: java.text.DateFormat, now: Long) {
    item(key = "fx") { Box(Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp)) { Kicker(if (matchday != null) "Matches · around matchday $matchday" else "Matches") } }
    if (matches.isEmpty()) {
        item(key = "fx-none") { Text("No matches this week.", fontFamily = Sans, fontSize = 13.sp, color = Muted, modifier = Modifier.padding(4.dp)) }
        return
    }
    var lastDay = ""
    for (m in matches.sortedBy { it.time }) {
        val day = dayLabel(m.time, now)
        if (day != lastDay) {
            lastDay = day
            item(key = "fd:$day:${m.time / 86_400_000L}") {
                Text(day, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Faint, modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp))
            }
        }
        item(key = "f:${m.id}") { FixtureRow(m, timeFormat) }
    }
}

@Composable
private fun FixtureRow(m: FootballMatch, timeFormat: java.text.DateFormat) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().focusRing(shape).clip(shape).focusable().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            m.state ?: timeFormat.format(Date(m.time)),
            style = Tabular.copy(fontSize = 12.sp),
            color = if (m.live) Signal else Muted,
            modifier = Modifier.width(62.dp),
            maxLines = 1,
        )
        Text(m.home.short, fontFamily = Sans, fontSize = 14.sp, color = Paper, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(
            m.score ?: "v",
            style = Tabular.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            color = if (m.live) Signal else Paper,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(64.dp),
        )
        Text(m.away.short, fontFamily = Sans, fontSize = 14.sp, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

private fun LazyListScope.table(rows: List<TableRow>, wide: Boolean) {
    item(key = "tb") { Box(Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp)) { Kicker("Table") } }
    if (rows.isEmpty()) {
        item(key = "tb-none") { Text("The table isn't available right now.", fontFamily = Sans, fontSize = 13.sp, color = Muted, modifier = Modifier.padding(4.dp)) }
        return
    }
    item(key = "tb-head") { TableLine(null, wide) }
    items(rows, key = { "t:" + it.position + it.tla }) { r -> TableLine(r, wide, last = rows.size) }
    item(key = "tb-legend") {
        Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 3.dp, height = 12.dp).background(TOP_ZONE))
            Text("  Top four     ", fontFamily = Sans, fontSize = 11.sp, color = Muted)
            Box(Modifier.size(width = 3.dp, height = 12.dp).background(BOTTOM_ZONE))
            Text("  Bottom three", fontFamily = Sans, fontSize = 11.sp, color = Muted)
        }
    }
}

private val TOP_ZONE = Color(0xFF4C8DFF)
private val BOTTOM_ZONE = Color(0xFFE5484D)

/** One line of the table, or its column names when [r] is null. */
@Composable
private fun TableLine(r: TableRow?, wide: Boolean, last: Int = 20) {
    val shape = RoundedCornerShape(6.dp)
    val head = r == null
    val color = if (head) Faint else Paper
    val size = if (head) 11.sp else 14.sp
    val cell = if (wide) 34.dp else 28.dp
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (head) Modifier else Modifier.focusRing(shape).clip(shape).focusable())
            .padding(vertical = if (head) 4.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val zone = when {
            r == null -> Color.Transparent
            r.position <= 4 -> TOP_ZONE
            r.position > last - 3 -> BOTTOM_ZONE
            else -> Color.Transparent
        }
        Box(Modifier.size(width = 3.dp, height = 18.dp).background(zone))
        Text(r?.position?.toString() ?: "#", style = Tabular.copy(fontSize = size), color = color, textAlign = TextAlign.End, modifier = Modifier.width(26.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            r?.short ?: "Team",
            fontFamily = Sans,
            fontWeight = if (head) FontWeight.Normal else FontWeight.Medium,
            fontSize = size,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val values = if (r == null) listOf("P", "W", "D", "L", "GD", "Pts") else listOf(
            r.played.toString(), r.won.toString(), r.draw.toString(), r.lost.toString(),
            (if (r.goalDifference > 0) "+" else "") + r.goalDifference, r.points.toString(),
        )
        values.forEachIndexed { i, v ->
            Text(
                v,
                style = Tabular.copy(fontSize = size, fontWeight = if (i == values.lastIndex && !head) FontWeight.SemiBold else FontWeight.Normal),
                color = if (i == values.lastIndex || head) color else Muted,
                textAlign = TextAlign.End,
                modifier = Modifier.width(cell),
            )
        }
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun FootNote(text: String) {
    Text(text, fontFamily = Sans, fontSize = 11.sp, lineHeight = 16.sp, color = Faint, modifier = Modifier.padding(start = 4.dp, top = 14.dp, end = 4.dp))
}

/** "just now", "3 min ago", "2 h ago". */
private fun agoLabel(at: Long, now: Long): String {
    if (at <= 0) return "a while ago"
    val min = ((now - at) / 60_000L).coerceAtLeast(0)
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        else -> "${min / 60} h ago"
    }
}
