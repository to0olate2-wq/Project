package app.falak.player.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.data.Channel
import app.falak.player.data.Kind
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Date

/** One picture of the ambient screen: a wide backdrop, or a poster. */
class AmbientSlide(val image: String, val title: String, val note: String, val wide: Boolean)

/**
 * Pictures for the ambient screen: wide backdrops where they're known, then posters of films and
 * series (never from hidden or locked groups, or outside the Kids profile).
 */
fun ambientSlides(movies: List<Channel>, series: List<Channel>, backdrops: Map<String, String>, excluded: Set<String>, max: Int = 24): List<AmbientSlide> {
    val pool = (movies + series).filter { it.logo.isNotBlank() && it.group !in excluded }
    val wide = pool.mapNotNull { ch -> backdrops[ch.id]?.takeIf { it.isNotBlank() }?.let { AmbientSlide(it, ch.name, noteFor(ch), wide = true) } }
    val posters = pool.shuffled().take(max).map { AmbientSlide(it.logo, it.name, noteFor(it), wide = false) }
    return (wide + posters).distinctBy { it.title }.take(max)
}

private fun noteFor(ch: Channel): String = listOfNotNull(
    if (ch.kind == Kind.SERIES) "Series" else "Film",
    ch.group.takeIf { it.isNotBlank() },
).joinToString("  ·  ")

/**
 * The ambient screen: when the app sits idle (or a video stays paused), a slow slideshow of the
 * library's pictures with the time, instead of a still picture burning into the screen. Any key or
 * a tap goes back.
 */
@Composable
fun AmbientScreen(slides: List<AmbientSlide>, paused: String?, onExit: () -> Unit) {
    val tv = isTv()
    var i by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(slides.size) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
            if ((now / 1_000) % SLIDE_S == 0L && slides.size > 1) i = (i + 1) % slides.size
        }
    }
    val h24 = is24h()
    val time = remember(now / 60_000, h24) { android.text.format.DateFormat.format(if (h24) "HH:mm" else "h:mm", Date(now)).toString() }
    // The clock drifts a little each minute so it never sits still on the same pixels.
    val step = ((now / 60_000) % DRIFT.size).toInt()
    val dx by animateDpAsState(DRIFT[step].first.dp, tween(4_000), label = "dx")
    val dy by animateDpAsState(DRIFT[step].second.dp, tween(4_000), label = "dy")
    val date = remember(now / 3_600_000) { java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault()).format(Date(now)) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0A09))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onExit),
    ) {
        val slide = slides.getOrNull(i)
        Crossfade(targetState = slide, animationSpec = tween(1_600), label = "ambient") { s ->
            if (s == null) return@Crossfade
            if (s.wide) {
                Box(Modifier.fillMaxSize()) {
                    AsyncImage(model = s.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    // Darker towards the corner with the clock, so it reads on any picture.
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.linearGradient(listOf(Color(0xE60B0A09), Color(0x660B0A09), Color.Transparent)),
                        ),
                    )
                }
            } else {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .padding(end = if (tv) 80.dp else 24.dp, top = 40.dp, bottom = 40.dp)
                            .fillMaxHeight(if (tv) 0.78f else 0.5f)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF1B1918)),
                    ) {
                        AsyncImage(model = s.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .offset(x = dx, y = dy)
                .padding(start = if (tv) 64.dp else 24.dp, bottom = if (tv) 56.dp else 32.dp, end = 24.dp)
                .fillMaxWidth(if (tv) 0.5f else 0.9f),
        ) {
            Text(time, fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = if (tv) 88.sp else 64.sp, color = Color(0xFFF7F3EC))
            Text(date, fontFamily = Sans, fontSize = if (tv) 20.sp else 16.sp, color = Color(0xCCF7F3EC))
            // Ramadan: the countdown to Iftar or Imsak.
            ramadanAmbientLine()?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = if (tv) 18.sp else 15.sp, color = Color(0xFFF2C14E), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (slide != null) {
                Spacer(Modifier.height(if (tv) 28.dp else 18.dp))
                Text(slide.title, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = if (tv) 18.sp else 15.sp, color = Color(0xFFF7F3EC), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(slide.note, fontFamily = Sans, fontSize = 13.sp, color = Color(0x99F7F3EC), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (paused != null) {
                Spacer(Modifier.height(16.dp))
                Text("Paused: $paused", fontFamily = Sans, fontSize = 13.sp, color = Color(0xFFFF6A4D), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            Text(if (tv) "Press any button to go back" else "Tap to go back", fontFamily = Sans, fontSize = 12.sp, color = Color(0x80F7F3EC))
        }
    }
}

@Composable
private fun is24h(): Boolean = android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current)

private const val SLIDE_S = 12L
private val DRIFT = listOf(0 to 0, 16 to -8, 30 to -20, 16 to -32, 0 to -20, 8 to -8)

/** Paused this long (player) or idle this long (lists) before the ambient screen comes up. */
const val AMBIENT_PAUSED_S = 90
const val AMBIENT_IDLE_MS = 5 * 60_000L
