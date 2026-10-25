package app.falak.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.RamadanNow
import app.falak.player.data.Ramadan
import app.falak.player.data.RamadanCalendar
import app.falak.player.repo
import kotlinx.coroutines.delay

/** This year's Ramadan and where today stands, kept up to date each minute; null outside it or when off. */
@Composable
fun rememberRamadan(): Pair<Ramadan.Span, Ramadan.Phase>? {
    val context = LocalContext.current
    val repo = context.repo
    val state by repo.state.collectAsState()
    val s = state.settings
    var now by remember { mutableLongStateOf(RamadanNow.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            now = RamadanNow.now()
        }
    }
    return remember(now / 60_000, s.ramadanMode, s.ramadanDates, s.prayerMethod, s.prayerAsr) {
        RamadanNow.phase(context, repo, now)
    }
}

private val MOON = Color(0xFFF2C14E)

/** Home during Ramadan: Iftar in…, Suhoor until…, the days before it, and Eid. */
@Composable
fun RamadanCard(phase: Ramadan.Phase) {
    val context = LocalContext.current
    val now = RamadanNow.now()
    val (title, line) = RamadanNow.lines(context, phase, now)
    val tv = isTv()
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 4.dp)
            .widthIn(max = 900.dp)
            .panel(shape)
            .padding(horizontal = 18.dp, vertical = if (tv) 16.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(if (tv) 44.dp else 38.dp).clip(CircleShape).background(Color(0x26F2C14E)), contentAlignment = Alignment.Center) {
            CrescentIcon(MOON, if (tv) 22.dp else 19.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = if (tv) 22.sp else 19.sp, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, fontFamily = Sans, fontSize = 13.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** For the ambient screen: one line ("Iftar in 2 h 13 min · Maghrib at 6:28 PM"), or null. */
@Composable
fun ramadanAmbientLine(): String? {
    val r = rememberRamadan() ?: return null
    val (title, line) = RamadanNow.lines(LocalContext.current, r.second, RamadanNow.now())
    return if (r.second is Ramadan.Phase.Soon || r.second == Ramadan.Phase.Eid) title else "$title · ${line.substringAfterLast(" · ")}"
}

/** Settings → Prayer times: Ramadan mode, its dates (movable a day or two), and the Suhoor reminder. */
@Composable
fun RamadanSettings(vm: LibraryViewModel) {
    val context = LocalContext.current
    val repo = context.repo
    val state by repo.state.collectAsState()
    val s = state.settings
    val tv = isTv()
    val now = System.currentTimeMillis()
    val calendar = remember(now / 3_600_000) { runCatching { RamadanCalendar.current(now, emptyMap()) }.getOrNull() }
    val span = calendar?.let { Ramadan.withMoves(it, s.ramadanDates) }
    val dateFormat = remember(context) { java.text.SimpleDateFormat("EEE d MMM yyyy", java.util.Locale.getDefault()) }
    fun label(ymd: Int) = dateFormat.format(java.util.Date(Ramadan.startOf(ymd) + 12 * 3_600_000L))
    // Notifications for the Suhoor reminder (Android 13+), asked when it's switched on.
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(s.suhoorReminder, s.ramadanMode, s.ramadanDates) { app.falak.player.SuhoorAlarm.plan(context, repo) }

    Spacer(Modifier.height(20.dp))
    Kicker("Ramadan", color = Muted)
    Spacer(Modifier.height(6.dp))
    Text(
        "During Ramadan, Home and the ambient screen count down to Iftar and to Imsak (${Ramadan.IMSAK_MINUTES} minutes before Fajr), " +
            "and Home gathers your provider's Ramadan series.",
        fontFamily = Sans, fontSize = 13.sp, lineHeight = 19.sp, color = Muted,
    )
    Spacer(Modifier.height(10.dp))
    ChipFlow {
        TagChip("On during Ramadan", s.ramadanMode != "off") { vm.updateSettings { it.copy(ramadanMode = "auto") } }
        TagChip("Off", s.ramadanMode == "off") { vm.updateSettings { it.copy(ramadanMode = "off") } }
    }
    if (s.ramadanMode == "off" || span == null || calendar == null) return
    Spacer(Modifier.height(14.dp))
    Column(Modifier.fillMaxWidth().panel(RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "Ramadan ${span.hijriYear} · ${Ramadan.epochDay(span.last) - Ramadan.epochDay(span.first) + 1} days" +
                if (span.moved) " · moved by hand" else " · Umm al-Qura calendar",
            fontFamily = Sans, fontSize = 12.sp, color = Faint,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        fun move(first: Int, last: Int) {
            val key = span.hijriYear.toString()
            vm.updateSettings {
                val same = first == calendar.first && last == calendar.last
                it.copy(ramadanDates = if (same) it.ramadanDates - key else it.ramadanDates + (key to Ramadan.encode(first, last)))
            }
        }
        // Up to three days either way from the calendar; at least 29 days in all.
        val firstMin = Ramadan.plusDays(calendar.first, -3)
        val firstMax = minOf(Ramadan.plusDays(calendar.first, 3), Ramadan.plusDays(span.last, -28))
        val lastMin = maxOf(Ramadan.plusDays(calendar.last, -3), Ramadan.plusDays(span.first, 28))
        val lastMax = minOf(Ramadan.plusDays(calendar.last, 3), Ramadan.plusDays(span.first, 29))
        DayStepper(
            "First day",
            label(span.first),
            canBack = span.first > firstMin,
            canForward = span.first < firstMax,
            onBack = { move(Ramadan.plusDays(span.first, -1), span.last) },
            onForward = { move(Ramadan.plusDays(span.first, 1), span.last) },
        )
        Rule()
        DayStepper(
            "Last day",
            label(span.last) + " · Eid the day after",
            canBack = span.last > lastMin,
            canForward = span.last < lastMax,
            onBack = { move(span.first, Ramadan.plusDays(span.last, -1)) },
            onForward = { move(span.first, Ramadan.plusDays(span.last, 1)) },
        )
        if (span.moved) {
            Rule()
            Text(
                "Back to the calendar's dates",
                fontFamily = Sans, fontSize = 14.sp, color = Signal,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRing(RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { move(calendar.first, calendar.last) }
                    .padding(vertical = 12.dp),
            )
        }
    }
    Text(
        "If the moon was sighted a day apart from the calendar where you are, move the first or last day here.",
        fontFamily = Sans, fontSize = 12.sp, lineHeight = 17.sp, color = Faint,
        modifier = Modifier.padding(top = 8.dp),
    )
    if (!tv) {
        Spacer(Modifier.height(14.dp))
        Kicker("Suhoor reminder", color = Muted)
        Spacer(Modifier.height(8.dp))
        ChipFlow {
            listOf(0 to "Off", 30 to "30 min before Imsak", 60 to "1 h before Imsak").forEach { (m, text) ->
                TagChip(text, s.suhoorReminder == m) {
                    vm.updateSettings { it.copy(suhoorReminder = m) }
                    if (m > 0 && android.os.Build.VERSION.SDK_INT >= 33 &&
                        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
    }
}

/** "First day   ‹  Mon 8 Feb 2027  ›" */
@Composable
private fun DayStepper(label: String, value: String, canBack: Boolean, canForward: Boolean, onBack: () -> Unit, onForward: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontFamily = Sans, fontSize = 12.sp, color = Muted)
            Text(value, fontFamily = Sans, fontSize = 16.sp, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StepButton("−", "$label a day earlier", canBack, onBack)
        Spacer(Modifier.width(8.dp))
        StepButton("+", "$label a day later", canForward, onForward)
    }
}

@Composable
private fun StepButton(text: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = CircleShape
    Box(
        Modifier
            .size(40.dp)
            .focusRing(shape)
            .clip(shape)
            .background(if (enabled) Ink3 else Color.Transparent, shape)
            .then(if (enabled) Modifier.clickable(onClickLabel = description, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = if (enabled) Paper else Faint)
    }
}
