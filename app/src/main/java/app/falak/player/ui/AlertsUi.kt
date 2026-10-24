package app.falak.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.data.Alerts
import app.falak.player.data.ProgramAlert
import kotlinx.coroutines.delay

/**
 * A reminder coming up: what, where and when, with "Watch now". On the lists it takes the remote's
 * focus (Back closes it); in the player the player handles the keys ([keysHint]: OK watches, Back
 * closes), and touch works everywhere.
 */
@Composable
fun BoxScope.ProgramAlertCard(
    alert: ProgramAlert?,
    keysHint: Boolean,
    onWatch: (ProgramAlert) -> Unit,
    onDismiss: () -> Unit,
) {
    val tv = isTv()
    if (alert != null && !keysHint) BackHandler { onDismiss() }
    AnimatedVisibility(
        visible = alert != null,
        enter = slideInVertically(tween(320)) { -it } + fadeIn(tween(220)),
        exit = slideOutVertically(tween(240)) { -it } + fadeOut(tween(180)),
        modifier = Modifier
            .align(if (tv) Alignment.TopEnd else Alignment.TopCenter)
            .padding(top = if (tv) 24.dp else 12.dp, end = if (tv) 28.dp else 0.dp, start = if (tv) 0.dp else 12.dp),
    ) {
        val a = alert ?: return@AnimatedVisibility
        val now by produceState(System.currentTimeMillis(), a.key) {
            while (true) {
                value = System.currentTimeMillis()
                delay(15_000)
            }
        }
        val shape = RoundedCornerShape(16.dp)
        val watch = remember { FocusRequester() }
        // On the lists, the remote lands on "Watch now" so OK is all it takes.
        if (!keysHint && tv) LaunchedEffect(a.key) {
            delay(150)
            runCatching { watch.requestFocus() }
        }
        Column(
            Modifier
                .widthIn(max = if (tv) 420.dp else 520.dp)
                .clip(shape)
                .background(Color(0xF5141210))
                .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Notifications, contentDescription = null, tint = Color(0xFFF2C14E), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(7.dp))
                Text(
                    (if (a.word != null) "Alert · ${a.word}" else "Reminder").uppercase(),
                    fontFamily = Sans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    letterSpacing = 1.2.sp,
                    color = Color(0xFFF2C14E),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                a.title,
                fontFamily = Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (tv) 21.sp else 19.sp,
                lineHeight = if (tv) 26.sp else 24.sp,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            val more = when (a.alsoOn) {
                0 -> ""
                1 -> " · also on 1 other channel"
                else -> " · also on ${a.alsoOn} other channels"
            }
            Text(
                "${a.channelName} · ${Alerts.whenLabel(a.start, now)}$more",
                fontFamily = Sans,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            if (keysHint && tv) {
                Text(
                    "OK  watch now     ·     Back  close",
                    fontFamily = Sans,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.85f),
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Always light on dark here (over the picture or either theme).
                    FalakTheme(dark = true, layout = if (tv) "tv" else "phone") {
                        PaperButton("Watch now", Icons.Filled.PlayArrow, Modifier.focusRequester(watch)) { onWatch(a) }
                        GhostButton("Close") { onDismiss() }
                    }
                }
            }
        }
    }
}

/**
 * Subscription watch: the reminder that the playlist's subscription ends within a week (or has
 * ended), over the lists. "OK" puts it away until tomorrow.
 */
@Composable
fun BoxScope.SubscriptionCard(text: String?, onDismiss: () -> Unit) {
    val tv = isTv()
    if (text != null) BackHandler { onDismiss() }
    AnimatedVisibility(
        visible = text != null,
        enter = slideInVertically(tween(320)) { -it } + fadeIn(tween(220)),
        exit = slideOutVertically(tween(240)) { -it } + fadeOut(tween(180)),
        modifier = Modifier
            .align(if (tv) Alignment.TopEnd else Alignment.TopCenter)
            .padding(top = if (tv) 24.dp else 12.dp, end = if (tv) 28.dp else 12.dp, start = if (tv) 0.dp else 12.dp),
    ) {
        val message = text ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(16.dp)
        val ok = remember { FocusRequester() }
        // On a TV the remote lands on "OK", so one press puts it away.
        if (tv) LaunchedEffect(message) {
            delay(150)
            runCatching { ok.requestFocus() }
        }
        Column(
            Modifier
                .widthIn(max = if (tv) 420.dp else 520.dp)
                .clip(shape)
                .background(Color(0xF5141210))
                .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Text(
                "SUBSCRIPTION",
                fontFamily = Sans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                letterSpacing = 1.2.sp,
                color = Color(0xFFF2C14E),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                fontFamily = Sans,
                fontSize = if (tv) 16.sp else 15.sp,
                lineHeight = if (tv) 22.sp else 21.sp,
                color = Color.White,
            )
            Spacer(Modifier.height(12.dp))
            FalakTheme(dark = true, layout = if (tv) "tv" else "phone") {
                PaperButton("OK", modifier = Modifier.focusRequester(ok)) { onDismiss() }
            }
        }
    }
}
