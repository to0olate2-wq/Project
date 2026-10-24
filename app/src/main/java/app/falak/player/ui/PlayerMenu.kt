package app.falak.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** One place the player's Menu can go: a section of the library or a page. */
class MenuPlace(val key: String, val label: String, val section: Section? = null)

/**
 * The player's Menu, drawn like the app's side menu: the same icons, type and remote-control
 * focus, sliding in over the picture. Picking a place closes the player and opens it there.
 */
@Composable
fun PlayerMenu(open: Boolean, nowPlaying: String?, places: List<MenuPlace>, onPick: (String) -> Unit, onClose: () -> Unit) {
    val tv = isTv()
    AnimatedVisibility(
        visible = open,
        enter = slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 3 } + fadeIn(tween(200)),
        exit = slideOutHorizontally(tween(240, easing = FastOutSlowInEasing)) { -it / 3 } + fadeOut(tween(160)),
    ) {
        val first = remember { FocusRequester() }
        // The remote starts on the first place as soon as the menu is on screen.
        LaunchedEffect(Unit) {
            repeat(8) {
                delay(45)
                if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
        // Tapping the picture beside the menu closes it.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).pointerInput(Unit) { detectTapGestures { onClose() } }) {
            Column(
                Modifier
                    .width(if (tv) 300.dp else 260.dp)
                    .fillMaxHeight()
                    .background(Ink2)
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, top = if (tv) 20.dp else 16.dp, bottom = 8.dp),
            ) {
                if (!nowPlaying.isNullOrBlank()) {
                    Box(Modifier.padding(start = 10.dp, bottom = 2.dp)) { Kicker("Now playing", color = Faint) }
                    Text(
                        nowPlaying,
                        fontFamily = Sans,
                        fontSize = 14.sp,
                        color = Paper,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 16.dp),
                    )
                }
                Box(Modifier.padding(start = 10.dp, bottom = 6.dp)) { Kicker("Go to", color = Faint) }
                places.forEachIndexed { i, place ->
                    NavItem(
                        icon = placeIcon(place),
                        label = place.label,
                        count = null,
                        selected = false,
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    ) { onPick(place.key) }
                }
            }
            // Always in view at the bottom, however many places there are above.
            Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
            Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                NavItem(rememberVectorPainter(Icons.Outlined.Close), "Keep watching", null, false, onClick = onClose)
            }
            }
        }
    }
}

@Composable
private fun placeIcon(place: MenuPlace): Painter = when {
    place.section != null -> navIcon(place.section)
    place.key == "GUIDE" -> rememberVectorPainter(Icons.Outlined.DateRange)
    place.key == "NOTICES" -> rememberVectorPainter(Icons.Outlined.Notifications)
    place.key == "MATCHES" -> androidx.compose.ui.res.painterResource(app.falak.player.R.drawable.ic_nav_matches)
    else -> rememberVectorPainter(Icons.Outlined.Settings)
}
