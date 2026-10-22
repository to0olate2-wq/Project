package app.falak.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.falak.player.data.AppState
import app.falak.player.data.KIDS_PROFILE
import kotlinx.coroutines.delay

/** Profile colours, by [Profile.color]. */
internal val ProfileTints = listOf(
    Color(0xFFFF6A4D), Color(0xFF3DB8A8), Color(0xFF5B8DEF), Color(0xFFB07CE8), Color(0xFF7BC86C), Color(0xFFE86FA6),
)

/** One choice in "Who's watching?". */
private class Choice(val id: String, val name: String, val tint: Color, val kids: Boolean)

/**
 * "Who's watching?": each time the app opens while kids mode is on or there are extra profiles.
 * With kids mode on, the grown-up profiles need the PIN (otherwise the Kids profile would be one
 * click away from everything).
 */
@Composable
fun ProfilePicker(vm: LibraryViewModel, state: AppState, onBack: () -> Unit) {
    val tv = isTv()
    var askPin by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(8) {
            delay(50)
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    BackHandler { onBack() }
    val s = state.settings
    val choices = buildList {
        add(Choice("", s.mainProfileName.ifBlank { "Me" }, ProfileTints[0], kids = false))
        if (s.profilesOn) state.profiles.forEach { add(Choice(it.id, it.name, ProfileTints[(it.color + 1) % ProfileTints.size], kids = false)) }
        if (s.kidsMode) add(Choice(KIDS_PROFILE, "Kids", Amber, kids = true))
    }
    val needsPin = s.kidsMode && s.pin.isNotEmpty()
    // Opaque, and swallows taps, so nothing behind it can be reached.
    Box(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 24.dp)) {
            BrandMark(if (tv) 44.dp else 38.dp)
            Spacer(Modifier.height(18.dp))
            Text("Who's watching?", fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = if (tv) 34.sp else 28.sp, color = Paper)
            Spacer(Modifier.height(if (tv) 36.dp else 28.dp))
            // Bigger cards when there are few; a row you can scroll when there are many.
            val tile = when {
                choices.size <= 3 -> if (tv) 150.dp else 104.dp
                choices.size <= 5 -> if (tv) 124.dp else 92.dp
                else -> if (tv) 104.dp else 84.dp
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(if (tv) 28.dp else 12.dp),
            ) {
                choices.forEachIndexed { i, c ->
                    val locked = needsPin && !c.kids
                    ProfileCard(
                        label = c.name,
                        tint = c.tint,
                        kids = c.kids,
                        locked = locked,
                        tile = tile,
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    ) {
                        if (locked) askPin = c.id else vm.chooseProfile(c.id)
                    }
                }
            }
        }
    }
    askPin?.let { id ->
        PinDialog(
            title = "Enter the PIN",
            onDismiss = { askPin = null },
            onSubmit = { pin ->
                val ok = pin == s.pin
                if (ok) {
                    askPin = null
                    vm.chooseProfile(id)
                }
                ok
            },
        )
    }
}

@Composable
private fun ProfileCard(
    label: String,
    tint: Color,
    kids: Boolean,
    locked: Boolean,
    tile: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tv = isTv()
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .width(tile + 24.dp)
            .cardFocus(shape)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ProfileTile(label, tint, kids, tile)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontFamily = Sans,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (tv) 20.sp else 16.sp,
                color = Paper,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = tile),
            )
            if (locked) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Outlined.Lock, contentDescription = "Needs the PIN", tint = Faint, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** A profile's square: the Kids face, or the name's first letter in the profile's colour. */
@Composable
internal fun ProfileTile(label: String, tint: Color, kids: Boolean, size: androidx.compose.ui.unit.Dp) {
    val shape = RoundedCornerShape(size * 0.14f)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(tint.copy(alpha = 0.16f))
            .border(1.dp, tint.copy(alpha = 0.45f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (kids) {
            Icon(Icons.Outlined.Face, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.46f))
        } else {
            Text(
                label.trim().take(1).uppercase().ifEmpty { "?" },
                fontFamily = Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.42f).sp,
                color = tint,
            )
        }
    }
}

/** In the top bar when there are profiles: who's watching (tap to switch). */
@Composable
fun ProfileButton(state: AppState, kids: Boolean, onClick: () -> Unit) {
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
        return
    }
    val p = state.profiles.firstOrNull { it.id == state.activeProfile }
    val name = p?.name ?: state.settings.mainProfileName.ifBlank { "Me" }
    val tint = ProfileTints[if (p == null) 0 else (p.color + 1) % ProfileTints.size]
    val shape = CircleShape
    Box(
        Modifier
            .padding(horizontal = 6.dp)
            .size(32.dp)
            .focusRing(shape)
            .clip(shape)
            .background(tint.copy(alpha = 0.22f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.trim().take(1).uppercase(), fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = tint)
    }
}

/**
 * Settings → Profiles: on or off (off by default), the profiles, add, rename, delete. Each profile
 * keeps its own favorites, lists, recently watched and Continue watching; hidden groups, locks and
 * playlists are shared.
 */
@Composable
fun ProfilesScreen(vm: LibraryViewModel, state: AppState) {
    val back: () -> Unit = { vm.screen = Screen.SETTINGS }
    BackHandler { back() }
    val s = state.settings
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { (context.applicationContext as app.falak.player.FalakApp).repo }
    var naming by remember { mutableStateOf<String?>(null) } // profile id being renamed; "+" = new; "" = main
    var deleting by remember { mutableStateOf<app.falak.player.data.Profile?>(null) }
    val tv = isTv()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .then(if (tv) Modifier.widthIn(max = 780.dp) else Modifier)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (tv) 24.dp else 16.dp),
        ) {
            PageHeader("Profiles", onBack = back)
            Text(
                "Each person gets their own favorites, lists, recently watched and Continue watching. " +
                    "The app asks who's watching when it opens. Playlists, hidden groups and locks are shared.",
                fontFamily = Sans,
                fontSize = 13.sp, lineHeight = 19.sp,
                color = Muted,
            )
            Spacer(Modifier.height(12.dp))
            ToggleRow("Profiles", if (s.profilesOn) "On" else "Off", s.profilesOn) { vm.setProfilesOn(!s.profilesOn) }
            if (s.profilesOn) {
                Spacer(Modifier.height(18.dp))
                Kicker("Who's watching", color = Muted)
                Spacer(Modifier.height(8.dp))
                ProfileRow(s.mainProfileName.ifBlank { "Me" }, ProfileTints[0], "Main profile", onRename = { naming = "" })
                state.profiles.forEach { pr ->
                    ProfileRow(
                        pr.name,
                        ProfileTints[(pr.color + 1) % ProfileTints.size],
                        "This device only",
                        onRename = { naming = pr.id },
                        onDelete = { deleting = pr },
                    )
                }
                if (s.kidsMode) ProfileRow("Kids", Amber, "Kids mode (Settings → Kids profile)", kids = true)
                Spacer(Modifier.height(12.dp))
                if (state.profiles.size < MAX_PROFILES) PaperButton("Add a profile") { naming = "+" }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    naming?.let { id ->
        val current = when (id) {
            "+" -> ""
            "" -> s.mainProfileName
            else -> state.profiles.firstOrNull { it.id == id }?.name.orEmpty()
        }
        NameDialog(if (id == "+") "New profile" else "Rename", current, onDismiss = { naming = null }) { name ->
            when (id) {
                "+" -> repo.addProfile(name)
                "" -> vm.updateSettings { it.copy(mainProfileName = name.trim().ifEmpty { "Me" }) }
                else -> repo.renameProfile(id, name)
            }
            naming = null
        }
    }
    deleting?.let { pr ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${pr.name}?") },
            text = { Text("Their favorites, lists and Continue watching go too.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    repo.deleteProfile(pr.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

private const val MAX_PROFILES = 5

@Composable
private fun ToggleRow(title: String, subtitle: String, on: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .panel(shape)
            .focusRing(shape)
            .clip(shape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = Sans, fontSize = 15.sp, color = Paper)
            Text(subtitle, fontFamily = Sans, fontSize = 12.sp, color = Muted)
        }
        androidx.compose.material3.Switch(
            checked = on,
            onCheckedChange = null,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Signal,
                checkedBorderColor = Signal,
                uncheckedThumbColor = Muted,
                uncheckedTrackColor = Ink3,
                uncheckedBorderColor = Faint,
            ),
        )
    }
}

@Composable
private fun ProfileRow(
    name: String,
    tint: Color,
    note: String,
    kids: Boolean = false,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        ProfileTile(name, tint, kids, 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Paper)
            Text(note, fontFamily = Sans, fontSize = 12.sp, color = Faint)
        }
        if (onRename != null) GhostButton("Rename", onClick = onRename)
        if (onDelete != null) {
            Spacer(Modifier.width(8.dp))
            GhostButton("Delete", onClick = onDelete)
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(20) },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = { androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
                fontSize = 14.sp, lineHeight = 20.sp,
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
