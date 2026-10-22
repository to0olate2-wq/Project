package app.falak.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.data.AppState
import app.falak.player.falakApp
import app.falak.player.sync.SyncServer

/**
 * Settings → Phone remote: a QR code that pairs a phone as this device's remote control and
 * keyboard, and the phones already paired. Pairing works only while this page is open.
 */
@Composable
fun RemoteScreen(vm: LibraryViewModel, state: AppState) {
    val context = LocalContext.current
    val app = remember { context.falakApp }
    val tv = isTv()
    val back: () -> Unit = { vm.screen = Screen.SETTINGS }
    BackHandler { back() }
    var code by remember { mutableStateOf("") }
    var address by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        app.pairingRemote = true
        code = app.remote.openPairing()
        address = SyncServer.localAddress()?.let { ip -> if (app.remote.port > 0) "$ip:${app.remote.port}" else null }
        onDispose {
            app.remote.closePairing()
            app.pairingRemote = false
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .then(if (tv) Modifier.widthIn(max = 820.dp) else Modifier)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (tv) 24.dp else 16.dp),
        ) {
            PageHeader("Phone remote", onBack = back)
            Text(
                "Use your phone as this TV's remote and keyboard: arrows and OK, channel up and down, search typed " +
                    "on the phone, and your channels, films and series to start with a tap. Pair it once; after that it " +
                    "works whenever The Player is open here and the phone is on the same Wi-Fi.",
                fontFamily = Sans,
                fontSize = 13.sp,
                color = Muted,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().panel(RoundedCornerShape(14.dp)).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val a = address
                if (a != null) {
                    val qr = remember(a, code) { qrBitmap("http://$a/?pair=$code", 400) }
                    if (qr != null) {
                        Image(
                            qr,
                            contentDescription = "QR code to pair a phone",
                            filterQuality = FilterQuality.None,
                            modifier = Modifier
                                .size(if (tv) 168.dp else 136.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White)
                                .padding(8.dp),
                        )
                        Spacer(Modifier.width(20.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Kicker("Scan with the phone's camera", color = Muted)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        a?.let { "Or open http://$it" } ?: "Connect this device to Wi-Fi to pair a phone",
                        fontFamily = Sans,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = if (tv) 18.sp else 15.sp,
                        color = Paper,
                    )
                    if (a != null) {
                        Spacer(Modifier.height(12.dp))
                        Kicker("and enter the code", color = Muted)
                        Text(
                            code.chunked(3).joinToString(" "),
                            style = Tabular.copy(fontSize = if (tv) 30.sp else 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp),
                            color = Paper,
                        )
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Kicker(if (state.remotes.isEmpty()) "No phones paired yet" else "Paired phones", color = Muted)
            Spacer(Modifier.height(6.dp))
            val fmt = remember { java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()) }
            state.remotes.sortedByDescending { it.lastUsed }.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.label, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Paper)
                        Text("Paired ${fmt.format(java.util.Date(r.addedAt))} · last used ${fmt.format(java.util.Date(maxOf(r.lastUsed, r.addedAt)))}", fontFamily = Sans, fontSize = 12.sp, color = Faint)
                    }
                    GhostButton("Remove") { app.repo.removeRemote(r.hash) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
