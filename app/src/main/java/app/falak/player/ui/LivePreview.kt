package app.falak.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.data.Catchup
import app.falak.player.data.Channel
import app.falak.player.data.Epg
import app.falak.player.data.Program
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Date

/*
 * TV, Live TV as a list: beside the list, the channel the remote is on, with what's on now and
 * next, Watch, From the start (with a replay) and the guide. Resting on a channel for a moment
 * plays it small there (Settings → Picture & playback → Live preview); OK still opens it full
 * screen straight away.
 */

/** How long the remote rests on a channel before its picture starts (moving on costs nothing). */
private const val PREVIEW_DWELL_MS = 1_200L

@Composable
fun LivePreviewPane(
    vm: LibraryViewModel,
    /** The channel the remote is on (or the first of the list). */
    ch: Channel?,
    /** The list it belongs to (the player steps through it). */
    list: List<Channel>,
    epg: Map<String, List<Program>>,
    now: Long,
    /** The remote is on the list or the pane, and nothing has started full screen from it. */
    active: Boolean,
    livePreview: Boolean,
    onPlay: (List<Channel>, Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (ch == null) {
        Box(modifier)
        return
    }
    val timeFormat = rememberTimeFormat()
    val programs = epg[ch.epgKey]
    val current = Epg.current(programs, now)
    val next = Epg.next(programs, now)
    // The picture: after a moment on the same channel; at once gone when the remote moves on.
    var playing by remember { mutableStateOf<Channel?>(null) }
    LaunchedEffect(ch.id, active, livePreview) {
        if (!active || !livePreview) {
            playing = null
            return@LaunchedEffect
        }
        if (playing?.id == ch.id) return@LaunchedEffect
        playing = null
        delay(PREVIEW_DWELL_MS)
        playing = ch
    }
    // One small picture at a time.
    LaunchedEffect(playing) { if (playing != null) MiniPlayerState.close() }
    val watch: () -> Unit = {
        // Let go of the preview's connection before the player makes its own.
        playing = null
        onPlay(list, ch)
    }
    Column(modifier.verticalScroll(rememberScrollState())) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black),
        ) {
            val shown = playing
            if (shown != null && shown.id == ch.id) {
                PreviewPicture(
                    shown,
                    shown.userAgent.ifBlank { vm.playlist?.userAgent.orEmpty() },
                    onOpen = watch,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().background(Ink3), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = ch.logo.ifBlank { null },
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(0.42f).fillMaxHeight(0.5f),
                    )
                }
            }
            Text(
                "LIVE",
                fontFamily = Sans,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Signal)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            ch.name + (current?.let { " · ${timeFormat.format(Date(it.start))} – ${timeFormat.format(Date(it.stop))}" } ?: ""),
            fontFamily = Sans,
            fontSize = 12.sp,
            color = Faint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            current?.title ?: ch.name,
            fontFamily = Serif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 21.sp,
            lineHeight = 26.sp,
            color = Paper,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (current != null && current.stop > current.start) {
            val done = ((now - current.start).toFloat() / (current.stop - current.start)).coerceIn(0f, 1f)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Hairline)) {
                Box(Modifier.fillMaxWidth(done).fillMaxHeight().background(Signal))
            }
        }
        current?.desc?.takeIf { it.isNotBlank() }?.let { desc ->
            Spacer(Modifier.height(8.dp))
            Text(desc, fontFamily = Sans, fontSize = 13.sp, lineHeight = 18.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (next != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Next · ${timeFormat.format(Date(next.start))}  ${next.title}",
                fontFamily = Sans,
                fontSize = 12.sp,
                color = Faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PaperButton("Watch", Icons.Filled.PlayArrow, onClick = watch)
            if (current != null && Catchup.canReplay(ch, current, now)) {
                GhostButton("From the start") {
                    playing = null
                    vm.replay(ch, current, now)?.let { item -> onPlay(listOf(item), item) }
                }
            }
            GhostButton("Guide", Icons.Outlined.DateRange) {
                playing = null
                vm.screen = Screen.GUIDE
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
