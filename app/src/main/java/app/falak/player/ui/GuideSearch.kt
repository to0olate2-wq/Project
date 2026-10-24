package app.falak.player.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.data.Catchup
import app.falak.player.data.Channel
import app.falak.player.data.Program
import app.falak.player.data.SearchQuery
import java.util.Calendar
import java.util.Date

/**
 * Programmes whose title matches [query] (the same smart matching as the rest of search): on now
 * first, then coming up (soonest first), then earlier ones that can still be replayed (latest
 * first). [onNowOnly] keeps just what's on now, best matches first.
 */
fun searchGuide(
    channels: List<Channel>,
    epg: Map<String, List<Program>>,
    query: String,
    now: Long,
    limit: Int = 200,
    onNowOnly: Boolean = false,
): List<Pair<Channel, Program>> {
    val q = SearchQuery(query)
    if (q.isEmpty) return emptyList()
    val live = ArrayList<Pair<Channel, Program>>()
    val liveScore = HashMap<Pair<Channel, Program>, Int>()
    val later = ArrayList<Pair<Channel, Program>>()
    val earlier = ArrayList<Pair<Channel, Program>>()
    val horizon = now + 2 * 86_400_000L
    val seen = HashSet<String>()
    for (ch in channels) {
        val programs = epg[ch.epgKey] ?: continue
        for (p in programs) {
            if (p.start >= horizon) continue
            val onNow = now >= p.start && now < p.stop
            val bucket = when {
                onNow -> live
                onNowOnly -> continue
                p.start >= now -> later
                Catchup.canReplay(ch, p, now) -> earlier
                else -> continue
            }
            val score = q.score(p.title)
            if (score == 0) continue
            // The same programme on the same guide row listed under two channel entries: once.
            if (!seen.add(ch.epgKey + "|" + p.start + "|" + ch.name)) continue
            val hit = ch to p
            bucket += hit
            if (onNow) liveScore[hit] = score
        }
    }
    if (onNowOnly) live.sortByDescending { liveScore[it] ?: 0 }
    later.sortBy { it.second.start }
    earlier.sortByDescending { it.second.start }
    return (live + later + earlier).take(limit)
}

/** Search box and results for programmes in the guide. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GuideSearch(
    query: String,
    onQuery: (String) -> Unit,
    hits: List<Pair<Channel, Program>>?,
    now: Long,
    timeFormat: java.text.DateFormat,
    onHit: (Channel, Program) -> Unit,
    onHitLong: (Channel, Program) -> Unit,
) {
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).panel(RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Find a programme", color = Faint, fontFamily = Sans, fontSize = 14.sp)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = Paper, fontFamily = Sans, fontSize = 14.sp),
                cursorBrush = SolidColor(Signal),
                modifier = Modifier.fillMaxWidth().focusRequester(field),
            )
        }
    }
    when {
        query.trim().length < 2 -> Text(
            "Type a programme name: a film, a match, a show.",
            fontFamily = Sans,
            fontSize = 13.sp, lineHeight = 19.sp,
            color = Muted,
            modifier = Modifier.padding(top = 10.dp, start = 4.dp),
        )
        hits == null -> Unit
        hits.isEmpty() -> Text(
            "Nothing called \"${query.trim()}\" in the guide.",
            fontFamily = Sans,
            fontSize = 13.sp, lineHeight = 19.sp,
            color = Muted,
            modifier = Modifier.padding(top = 10.dp, start = 4.dp),
        )
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(hits, key = { (ch, p) -> "${ch.id}|${p.start}" }) { (ch, p) ->
                val live = now >= p.start && now < p.stop
                val shape = RoundedCornerShape(8.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .focusRing(shape)
                        .clip(shape)
                        .combinedClickable(onClick = { onHit(ch, p) }, onLongClick = { onHitLong(ch, p) })
                        .padding(vertical = 10.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.width(96.dp)) {
                        if (live) {
                            Text("On now", fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Signal)
                        } else {
                            Text(timeFormat.format(Date(p.start)), style = Tabular.copy(fontSize = 13.sp), color = Paper)
                            Text(dayLabel(p.start, now), fontFamily = Sans, fontSize = 11.sp, color = Faint)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (!live && p.stop <= now) "⟲ " else "") + p.title,
                            fontFamily = Sans,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = Paper,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${ch.name} · ${timeFormat.format(Date(p.start))}–${timeFormat.format(Date(p.stop))}",
                            fontFamily = Sans,
                            fontSize = 12.sp,
                            color = Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (live) {
                            val done = ((now - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                            Box(Modifier.padding(top = 5.dp).fillMaxWidth(0.5f).height(3.dp).background(Hairline)) {
                                Box(Modifier.fillMaxWidth(done).height(3.dp).background(Signal))
                            }
                        }
                    }
                }
                Rule()
            }
        }
    }
}

/** "Today", "Tomorrow", "Yesterday" or the weekday and date. */
private fun dayLabel(time: Long, now: Long): String {
    val a = Calendar.getInstance().apply { timeInMillis = now }
    val b = Calendar.getInstance().apply { timeInMillis = time }
    val days = run {
        fun dayNumber(c: Calendar) = c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)
        dayNumber(b) - dayNumber(a)
    }
    return when (days) {
        0 -> "Today"
        1 -> "Tomorrow"
        -1 -> "Yesterday"
        else -> java.text.SimpleDateFormat("EEE d", java.util.Locale.getDefault()).format(Date(time))
    }
}
