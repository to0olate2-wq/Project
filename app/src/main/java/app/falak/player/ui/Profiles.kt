package app.falak.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.falak.player.data.AppState
import kotlinx.coroutines.delay

/**
 * "Who's watching?": shown each time the app opens while kids mode is on. The grown-up profile
 * needs the PIN (otherwise the Kids profile would be one click away from everything).
 */
@Composable
fun ProfilePicker(vm: LibraryViewModel, state: AppState, onBack: () -> Unit) {
    val tv = isTv()
    var askPin by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(8) {
            delay(50)
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    BackHandler { onBack() }
    // Opaque, and swallows taps, so nothing behind it can be reached.
    Box(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            BrandMark(if (tv) 44.dp else 38.dp)
            Spacer(Modifier.height(18.dp))
            Text("Who's watching?", fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = if (tv) 34.sp else 28.sp, color = Paper)
            Spacer(Modifier.height(if (tv) 36.dp else 28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(if (tv) 36.dp else 22.dp)) {
                ProfileCard(
                    label = "Me",
                    icon = Icons.Outlined.Person,
                    tint = Signal,
                    locked = state.settings.pin.isNotEmpty(),
                    modifier = Modifier.focusRequester(first),
                ) {
                    if (state.settings.pin.isNotEmpty()) askPin = true else vm.chooseProfile(kids = false)
                }
                ProfileCard(label = "Kids", icon = Icons.Outlined.Face, tint = Amber, locked = false) {
                    vm.chooseProfile(kids = true)
                }
            }
        }
    }
    if (askPin) {
        PinDialog(
            title = "Enter the PIN",
            onDismiss = { askPin = false },
            onSubmit = { pin ->
                val ok = pin == state.settings.pin
                if (ok) {
                    askPin = false
                    vm.chooseProfile(kids = false)
                }
                ok
            },
        )
    }
}

@Composable
private fun ProfileCard(
    label: String,
    icon: ImageVector,
    tint: Color,
    locked: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tv = isTv()
    val shape = RoundedCornerShape(20.dp)
    val tile = if (tv) 150.dp else 120.dp
    Column(
        modifier
            .width(tile + 24.dp)
            .cardFocus(shape)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(tile)
                .clip(shape)
                .background(tint.copy(alpha = 0.16f))
                .border(1.dp, tint.copy(alpha = 0.45f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(tile * 0.46f))
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = if (tv) 20.sp else 17.sp, color = Paper)
            if (locked) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Outlined.Lock, contentDescription = "Needs the PIN", tint = Faint, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** In the top bar while kids mode is on: who's watching, and a way to switch. */
@Composable
fun ProfileButton(kids: Boolean, onClick: () -> Unit) {
    if (kids) {
        val shape = CircleShape
        Text(
            "Kids",
            fontFamily = Sans,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = Color.White,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .focusRing(shape)
                .clip(shape)
                .background(Signal)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    } else {
        IconButton(onClick = onClick) {
            Icon(Icons.Outlined.Face, contentDescription = "Switch profile", tint = Paper)
        }
    }
}

/** Back on the first page: leave the app, or stay. Drawn like the rest of the app. */
@Composable
fun ExitDialog(onStay: () -> Unit, onExit: () -> Unit) {
    Dialog(onDismissRequest = onStay) {
        val stay = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            repeat(8) {
                delay(50)
                if (runCatching { stay.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
        val shape = RoundedCornerShape(18.dp)
        Column(
            Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(Ink2)
                .border(1.dp, Hairline, shape)
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(30.dp)
                Spacer(Modifier.width(12.dp))
                Text("Leave The Player?", fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = Paper)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Your playlists, favorites and place in films stay as they are for next time.",
                fontFamily = Sans,
                fontSize = 14.sp,
                color = Muted,
                textAlign = TextAlign.Start,
            )
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                GhostButton("Stay", modifier = Modifier.focusRequester(stay), onClick = onStay)
                Spacer(Modifier.width(10.dp))
                PaperButton("Exit", onClick = onExit)
            }
        }
    }
}
