package app.falak.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.PrayerAlerts
import app.falak.player.data.AppState
import app.falak.player.data.PrayerTimes
import app.falak.player.data.SunTimes
import app.falak.player.repo
import java.util.Calendar
import java.util.TimeZone

/** A small crescent, drawn (no icon font needed). */
@Composable
private fun Crescent(color: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2
        drawCircle(color, r, center)
        drawCircle(Color.Transparent, r * 0.82f, center + Offset(r * 0.42f, -r * 0.18f), blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
    }
}

/** The crescent as an icon (its cut-out needs its own layer). */
@Composable
internal fun CrescentIcon(color: Color, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).graphicsLayerOffscreen()) { Crescent(color, size) }
}

/**
 * At a prayer time: a banner at the top for a couple of minutes. In the player, with "pause"
 * chosen, it says the video paused and OK continues (the player handles the keys).
 */
@Composable
fun BoxScope.PrayerBanner(alert: PrayerAlerts.Alert?, paused: Boolean, onDismiss: () -> Unit) {
    val tv = isTv()
    AnimatedVisibility(
        visible = alert != null,
        enter = slideInVertically(tween(320)) { -it } + fadeIn(tween(220)),
        exit = slideOutVertically(tween(240)) { -it } + fadeOut(tween(180)),
        modifier = Modifier.align(Alignment.TopCenter).padding(top = if (tv) 24.dp else 12.dp),
    ) {
        val a = alert ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(50)
        Row(
            Modifier
                .widthIn(max = 560.dp)
                .clip(shape)
                .background(Color(0xF2141210))
                .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
                .padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Off-screen drawing for the crescent's cut-out needs its own layer.
            Box(Modifier.size(22.dp).graphicsLayerOffscreen()) { Crescent(Color(0xFFF2C14E), 22.dp) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("${a.name}  ·  ${a.time}", fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = if (tv) 17.sp else 15.sp, color = Color.White)
                Text(
                    if (paused) "Paused for the prayer · ${if (tv) "OK" else "Play"} continues" else "Time for prayer",
                    fontFamily = Sans,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "Dismiss", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun minuteOfDay(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

private fun Modifier.graphicsLayerOffscreen(): Modifier =
    this.then(Modifier.graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen })

/** Settings → Prayer times: on or off (off by default), today's times, and how they're worked out. */
@Composable
fun PrayerScreen(vm: LibraryViewModel, state: AppState) {
    val back: () -> Unit = { vm.screen = Screen.SETTINGS }
    BackHandler { back() }
    val context = LocalContext.current
    val s = state.settings
    val tv = isTv()
    val zone = TimeZone.getDefault()
    val place = remember { SunTimes.place(context, zone) }
    val fmt = rememberTimeFormat()
    // Recomputed when the method or Asr choice changes.
    val today = remember(s.prayerMethod, s.prayerAsr) { PrayerAlerts.today(context, context.repo) }
    var nowMin by remember { mutableIntStateOf(minuteOfDay()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            nowMin = minuteOfDay()
        }
    }
    // After Isha the next one is tomorrow's Fajr.
    val laterToday = today.firstOrNull { it.minute > nowMin }
    val next = laterToday ?: today.firstOrNull()
    val auto = PrayerTimes.defaultMethod(zone.id)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .then(if (tv) Modifier.widthIn(max = 780.dp) else Modifier)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (tv) 24.dp else 16.dp),
        ) {
            PageHeader("Prayer times", onBack = back, subtitle = place.city + if (place.guessed) " (approximate)" else "")
            Text(
                "A reminder at each prayer while The Player is open, worked out on this device for ${place.city} " +
                    "(from its time zone; nothing goes online). The player can pause for it: OK continues.",
                fontFamily = Sans,
                fontSize = 13.sp, lineHeight = 19.sp,
                color = Muted,
            )
            Spacer(Modifier.height(14.dp))
            ChipFlow {
                listOf("off" to "Off", "remind" to "Reminder", "pause" to "Reminder and pause").forEach { (key, label) ->
                    TagChip(label, s.prayerMode == key) { vm.updateSettings { it.copy(prayerMode = key) } }
                }
            }
            Spacer(Modifier.height(20.dp))
            Kicker("Today", color = Muted)
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().panel(RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 6.dp)) {
                today.forEachIndexed { i, p ->
                    val isNext = p == next
                    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            p.name,
                            fontFamily = Sans,
                            fontWeight = if (isNext) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 16.sp,
                            color = if (isNext) Signal else Paper,
                            modifier = Modifier.weight(1f),
                        )
                        if (isNext) {
                            Text(if (laterToday == null) "Tomorrow" else "Next", fontFamily = Sans, fontSize = 12.sp, color = Signal, modifier = Modifier.padding(end = 12.dp))
                        }
                        Text(PrayerTimes.label(p.minute, fmt), style = Tabular.copy(fontSize = 16.sp), color = if (isNext) Signal else Paper)
                    }
                    if (i < today.lastIndex) Rule()
                }
            }
            Spacer(Modifier.height(20.dp))
            Kicker("Calculation", color = Muted)
            Spacer(Modifier.height(8.dp))
            ChipFlow {
                TagChip("Automatic (${auto.label})", s.prayerMethod.isEmpty()) { vm.updateSettings { it.copy(prayerMethod = "") } }
                PrayerTimes.Method.entries.filter { it != auto }.forEach { m ->
                    TagChip(m.label, s.prayerMethod == m.key) { vm.updateSettings { it.copy(prayerMethod = m.key) } }
                }
            }
            Spacer(Modifier.height(14.dp))
            Kicker("Asr", color = Muted)
            Spacer(Modifier.height(8.dp))
            ChipFlow {
                listOf("" to "Automatic", "std" to "Standard", "hanafi" to "Hanafi").forEach { (key, label) ->
                    TagChip(label, s.prayerAsr == key) { vm.updateSettings { it.copy(prayerAsr = key) } }
                }
            }
            RamadanSettings(vm)
            Spacer(Modifier.height(28.dp))
        }
    }
}
