package app.falak.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * A film's page and a series' page (8.19), in the look of Home, Films and Series: the title's
 * picture behind the page (the same drawing as there, MarqueeBackdrop), its name in the big serif
 * over it, then the buttons and the story.
 *  - TV: the picture fills the screen and stays put (a poster alone: a wide slice of it on the
 *    right); the words keep to the left of it.
 *  - A phone held upright: the poster edge to edge at the top, the name over its lower part; it
 *    fades as the page scrolls.
 *  - Tablets and phones on their side: wide art across the top, or the poster standing on the right.
 */

/** How a title's page is laid out on this screen. */
internal class TitleStageInfo(val tv: Boolean, val portrait: Boolean, /** How tall the picture is. */ val art: Dp)

/** The picture of a film or a series behind its page, the page over it, and Back at the top left. */
@Composable
internal fun TitleStage(
    wide: String?,
    poster: String?,
    name: String,
    /** The page's list: where the picture scrolls with the page, it fades as the list moves. */
    list: LazyListState,
    onBack: () -> Unit,
    content: @Composable (TitleStageInfo) -> Unit,
) {
    val tv = isTv()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val portrait = !tv && maxWidth < 600.dp && maxHeight > maxWidth
        val art = when {
            tv -> maxHeight
            portrait -> minOf(maxHeight * 0.52f, maxWidth * 1.25f)
            else -> maxHeight * 0.8f
        }
        MarqueeBackdrop(
            backdrop = wide?.takeIf { it.isNotBlank() },
            poster = poster?.takeIf { it.isNotBlank() },
            name = name,
            list = list,
            mode = when {
                portrait -> ArtMode.PORTRAIT
                tv -> ArtMode.BAND
                else -> ArtMode.POSTER
            },
            scrolls = !tv,
            full = tv,
            modifier = Modifier.fillMaxWidth().height(art),
        )
        content(TitleStageInfo(tv, portrait, art))
        // (Over the page, so it is there however far the page has scrolled.)
        IconButton(onClick = onBack, modifier = Modifier.padding(start = pageMargin() - 12.dp, top = 6.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Paper)
        }
    }
}

/**
 * The top of a title's page: what it is, its name, a line about it, its story and its buttons.
 * On a TV the story comes before the buttons (as on Home); on a phone the buttons come first, in
 * reach of the thumb, and the story is whole.
 */
@Composable
internal fun TitleHead(
    stage: TitleStageInfo,
    kicker: String,
    name: String,
    meta: String,
    genre: String?,
    plot: String?,
    buttons: @Composable () -> Unit,
) {
    val names: @Composable () -> Unit = {
        Kicker(kicker)
        Spacer(Modifier.height(6.dp))
        Text(
            name,
            fontFamily = Serif,
            fontWeight = FontWeight.Bold,
            fontSize = if (stage.tv) 40.sp else if (stage.portrait) 30.sp else 32.sp,
            lineHeight = if (stage.tv) 46.sp else if (stage.portrait) 35.sp else 38.sp,
            color = Paper,
            maxLines = if (stage.tv) 2 else 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (meta.isNotEmpty()) {
            Text(meta, style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.padding(top = 6.dp))
        }
        if (!genre.isNullOrBlank()) {
            Text(genre, style = MaterialTheme.typography.bodySmall, color = Amber, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
    val story: @Composable () -> Unit = {
        if (!plot.isNullOrBlank()) {
            Text(
                plot,
                style = MaterialTheme.typography.bodyLarge,
                color = if (stage.portrait) Paper else Muted,
                // A TV has no way to open a cut-off story: room for a long one, the rows still in reach.
                maxLines = if (stage.tv) 6 else if (stage.portrait) Int.MAX_VALUE else 8,
                overflow = TextOverflow.Ellipsis,
                // Readable line length on wide screens.
                modifier = Modifier.widthIn(max = 760.dp),
            )
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = pageMargin())) {
        if (stage.portrait) {
            // The name over the lower part of the poster.
            Box(Modifier.fillMaxWidth().height(stage.art - 4.dp)) {
                Column(Modifier.align(Alignment.BottomStart)) { names() }
            }
            Spacer(Modifier.height(16.dp))
            buttons()
            Spacer(Modifier.height(18.dp))
            story()
        } else {
            // Clear of Back; the words keep to the left of the picture.
            Spacer(Modifier.height(if (stage.tv) 60.dp else 56.dp))
            Column(Modifier.fillMaxWidth(if (stage.tv) 0.56f else 0.6f)) {
                names()
                if (!plot.isNullOrBlank()) Spacer(Modifier.height(12.dp))
                story()
            }
            Spacer(Modifier.height(18.dp))
            buttons()
        }
    }
}
