package app.falak.player.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import coil.compose.AsyncImage

/**
 * A channel's logo, or [fallback] when it has none or its link doesn't load (many playlists have
 * broken logo links): the channel's initials in small boxes, its name on big cards. Before, those
 * were empty grey boxes.
 */
@Composable
fun LogoOr(
    url: String,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier,
    alignment: Alignment = Alignment.Center,
    fallback: @Composable () -> Unit,
) {
    var failed by remember(url) { mutableStateOf(false) }
    if (url.isBlank() || failed) {
        fallback()
    } else {
        AsyncImage(
            model = url,
            contentDescription = contentDescription,
            modifier = modifier,
            alignment = alignment,
            contentScale = contentScale,
            onError = { failed = true },
        )
    }
}

/** The channel's initials, for a logo box with no logo. */
@Composable
fun Initials(name: String, fontSize: TextUnit, modifier: Modifier = Modifier) {
    Text(
        initialsOf(name),
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = fontSize,
        color = Muted,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}
