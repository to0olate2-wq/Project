package app.falak.player.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.falak.player.data.Channel
import app.falak.player.data.Epg
import app.falak.player.data.Kind
import app.falak.player.data.Program
import app.falak.player.data.VodInfo
import kotlinx.coroutines.delay

/*
 * The Spotlight look (8.14), TV: the top of Home, Films and Series shows the card the remote is
 * on. A channel: what's on now, how long is left and what's next. A film or series: its rating,
 * year and story, with its picture behind (see MarqueeStage). The rows fill the screen under it.
 * Before the remote is on a card, the page's featured titles take turns there.
 */

/** TV: cards tell the page which one the remote is on (null: the page has no spotlight). */
val LocalSpotlight = staticCompositionLocalOf<((Channel) -> Unit)?> { null }

/** A card that shows in the spotlight while the remote is on it. */
@Composable
fun Modifier.spotlights(ch: Channel): Modifier {
    val spot = LocalSpotlight.current ?: return this
    return this.onFocusChanged { if (it.isFocused) spot(ch) }
}

/** The spotlight's height: always the same, so the rows under it never move. */
val SPOTLIGHT_HEIGHT = 138.dp

/** What the spotlight says about [ch]: nothing to press, the card itself opens it. */
@Composable
fun SpotlightPanel(vm: LibraryViewModel, ch: Channel?, modifier: Modifier = Modifier) {
    val epg by vm.epg.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    Box(modifier.fillMaxWidth().height(SPOTLIGHT_HEIGHT)) {
        if (ch != null) {
            AnimatedContent(
                targetState = ch,
                contentKey = { it.id },
                transitionSpec = { fadeIn(tween(160)).togetherWith(fadeOut(tween(90))) },
                label = "spotlight",
                modifier = Modifier.fillMaxWidth(0.58f).padding(start = 4.dp),
            ) { item ->
                if (item.kind == Kind.LIVE) {
                    LiveSpot(item, epg[item.epgKey], now)
                } else {
                    TitleSpot(item, vm.heroInfo[item.id])
                }
            }
        }
    }
}

@Composable
private fun LiveSpot(ch: Channel, guide: List<Program>?, now: Long) {
    val current = Epg.current(guide, now)
    val next = Epg.next(guide, now)
    val time = rememberTimeFormat()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveDot(6.dp)
            Spacer(Modifier.width(7.dp))
            Kicker(if (current != null) "Live now" else "Live TV")
            ch.tidyQuality?.let {
                Spacer(Modifier.width(10.dp))
                CardBadge(it, Amber)
            }
        }
        Spacer(Modifier.height(6.dp))
        SpotTitle(current?.title?.takeIf { it.isNotBlank() } ?: ch.name)
        Spacer(Modifier.height(4.dp))
        SpotLine(
            if (current != null) {
                "${ch.name}   ·   ${time.format(java.util.Date(current.start))} – ${time.format(java.util.Date(current.stop))}   ·   ${minutesLeft(current, now)}"
            } else {
                ch.group.ifBlank { " " }
            },
        )
        if (current != null) {
            val length = (current.stop - current.start).coerceAtLeast(1L).toFloat()
            Spacer(Modifier.height(9.dp))
            Box(Modifier.width(280.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Paper.copy(alpha = 0.22f))) {
                Box(Modifier.fillMaxWidth(((now - current.start) / length).coerceIn(0f, 1f)).height(3.dp).background(Signal))
            }
        }
        val under = when {
            next != null && next.title.isNotBlank() -> "Next   ·   ${time.format(java.util.Date(next.start))}   ${next.title}"
            current != null && current.desc.isNotBlank() -> current.desc
            else -> null
        }
        if (under != null) {
            Spacer(Modifier.height(9.dp))
            Text(under, fontFamily = Sans, fontSize = 13.sp, color = Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TitleSpot(ch: Channel, details: VodInfo?) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Kicker(
                when {
                    ch.isNew() && ch.kind == Kind.SERIES -> "New series"
                    ch.isNew() -> "New film"
                    ch.kind == Kind.SERIES -> "Series"
                    else -> "Film"
                },
            )
            qualityTag(ch)?.let {
                Spacer(Modifier.width(10.dp))
                CardBadge(it, Paper)
            }
        }
        Spacer(Modifier.height(6.dp))
        SpotTitle(ch.name)
        val meta = listOfNotNull(
            ratingLabel(details?.rating?.takeIf { it.isNotBlank() } ?: ch.rating),
            details?.year?.takeIf { it.isNotBlank() }?.take(4),
            (details?.genre?.takeIf { it.isNotBlank() }?.substringBefore(',') ?: ch.group.takeIf { it.isNotBlank() }),
            details?.duration?.takeIf { it.isNotBlank() },
            ch.info.takeIf { it.isNotBlank() },
        ).joinToString("   ·   ")
        Spacer(Modifier.height(4.dp))
        SpotLine(meta.ifEmpty { " " })
        details?.plot?.takeIf { it.isNotBlank() }?.let { plot ->
            Spacer(Modifier.height(6.dp))
            Text(plot, fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SpotTitle(text: String) {
    Text(
        text,
        fontFamily = Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        color = Paper,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SpotLine(text: String) {
    Text(text, fontFamily = Sans, fontSize = 14.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
