package app.falak.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.data.AppState
import app.falak.player.data.Catchup
import app.falak.player.data.Channel
import app.falak.player.data.Epg
import app.falak.player.data.KIDS_PROFILE
import app.falak.player.data.PrayerTimes
import app.falak.player.data.Program
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/*
 * Home's top, Orbit style: a greeting with the date, time and next prayer; a big card for the
 * channel you'd most likely watch now (what's on, how far in, Watch live, From the start); and
 * "Tonight": your other channels with what's on, how far in (the ring) and what's next.
 */

/** Why Home's live card shows this channel. */
enum class NowReason { HABIT, RECENT, FAVORITE, NONE }

class NowPick(val channel: Channel, val reason: NowReason)

/** "Good evening, Ahmed" and "Monday 26 October · 9:41 PM · Next prayer Fajr 05:12". */
@Composable
fun HomeGreeting(vm: LibraryViewModel, state: AppState, now: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val tv = isTv()
    val hour = remember(now / 60_000) { Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY) }
    val hello = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
    // The profile's own name; not the main profile's default "Me", nor Kids.
    val name = when (val id = state.activeProfile) {
        "" -> state.settings.mainProfileName.trim().takeIf { it.isNotEmpty() && it != "Me" }
        KIDS_PROFILE -> null
        else -> state.profiles.firstOrNull { it.id == id }?.name?.trim()?.takeIf { it.isNotEmpty() }
    }
    val s = state.settings
    val prayerOn = s.prayerMode != "off"
    val prayer by produceState<Pair<Long, PrayerTimes.Prayer>?>(null, prayerOn, s.prayerMethod, s.prayerAsr, now / 60_000) {
        value = if (prayerOn) withContext(Dispatchers.Default) { vm.nextPrayer() } else null
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val roomy = tv || maxWidth >= 600.dp
        val line = remember(now / 60_000, prayer, roomy) {
            val locale = Locale.getDefault()
            val datePattern = android.text.format.DateFormat.getBestDateTimePattern(locale, if (roomy) "EEEEdMMMM" else "EEEdMMM")
            val date = SimpleDateFormat(datePattern, locale).format(Date(now))
            val fmt = android.text.format.DateFormat.getTimeFormat(context)
            val time = fmt.format(Date(now))
            val next = prayer?.let { (_, p) -> (if (roomy) "Next prayer " else "") + p.name + " " + PrayerTimes.label(p.minute, fmt) }
            listOfNotNull(date, time, next).joinToString("  ·  ")
        }
        Column(Modifier.padding(start = 4.dp)) {
            Text(
                if (name != null) "$hello, $name" else hello,
                fontFamily = Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (tv) 26.sp else 25.sp,
                lineHeight = if (tv) 32.sp else 30.sp,
                color = Paper,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(line, fontFamily = Sans, fontSize = if (tv) 13.sp else 12.sp, color = Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The live card and Tonight: side by side on a TV, a tablet or a phone on its side; one above
 * the other on a phone held upright ([cards]: Tonight as a row of small cards, Sidebar look).
 */
@Composable
fun NowRow(
    vm: LibraryViewModel,
    pick: NowPick,
    tonight: List<Channel>,
    epg: Map<String, List<Program>>,
    now: Long,
    heightLimit: Dp,
    cards: Boolean,
    onPlay: (List<Channel>, Channel) -> Unit,
    onLongPress: (Channel) -> Unit,
) {
    val playList = remember(pick, tonight) { listOf(pick.channel) + tonight }
    val onGuide = { vm.screen = Screen.GUIDE }
    val title = remember(now / 60_000) {
        val h = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        if (h >= 17 || h < 4) "Tonight" else "On now"
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 640.dp
        if (wide) {
            val heroWidth = if (tonight.isEmpty()) maxWidth else maxWidth * 0.58f
            val height = (maxWidth * 0.58f * 9f / 16f).coerceAtMost(heightLimit).coerceAtLeast(220.dp)
            Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                LiveHeroCard(vm, pick, epg, now, wide = true, onPlay = { onPlay(playList, it) }, onPlayItem = { onPlay(listOf(it), it) }, modifier = Modifier.width(heroWidth).fillMaxHeight())
                if (tonight.isNotEmpty()) {
                    val rows = ((height - 62.dp) / 63.dp).toInt().coerceIn(1, 4)
                    TonightPanel(title, tonight.take(rows), epg, now, wide = true, onOpen = { onPlay(playList, it) }, onLongPress = onLongPress, onGuide = onGuide, modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                LiveHeroCard(vm, pick, epg, now, wide = false, onPlay = { onPlay(playList, it) }, onPlayItem = { onPlay(listOf(it), it) }, modifier = Modifier.fillMaxWidth().height(236.dp))
                if (tonight.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    if (cards) {
                        TonightCards("$title on your channels", tonight, epg, now, onOpen = { onPlay(playList, it) }, onLongPress = onLongPress, onGuide = onGuide)
                    } else {
                        TonightPanel(title, tonight.take(3), epg, now, wide = false, onOpen = { onPlay(playList, it) }, onLongPress = onLongPress, onGuide = onGuide, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** The channel you'd most likely watch now, with what's on: Watch live, or From the start. */
@Composable
private fun LiveHeroCard(
    vm: LibraryViewModel,
    pick: NowPick,
    epg: Map<String, List<Program>>,
    now: Long,
    wide: Boolean,
    onPlay: (Channel) -> Unit,
    onPlayItem: (Channel) -> Unit,
    modifier: Modifier,
) {
    val ch = pick.channel
    val prog = Epg.current(epg[ch.epgKey], now)
    val context = LocalContext.current
    val tv = isTv()
    val shape = RoundedCornerShape(16.dp)
    // Over the picture it's always light on dark, in the light theme too.
    CompositionLocalProvider(LocalPalette provides DarkPalette) {
        BoxWithConstraints(modifier.clip(shape).background(Brush.linearGradient(listOf(Color(0xFF1E2633), Color(0xFF141312))))) {
            val h = maxHeight
            if (ch.logo.isNotBlank()) {
                // A soft wash of the logo's colours (decoded tiny), and the logo itself.
                val wash = remember(ch.logo) {
                    ImageRequest.Builder(context).data(ch.logo).size(16, 9).memoryCacheKey(ch.logo + "#wash").build()
                }
                AsyncImage(model = wash, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.4f, modifier = Modifier.fillMaxSize())
                AsyncImage(
                    model = ch.logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = if (wide) 52.dp else 40.dp, end = if (wide) 28.dp else 16.dp)
                        .height(h * (if (wide) 0.34f else 0.28f))
                        .aspectRatio(1.6f),
                )
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x59000000), 0.28f to Color.Transparent, 0.42f to Color.Transparent, 1f to Color(0xE6080808))))

            Row(Modifier.align(Alignment.TopStart).padding(if (wide) 18.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.background(Signal, RoundedCornerShape(5.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
                    Spacer(Modifier.width(6.dp))
                    Text("LIVE", fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.4.sp, color = Color.White)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    ch.name,
                    fontFamily = Sans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (wide) 14.sp else 12.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 260.dp).background(Color(0x8C121110), RoundedCornerShape(5.dp)).padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
            val reason = when (pick.reason) {
                NowReason.HABIT -> "You often watch this now"
                NowReason.RECENT -> "You watched this last"
                NowReason.FAVORITE -> "One of your favorites"
                NowReason.NONE -> null
            }
            if (wide && reason != null) {
                Text(
                    reason,
                    fontFamily = Sans,
                    fontSize = 13.sp,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.TopEnd).padding(18.dp).background(Color(0x8C121110), RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }

            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = if (wide) 22.dp else 14.dp, vertical = if (wide) 18.dp else 12.dp)) {
                Text(
                    prog?.title?.takeIf { it.isNotBlank() } ?: ch.name,
                    fontFamily = Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (wide) (if (tv) 30.sp else 28.sp) else 23.sp,
                    lineHeight = if (wide) (if (tv) 34.sp else 32.sp) else 27.sp,
                    color = Color.White,
                    maxLines = if (wide) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                if (prog != null) {
                    val fmt = remember { android.text.format.DateFormat.getTimeFormat(context) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fmt.format(Date(prog.start)) + " – " + fmt.format(Date(prog.stop)), fontFamily = Sans, fontSize = if (wide) 14.sp else 12.sp, color = Muted, maxLines = 1)
                        Spacer(Modifier.width(10.dp))
                        val fraction = ((now - prog.start).toFloat() / (prog.stop - prog.start).coerceAtLeast(1L)).coerceIn(0f, 1f)
                        Box(Modifier.weight(1f, fill = false).widthIn(max = 240.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x38FFFFFF))) {
                            Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(Signal))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(minutesLeft(prog, now), fontFamily = Sans, fontSize = if (wide) 14.sp else 12.sp, color = Muted, maxLines = 1)
                    }
                } else {
                    Text(ch.group.ifBlank { "Live now" }, fontFamily = Sans, fontSize = if (wide) 14.sp else 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(if (wide) 14.dp else 10.dp))
                // From the start: the channel keeps a replay and the show began a while ago.
                val replay = prog?.takeIf { now - it.start >= 3 * 60_000L && Catchup.canReplay(ch, it, now) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PaperButton("Watch live", Icons.Filled.PlayArrow) { onPlay(ch) }
                    if (replay != null) {
                        GhostButton("From the start", Icons.Outlined.Refresh) {
                            vm.replay(ch, replay, System.currentTimeMillis())?.let(onPlayItem)
                        }
                    }
                }
            }
        }
    }
}

/** "Tonight": channel, what's on and until when, the ring for how far in, and what's next. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun TonightPanel(
    title: String,
    items: List<Channel>,
    epg: Map<String, List<Program>>,
    now: Long,
    wide: Boolean,
    onOpen: (Channel) -> Unit,
    onLongPress: (Channel) -> Unit,
    onGuide: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val fmt = remember { android.text.format.DateFormat.getTimeFormat(context) }
    Column(modifier.panel(RoundedCornerShape(16.dp)).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)) {
        PanelHeader(title, if (wide) "Full guide  →" else "Guide  →", onGuide)
        Spacer(Modifier.height(6.dp))
        items.forEach { ch ->
            val prog = Epg.current(epg[ch.epgKey], now)
            val next = Epg.next(epg[ch.epgKey], now)
            Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
            Row(
                Modifier
                    .fillMaxWidth()
                    .focusRing(RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp))
                    .combinedClickable(onClick = { onOpen(ch) }, onLongClick = { onLongPress(ch) })
                    .padding(vertical = 9.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProgressRing(prog?.let { fractionOf(it, now) }, ch, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(prog?.title?.takeIf { it.isNotBlank() } ?: ch.name, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = when {
                        prog == null -> ch.group.takeIf { it.isNotBlank() && it != ch.name } ?: "Live"
                        // Wide: what's next (and so when this ends) is on the right.
                        wide && next != null -> ch.name
                        next != null -> "${ch.name}  ·  next ${fmt.format(Date(next.start))} ${next.title}"
                        else -> "${ch.name}  ·  until ${fmt.format(Date(prog.stop))}"
                    }
                    Text(sub, fontFamily = Sans, fontSize = 12.sp, color = Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (wide && next != null) {
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.width(104.dp), horizontalAlignment = Alignment.End) {
                        Text(fmt.format(Date(next.start)), fontFamily = Sans, fontSize = 12.sp, color = Muted, maxLines = 1)
                        Text(next.title, fontFamily = Sans, fontSize = 12.sp, color = Faint, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                    }
                }
            }
        }
    }
}

/** Phone, Sidebar look: Tonight as a row of small cards. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun TonightCards(
    title: String,
    items: List<Channel>,
    epg: Map<String, List<Program>>,
    now: Long,
    onOpen: (Channel) -> Unit,
    onLongPress: (Channel) -> Unit,
    onGuide: () -> Unit,
) {
    val context = LocalContext.current
    val fmt = remember { android.text.format.DateFormat.getTimeFormat(context) }
    Column(Modifier.fillMaxWidth()) {
        PanelHeader(title, "Guide  →", onGuide)
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
            items(items, key = { "t:" + it.id }) { ch ->
                val prog = Epg.current(epg[ch.epgKey], now)
                val next = Epg.next(epg[ch.epgKey], now)
                val shape = RoundedCornerShape(14.dp)
                Column(
                    Modifier
                        .width(150.dp)
                        .focusRing(shape)
                        .panel(shape)
                        .combinedClickable(onClick = { onOpen(ch) }, onLongClick = { onLongPress(ch) })
                        .padding(12.dp),
                ) {
                    ProgressRing(prog?.let { fractionOf(it, now) }, ch, 44.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(prog?.title?.takeIf { it.isNotBlank() } ?: ch.name, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ch.name, fontFamily = Sans, fontSize = 12.sp, color = Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        next?.let { "${fmt.format(Date(it.start))}  ${it.title}" } ?: ch.group.ifBlank { " " },
                        fontFamily = Sans,
                        fontSize = 11.sp,
                        color = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(title: String, link: String, onLink: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(
            link,
            fontFamily = Sans,
            fontSize = 13.sp,
            color = Muted,
            maxLines = 1,
            modifier = Modifier
                .focusRing(RoundedCornerShape(6.dp))
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onLink)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

private fun fractionOf(p: Program, now: Long): Float =
    ((now - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1L)).coerceIn(0f, 1f)

/** The channel's logo in a ring that fills as the show goes on (an empty ring with no guide). */
@Composable
private fun ProgressRing(fraction: Float?, ch: Channel, size: Dp) {
    val track = Paper.copy(alpha = 0.14f)
    val signal = Signal
    Box(
        Modifier.size(size).drawBehind {
            val sw = 3.dp.toPx()
            val topLeft = Offset(sw / 2, sw / 2)
            val arc = Size(this.size.width - sw, this.size.height - sw)
            drawArc(track, 0f, 360f, false, topLeft = topLeft, size = arc, style = Stroke(sw))
            if (fraction != null && fraction > 0f) {
                drawArc(signal, -90f, 360f * fraction, false, topLeft = topLeft, size = arc, style = Stroke(sw, cap = StrokeCap.Round))
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size - 10.dp).clip(CircleShape).background(Ink3), contentAlignment = Alignment.Center) {
            if (ch.logo.isNotBlank()) {
                AsyncImage(model = ch.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(5.dp))
            } else {
                val initials = ch.name.split(' ', '-', '_').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase()
                Text(initials, fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Paper, maxLines = 1)
            }
        }
    }
}
