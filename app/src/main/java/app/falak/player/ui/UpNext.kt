package app.falak.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.data.Channel
import coil.compose.AsyncImage

/**
 * The next episode, in the corner while this one's credits roll: "Playing in 8" counts down to it.
 * On TV, OK plays it now and Back stays on this one (the player handles the keys).
 */
@Composable
fun BoxScope.UpNextCard(next: Channel?, label: String, seconds: Int?, onPlay: () -> Unit, onHide: () -> Unit) {
    val tv = isTv()
    AnimatedVisibility(
        visible = next != null,
        enter = slideInHorizontally(tween(320)) { it / 3 } + fadeIn(tween(220)),
        exit = slideOutHorizontally(tween(240)) { it / 3 } + fadeOut(tween(180)),
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = if (tv) 40.dp else 16.dp, bottom = if (tv) 120.dp else 96.dp),
    ) {
        val ep = next ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier
                .width(if (tv) 430.dp else 310.dp)
                .clip(shape)
                .background(Color(0xF0141210))
                .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.width(if (tv) 132.dp else 104.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2623)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (ep.logo.isNotBlank()) {
                        AsyncImage(model = ep.logo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(28.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "UP NEXT" + if (label.isNotBlank()) "  ·  $label" else "",
                        fontFamily = Sans,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 1.2.sp,
                        color = Color(0xFFFF6A4D),
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(ep.name, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val pill = RoundedCornerShape(50)
                Row(
                    Modifier.clip(pill).background(Color.White).clickable(onClick = onPlay).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (seconds != null) "Playing in $seconds" else "Play next",
                        fontFamily = Sans,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = Color.Black,
                    )
                    if (tv) KeyHint("OK", dark = true)
                }
                Row(
                    Modifier.clip(pill).border(1.dp, Color.White.copy(alpha = 0.35f), pill).clickable(onClick = onHide).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Keep watching", fontFamily = Sans, fontSize = 14.sp, color = Color.White, maxLines = 1, softWrap = false)
                    if (tv) KeyHint("Back", dark = false)
                }
            }
        }
    }
}

/** "Skip intro", bottom right while a learned intro plays. OK (TV) or a tap skips it. */
@Composable
fun BoxScope.SkipIntroButton(visible: Boolean, onSkip: () -> Unit) {
    val tv = isTv()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200)),
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = if (tv) 40.dp else 16.dp, bottom = if (tv) 120.dp else 96.dp),
    ) {
        val pill = RoundedCornerShape(50)
        Row(
            Modifier
                .clip(pill)
                .background(Color(0xE6141210))
                .border(1.5.dp, Color.White.copy(alpha = 0.85f), pill)
                .clickable(onClick = onSkip)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Skip intro", fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = if (tv) 16.sp else 15.sp, color = Color.White)
            if (tv) KeyHint("OK", dark = false)
        }
    }
}

/**
 * Tuned in to a show already under way on a channel with replay: "From the start" for a few
 * seconds (OK on the remote, or a tap). [minutes]: how long ago it started.
 */
@Composable
fun BoxScope.StartOverButton(visible: Boolean, title: String, minutes: Int, onStart: () -> Unit) {
    val tv = isTv()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200)),
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = if (tv) 40.dp else 16.dp, bottom = if (tv) 120.dp else 96.dp),
    ) {
        val pill = RoundedCornerShape(50)
        Row(
            Modifier
                .clip(pill)
                .background(Color(0xE6141210))
                .border(1.5.dp, Color.White.copy(alpha = 0.85f), pill)
                .clickable(onClick = onStart)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("⟲  From the start", fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = if (tv) 16.sp else 15.sp, color = Color.White)
                Text(
                    "$title · started $minutes min ago",
                    fontFamily = Sans,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 320.dp),
                )
            }
            if (tv) KeyHint("OK", dark = false)
        }
    }
}

/** A small key cap ("OK", "Back") after a button's label, so the remote's key is obvious. */
@Composable
private fun KeyHint(key: String, dark: Boolean) {
    Spacer(Modifier.width(8.dp))
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (dark) Color.Black.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.16f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(key, fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = if (dark) Color.Black else Color.White, maxLines = 1, softWrap = false)
    }
}
