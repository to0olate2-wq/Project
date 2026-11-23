package app.falak.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.falak.player.autoSync
import app.falak.player.data.Handoff
import app.falak.player.data.Kind
import app.falak.player.data.LinkAnswer
import app.falak.player.data.LinkAsk
import app.falak.player.data.SyncMerge
import app.falak.player.data.TitleMatch
import app.falak.player.repo
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone as a second screen (8.42): what the linked TV plays, with the programme now and next
 * (live), or the rating and cast (films, series), and a small remote. Asks the TV every 3 s over
 * Wi-Fi linking; nothing goes past the home network except the rating and cast (Cinemeta, as on a
 * film's page).
 */
@Composable
fun SecondScreenDialog(vm: LibraryViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val sync = context.autoSync
    val repo = context.repo
    val scope = rememberCoroutineScope()
    val test = SecondScreenTest.answer
    var devices by remember { mutableStateOf(if (test != null) SecondScreenTest.devices else sync.nearby()) }
    var chosen by remember { mutableStateOf(devices.firstOrNull()?.second) }
    var answer by remember { mutableStateOf<LinkAnswer?>(null) }
    var asked by remember { mutableStateOf(false) }
    // Bumped after a key, so the screen follows the TV at once.
    var poke by remember { mutableIntStateOf(0) }
    LaunchedEffect(poke) {
        if (test != null) {
            answer = test
            asked = true
            return@LaunchedEffect
        }
        if (poke > 0) delay(700)
        while (true) {
            devices = sync.nearby()
            if (chosen == null || devices.none { it.second == chosen }) chosen = devices.firstOrNull()?.second
            val at = chosen
            answer = if (at == null) null else withContext(Dispatchers.IO) { sync.ask(at, LinkAsk("now", System.currentTimeMillis())) }
            asked = true
            delay(3_000)
        }
    }
    fun press(key: String) {
        val at = chosen ?: return
        scope.launch(Dispatchers.IO) { sync.ask(at, LinkAsk("key", System.currentTimeMillis(), key = key)) }
        poke++
    }
    val deviceName = devices.firstOrNull { it.second == chosen }?.first ?: answer?.device ?: "your TV"

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Paper) }
                Column(Modifier.weight(1f)) {
                    Text("Second screen", fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Paper)
                    Text(deviceName, fontFamily = Sans, fontSize = 12.sp, color = Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (devices.size > 1) {
                ChipFlow(Modifier.padding(horizontal = 16.dp)) {
                    devices.forEach { (name, address) -> TagChip(name, address == chosen) { chosen = address; poke++ } }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                val now = answer?.now
                when {
                    devices.isEmpty() -> Hint(
                        "No linked TV found on this Wi-Fi. Open The Player on the TV (linked in Settings → Devices & backup), " +
                            "on the same Wi-Fi as this phone.",
                    )
                    !asked -> Hint("Asking $deviceName…")
                    answer?.ok == false -> Hint(answer?.message.orEmpty())
                    now == null -> Hint("Nothing is playing on $deviceName. The remote below still works.")
                    else -> NowPlaying(vm, now, onClose)
                }
                val now2 = answer?.now
                if (now2 != null) {
                    Spacer(Modifier.height(14.dp))
                    TagChip("Play it on this phone", false) {
                        val s = repo.state.value
                        val local = s.playlists.firstOrNull { SyncMerge.syncId(s, it.id) == now2.item.playlist }
                        if (local == null) {
                            android.widget.Toast.makeText(context, "That playlist isn't on this phone yet", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            val at = chosen
                            // (Where the TV is now, not 3 s ago: the TV keeps the item, the phone asks once more.)
                            scope.launch {
                                val fresh = if (at == null) null else withContext(Dispatchers.IO) { sync.ask(at, LinkAsk("now", System.currentTimeMillis())) }
                                val item = fresh?.now?.item?.takeIf { it.channel.id == now2.item.channel.id } ?: now2.item
                                // The TV stops first (and surely: this page closes next, which would cancel a call left running).
                                if (at != null) withContext(Dispatchers.IO) { sync.ask(at, LinkAsk("stop", System.currentTimeMillis())) }
                                vm.playHandedOff(local.id, item)
                                onClose()
                            }
                        }
                    }
                }
            }
            if (devices.isNotEmpty()) RemotePad(::press)
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, fontFamily = Sans, fontSize = 15.sp, color = Muted, modifier = Modifier.padding(vertical = 24.dp))
}

@Composable
private fun NowPlaying(vm: LibraryViewModel, now: app.falak.player.data.NowState, onClose: () -> Unit) {
    val ch = now.item.channel
    val repo = LocalContext.current.repo
    val clock = android.text.format.DateFormat.getTimeFormat(LocalContext.current)
    Row(verticalAlignment = Alignment.CenterVertically) {
        val pic = ch.logo.ifBlank { null }
        Box(Modifier.size(if (ch.kind == Kind.LIVE) 64.dp else 72.dp).clip(RoundedCornerShape(10.dp)).background(Ink3), contentAlignment = Alignment.Center) {
            if (pic != null) {
                AsyncImage(pic, contentDescription = null, contentScale = if (ch.kind == Kind.LIVE) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize().padding(if (ch.kind == Kind.LIVE) 6.dp else 0.dp))
            } else {
                Text(initialsOf(ch.name), fontFamily = Sans, fontWeight = FontWeight.Bold, color = Paper)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(ch.name, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Paper, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val line = when {
                !now.playing -> "Paused"
                ch.kind == Kind.LIVE -> "Live"
                else -> Handoff.timeLine(now.item.pos, now.item.dur)
            }
            if (line.isNotEmpty()) Text(line, fontFamily = Sans, fontSize = 13.sp, color = Muted)
        }
    }
    Spacer(Modifier.height(16.dp))
    if (ch.kind == Kind.LIVE) {
        val p = now.now
        if (p != null) {
            Text("On now · ${clock.format(java.util.Date(p.start))}–${clock.format(java.util.Date(p.stop))}", fontFamily = Sans, fontSize = 12.sp, color = Faint)
            Text(p.title, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Paper)
            val wall = System.currentTimeMillis()
            if (p.stop > p.start) {
                Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(3.dp).background(Color(0x33FFFFFF))) {
                    Box(Modifier.fillMaxWidth(((wall - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f)).height(3.dp).background(Signal))
                }
            }
            if (p.desc.isNotBlank()) Text(p.desc, fontFamily = Sans, fontSize = 14.sp, color = Muted)
        } else {
            Hint("The TV guide has nothing for this channel.")
        }
        now.next?.let { n ->
            Spacer(Modifier.height(14.dp))
            Text("Next · ${clock.format(java.util.Date(n.start))}", fontFamily = Sans, fontSize = 12.sp, color = Faint)
            Text(n.title, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = Paper)
            if (n.desc.isNotBlank()) Text(n.desc, fontFamily = Sans, fontSize = 13.sp, color = Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    } else {
        // A film or an episode: its rating and cast, found by its name (as on a film's page).
        val type = if (ch.kind == Kind.SERIES) "series" else "movie"
        val meta by produceState<app.falak.player.data.AddonMeta?>(null, ch.id) {
            if (app.falak.player.data.PeopleClient.testing) return@produceState
            value = withContext(Dispatchers.IO) {
                val imdb = ch.id.removePrefix("addon:").substringBefore(':').takeIf { Regex("tt\\d{5,}").matches(it) } ?: run {
                    // An episode is looked up by its series' name (the provider's episode names say "S01E03").
                    val title = if (ch.kind == Kind.SERIES) ch.name.substringBefore(" - S").substringBefore(" S0") else ch.name
                    val (name, year) = TitleMatch.clean(title)
                    repo.imdbFor(type, name, year)
                }
                imdb?.let { runCatching { repo.imdbMeta(it, type) }.getOrNull() }
            }
        }
        val m = meta
        if (m != null) {
            val facts = listOfNotNull(m.rating.takeIf { it.isNotBlank() }?.let { "IMDb $it" }, m.year.ifBlank { null }, m.runtime.ifBlank { null })
            if (facts.isNotEmpty()) Text(facts.joinToString("  ·  "), fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Paper)
            if (m.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(m.description, fontFamily = Sans, fontSize = 14.sp, color = Muted)
            }
            val people = (m.cast.take(8) + m.director.take(2)).distinct()
            if (people.isNotEmpty() && !vm.kidsActive) {
                Spacer(Modifier.height(12.dp))
                Text("Cast", fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Paper, modifier = Modifier.padding(bottom = 6.dp))
                ChipFlow {
                    people.forEach { n ->
                        TagChip(n, false) {
                            onClose()
                            vm.openPerson(n)
                        }
                    }
                }
            }
        }
    }
}

/** The TV's remote: arrows and OK, Back, channel up/down, play/pause, info, last channel. */
@Composable
private fun RemotePad(press: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Ink2).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PadKey("CH +", "Channel up") { press("chup") }
                PadKey("CH −", "Channel down") { press("chdown") }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PadKey("▲", "Up") { press("up") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PadKey("◀", "Left") { press("left") }
                    PadKey("OK", "OK", big = true) { press("ok") }
                    PadKey("▶", "Right") { press("right") }
                }
                PadKey("▼", "Down") { press("down") }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PadKey("Back", "Back") { press("back") }
                PadKey("⏯", "Play or pause") { press("playpause") }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TagChip("Last channel", false) { press("last") }
            TagChip("Info", false) { press("info") }
        }
    }
}

@Composable
private fun PadKey(label: String, description: String, big: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(3.dp)
            .size(if (big) 64.dp else 52.dp)
            .clip(CircleShape)
            .background(if (big) Signal.copy(alpha = 0.9f) else Ink3)
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = if (label.length > 2) 13.sp else 18.sp, color = Paper, textAlign = TextAlign.Center)
    }
}

/** Screen tests: what a linked TV answers (no network under Robolectric). */
internal object SecondScreenTest {
    var devices: List<Pair<String, String>> = emptyList()
    var answer: LinkAnswer? = null
}
