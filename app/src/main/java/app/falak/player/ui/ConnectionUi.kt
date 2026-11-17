package app.falak.player.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.falak.player.data.AppState
import app.falak.player.data.ConnectionCheck
import app.falak.player.data.Playlist
import app.falak.player.data.SourceType
import app.falak.player.data.mine
import app.falak.player.repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings → Player → Check the connection (8.31): three checks one after another, each shown as it
 * finishes, then which part is the slow one. On mobile data the internet test is smaller (5 MB).
 */
@Composable
fun ConnectionCheckDialog(vm: LibraryViewModel, state: AppState, p: Playlist?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repo = context.repo
    var net by remember { mutableStateOf<ConnectionCheck.Measure?>(null) }
    var provider by remember { mutableStateOf<ConnectionCheck.Measure?>(null) }
    var stream by remember { mutableStateOf<ConnectionCheck.Measure?>(null) }
    var step by remember { mutableIntStateOf(0) }
    var round by remember { mutableIntStateOf(0) }
    val index = vm.index
    // The channel watched last (else the first one): how fast a real channel comes.
    val channel = remember(p?.id, index) {
        p?.let { state.lastLive[state.mine(it.id)] }?.let { index.byId[it] }?.takeIf { it.url.startsWith("http") }
            ?: index.live.firstOrNull { it.url.startsWith("http") }
    }
    LaunchedEffect(round) {
        net = null; provider = null; stream = null
        step = 1
        val metered = onMobileData(context)
        net = withContext(Dispatchers.IO) {
            val size = if (metered) 5_000_000L else 25_000_000L
            ConnectionCheck.measure(repo.http, ConnectionCheck.speedUrl(size), size, 8_000)
        }
        step = 2
        provider = when {
            p == null -> null
            p.type == SourceType.XTREAM -> withContext(Dispatchers.IO) {
                val base = p.url.trimEnd('/')
                ConnectionCheck.measure(
                    repo.http,
                    "$base/player_api.php?username=${java.net.URLEncoder.encode(p.username, "UTF-8")}&password=${java.net.URLEncoder.encode(p.password, "UTF-8")}",
                    200_000, 10_000, p.userAgent,
                )
            }
            p.type == SourceType.M3U_URL && p.url.startsWith("http") -> withContext(Dispatchers.IO) {
                ConnectionCheck.measure(repo.http, p.url, 300_000, 10_000, p.userAgent)
            }
            else -> null
        }
        step = 3
        stream = channel?.let { ch ->
            withContext(Dispatchers.IO) { ConnectionCheck.measure(repo.http, ch.url, 8_000_000, 7_000, ch.userAgent.ifBlank { p?.userAgent.orEmpty() }) }
        }
        step = 4
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Check the connection") },
        text = {
            Column {
                Line(
                    "Your internet",
                    when {
                        net == null -> if (step == 1) "Measuring…" else ""
                        net!!.failed != null -> "No answer (${net!!.failed})"
                        else -> ConnectionCheck.rate(net!!.mbps) + " · " + ConnectionCheck.goodFor(net!!.mbps)
                    },
                )
                if (p != null && (p.type != SourceType.M3U_FILE)) {
                    Line(
                        "Your provider",
                        when {
                            step < 2 -> "Waiting"
                            provider == null -> if (step == 2) "Asking…" else "—"
                            provider!!.failed != null -> "Didn't answer (${provider!!.failed})"
                            else -> "Answered in " + String.format(java.util.Locale.US, "%.1f s", provider!!.firstByteMs / 1000.0)
                        },
                    )
                }
                if (channel != null) {
                    Line(
                        "A channel: ${channel.name}",
                        when {
                            step < 3 -> "Waiting"
                            stream == null -> if (step == 3) "Measuring…" else "—"
                            stream!!.failed != null -> "Didn't open (${stream!!.failed})"
                            else -> ConnectionCheck.rate(stream!!.mbps) + " from your provider · " + ConnectionCheck.goodFor(stream!!.mbps)
                        },
                    )
                }
                if (step >= 4) {
                    Text(
                        ConnectionCheck.verdict(net, provider, stream),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { if (step >= 4) TextButton(onClick = { round++ }) { Text("Check again") } },
    )
}

@Composable
private fun Line(title: String, value: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (value.isNotEmpty()) Text(value, style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}
