package app.falak.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.data.AppState
import app.falak.player.data.ProgramAlert
import kotlinx.coroutines.delay
import java.util.Date

/**
 * Settings → Reminders & alerts: what's coming up (reminders set in the guide, and programmes
 * found by alert words), the alert words, and how reminders arrive.
 */
internal fun LazyListScope.remindersPage(
    vm: LibraryViewModel,
    state: AppState,
    first: Modifier,
    notificationsOff: Boolean,
    onAddWord: () -> Unit,
) {
    val coming = vm.upcomingAlerts
    item { SectionTitle("Coming up", first = true) }
    if (vm.playlist == null) {
        item { Note("Open a playlist to see what's coming up.") }
    } else if (coming.isEmpty()) {
        item {
            Note(
                "Nothing coming up. In the TV guide, pick a programme that hasn't started yet and choose " +
                    "“Remind me”, or add alert words below.",
            )
        }
    } else {
        items(coming.take(25), key = { "a:" + it.key }) { a -> AlertRow(a, vm) }
    }

    item { SectionTitle("Alert words") }
    item {
        Note(
            "Any programme with these words in its name comes up as a reminder, on the channels you can see. " +
                "The TV guide's search can add one too (“Alert me”).",
        )
    }
    item {
        ChipFlow(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
            for (w in state.alertWords) TagChip("$w  ✕", false) { vm.toggleAlertWord(w) }
            TagChip("+ Add alert word", true, modifier = first) { onAddWord() }
        }
    }

    item { SectionTitle("How you're reminded") }
    item {
        Note(
            "Two minutes before it starts, a card comes up over the lists and the player: Watch now (OK on the remote) " +
                "switches to the channel. With the app closed, a notification comes shortly before instead " +
                "(phones and tablets; Android may hold it a few minutes to save battery)." +
                if (notificationsOff) " Notifications are off for The Player, so reminders only show while it's open." else "",
        )
    }
}

/** One programme coming up; a reminder set by hand can be cancelled. */
@Composable
private fun AlertRow(a: ProgramAlert, vm: LibraryViewModel) {
    val fmt = rememberTimeFormat()
    val now = System.currentTimeMillis()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(92.dp)) {
            Text(fmt.format(Date(a.start)), style = Tabular.copy(fontSize = 14.sp), color = Paper)
            Text(dayLabel(a.start, now), fontFamily = Sans, fontSize = 11.sp, color = Faint)
        }
        Column(Modifier.weight(1f)) {
            Text(a.title, style = MaterialTheme.typography.bodyLarge, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                a.channelName + (if (a.word != null) "  ·  alert: ${a.word}" else "") +
                    if (a.alsoOn > 0) "  ·  +${a.alsoOn} channels" else "",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (a.word == null) {
            Spacer(Modifier.width(8.dp))
            val shape = RoundedCornerShape(8.dp)
            Text(
                "Cancel",
                fontFamily = Sans,
                fontSize = 13.sp,
                color = Muted,
                modifier = Modifier
                    .focusRing(shape)
                    .clip(shape)
                    .clickable { vm.cancelReminder(a.channelId, a.start) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** A name to be alerted about ("Real Madrid", "الهلال", "Formula 1"). */
@Composable
internal fun AlertWordDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { field.requestFocus() }
    }
    val add = {
        if (text.isNotBlank()) onAdd(text.trim())
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alert me about…") },
        text = {
            Column {
                Text(
                    "A team, a show, a film: whenever a programme with these words in its name is coming up, you're reminded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                Spacer(Modifier.width(1.dp))
                OutlinedTextField(
                    text,
                    { text = it.take(60) },
                    singleLine = true,
                    label = { Text("Words") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).focusRequester(field),
                )
            }
        },
        confirmButton = { TextButton(onClick = add, enabled = text.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
