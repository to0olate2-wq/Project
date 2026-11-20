package app.falak.player

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.falak.player.ui.MainActivity

/**
 * Long-press the app's icon (8.40, phones; the owner picked it): Live TV, the channel watched
 * last, and Search. Set once at start; Android shows them in the icon's menu and lets them be
 * dragged to the home screen. TVs have no such menu, so nothing is set there.
 */
object AppShortcuts {
    const val EXTRA = "app.falak.player.SHORTCUT"

    fun publish(context: Context) {
        if (context.packageManager.hasSystemFeature("android.software.leanback")) return
        runCatching {
            val list = listOf(
                make(context, "live", "Live TV", "Open Live TV", R.drawable.shortcut_live, "LIVE"),
                make(context, "last", "Last channel", "Play the channel watched last", R.drawable.shortcut_last, "LAST"),
                make(context, "search", "Search", "Search channels, films and series", R.drawable.shortcut_search, "SEARCH"),
            )
            ShortcutManagerCompat.setDynamicShortcuts(context, list)
        }
    }

    private fun make(context: Context, id: String, short: String, long: String, icon: Int, what: String): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(short)
            .setLongLabel(long)
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(EXTRA, what)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            .build()
}
