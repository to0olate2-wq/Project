package app.falak.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.data.AppState
import app.falak.player.data.Channel
import coil.compose.AsyncImage

/** A one-line text box in the app's style. */
@Composable
private fun Field(value: String, hint: String, onChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().panel(RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (value.isEmpty()) Text(hint, color = Faint, fontFamily = Sans, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Paper, fontFamily = Sans, fontSize = 14.sp),
            cursorBrush = SolidColor(Signal),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Long-press → "Rename or change logo…": your own name and logo for a channel. */
@Composable
fun ChannelEditDialog(vm: LibraryViewModel, ch: Channel, onDismiss: () -> Unit) {
    val original = remember(ch.id) { vm.providerChannel(ch) }
    val current = vm.channelEdit(ch)
    var name by remember(ch.id) { mutableStateOf(current?.name?.ifBlank { null } ?: ch.name) }
    var logo by remember(ch.id) { mutableStateOf(current?.logo.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename or change logo", fontFamily = Serif) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                AsyncImage(
                    model = logo.trim().ifBlank { original.logo }.ifBlank { null },
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Ink3),
                )
                Spacer(Modifier.height(12.dp))
                Kicker("Name", color = Muted)
                Spacer(Modifier.height(4.dp))
                Field(name, original.name) { name = it }
                Text("Your provider calls it “${original.name}”", fontFamily = Sans, fontSize = 12.sp, color = Faint, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(12.dp))
                Kicker("Logo", color = Muted)
                Spacer(Modifier.height(4.dp))
                Field(logo, "A picture's web address (https://…)") { logo = it }
                Text(
                    "Leave it empty for your provider's logo. Your names and logos show in the lists, the player and the guide, and on your linked devices.",
                    fontFamily = Sans,
                    fontSize = 12.sp,
                    color = Faint,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = name.trim()
                // A name only when it's really yours (not the one shown already, unless you had chosen one).
                val keepName = n.isNotBlank() && (n != ch.name.trim() || current?.name?.isNotBlank() == true) && n != original.name.trim()
                vm.editChannel(ch, if (keepName) n else null, logo)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { vm.editChannel(ch, null, null); onDismiss() }) { Text("Use the provider's") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun Tick(checked: Boolean) {
    Box(
        Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (checked) Signal else Color.Transparent)
            .border(1.5.dp, if (checked) Signal else Faint, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

/** Settings → Appearance → Home rows: which rows Home shows, and in what order. */
@Composable
fun HomeRowsDialog(vm: LibraryViewModel, state: AppState, onDismiss: () -> Unit) {
    val s = state.settings
    val order = HomeRows.order(s)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Home rows", fontFamily = Serif) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text("Tick the rows Home shows; Up and Down move them. Rows with nothing in them stay hidden.", fontFamily = Sans, fontSize = 13.sp, color = Muted)
                Spacer(Modifier.height(8.dp))
                order.forEachIndexed { i, key ->
                    val on = key !in s.homeHidden
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier
                                .weight(1f)
                                .focusRing(RoundedCornerShape(6.dp))
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    vm.updateSettings { it.copy(homeHidden = if (key in it.homeHidden) it.homeHidden - key else it.homeHidden + key) }
                                }
                                .padding(vertical = 9.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Tick(on)
                            Spacer(Modifier.width(12.dp))
                            Text(HomeRows.label(key), fontFamily = Sans, fontSize = 15.sp, color = if (on) Paper else Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (i > 0) TagChip("Up", false) { vm.updateSettings { it.copy(homeRows = HomeRows.moved(it, key, -1)) } }
                        Spacer(Modifier.width(6.dp))
                        if (i < order.lastIndex) TagChip("Down", false) { vm.updateSettings { it.copy(homeRows = HomeRows.moved(it, key, 1)) } }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = if (s.homeRows.isNotEmpty() || s.homeHidden.isNotEmpty()) {
            { TextButton(onClick = { vm.updateSettings { it.copy(homeRows = emptyList(), homeHidden = emptyList()) } }) { Text("As it was") } }
        } else {
            null
        },
    )
}

/** Settings → Remote buttons: a job for your remote's buttons (the colour ones, say) while watching. */
@Composable
fun RemoteButtonsDialog(vm: LibraryViewModel, state: AppState, onDismiss: () -> Unit) {
    val jobs = state.settings.keyActions
    var listening by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<Int?>(null) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = { if (listening) listening = false else onDismiss() },
        title = { Text(if (picking != null) "${KeyActions.keyName(picking!!)} button" else "Remote buttons", fontFamily = Serif) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                val key = picking
                when {
                    key != null -> {
                        Text("While watching, this button:", fontFamily = Sans, fontSize = 13.sp, color = Muted)
                        Spacer(Modifier.height(8.dp))
                        ChipFlow {
                            KeyActions.ALL.forEach { (job, label) ->
                                TagChip(label, jobs[key.toString()] == job) {
                                    vm.updateSettings { it.copy(keyActions = it.keyActions + (key.toString() to job)) }
                                    picking = null
                                }
                            }
                        }
                    }
                    listening -> {
                        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .panel(RoundedCornerShape(12.dp))
                                .focusRequester(focus)
                                .onPreviewKeyEvent { e ->
                                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                                    val code = e.nativeKeyEvent.keyCode
                                    if (code == android.view.KeyEvent.KEYCODE_BACK || code == android.view.KeyEvent.KEYCODE_ESCAPE) {
                                        listening = false
                                    } else if (KeyActions.assignable(code)) {
                                        listening = false
                                        refused = null
                                        picking = code
                                    } else {
                                        refused = KeyActions.keyName(code)
                                    }
                                    true
                                }
                                .focusable(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Press the button on your remote", fontFamily = Sans, fontSize = 16.sp, color = Paper)
                        }
                        refused?.let {
                            Text("“$it” keeps its own job: try another button (a colour one, say).", fontFamily = Sans, fontSize = 13.sp, color = Amber, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    else -> {
                        Text(
                            "Give buttons on your remote a job while watching, such as Record on the red one or the TV guide on the green one. " +
                                "The remote's own Record button records already.",
                            fontFamily = Sans,
                            fontSize = 13.sp,
                            color = Muted,
                        )
                        Spacer(Modifier.height(10.dp))
                        jobs.entries.sortedBy { it.key.toIntOrNull() ?: 0 }.forEach { (code, job) ->
                            val c = code.toIntOrNull() ?: return@forEach
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${KeyActions.keyName(c)}  →  ${KeyActions.label(job)}", fontFamily = Sans, fontSize = 15.sp, color = Paper, modifier = Modifier.weight(1f))
                                TagChip("Change", false) { picking = c }
                                Spacer(Modifier.width(6.dp))
                                TagChip("Remove", false) { vm.updateSettings { it.copy(keyActions = it.keyActions - code) } }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        PaperButton("Add a button") {
                            refused = null
                            listening = true
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    picking != null -> picking = null
                    listening -> listening = false
                    else -> onDismiss()
                }
            }) { Text(if (picking != null || listening) "Back" else "Done") }
        },
    )
}
