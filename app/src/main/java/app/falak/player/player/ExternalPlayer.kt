package app.falak.player.player

import android.content.Intent
import android.net.Uri

/**
 * "Another app (VLC, MX Player…)" (8.35): the intent that hands a stream over. Android shows its
 * own "Open with" list, with "Always" to skip it next time. The provider's User-Agent and Referer
 * go along as `headers` (MX Player and Just Player read them; VLC ignores them), so providers that
 * insist on them play there too.
 */
object ExternalPlayer {
    fun headers(userAgent: String, referrer: String): Array<String> = buildList {
        if (userAgent.isNotBlank()) { add("User-Agent"); add(userAgent) }
        if (referrer.isNotBlank()) { add("Referer"); add(referrer) }
    }.toTypedArray()

    fun intent(uri: Uri, title: String, userAgent: String, referrer: String): Intent {
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/*")
            .putExtra("title", title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val h = headers(userAgent, referrer)
        if (h.isNotEmpty()) i.putExtra("headers", h)
        return i
    }
}
