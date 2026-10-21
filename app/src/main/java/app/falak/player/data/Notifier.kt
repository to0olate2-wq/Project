package app.falak.player.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.falak.player.R
import app.falak.player.ui.MainActivity

/** "New in Strong: 27 films · 3 channels" — shown when a playlist refresh finds new content. */
object Notifier {
    private const val CHANNEL = "new_content"
    /** Extra on the notification's tap: open What's new. */
    const val EXTRA_OPEN = "open"

    fun newContent(context: Context, notice: Notice) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return // What's new in the app still lists it
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (nm?.getNotificationChannel(CHANNEL) == null) {
                    nm?.createNotificationChannel(
                        NotificationChannel(CHANNEL, "New channels, films and series", NotificationManager.IMPORTANCE_DEFAULT).apply {
                            description = "When a playlist refresh finds something new"
                        },
                    )
                }
            }
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN, "NOTICES")
            val tap = PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val lines = notice.groups.take(5).joinToString("\n") { g ->
                val what = when (g.kind) {
                    Kind.LIVE -> if (g.count == 1) "channel" else "channels"
                    Kind.MOVIE -> if (g.count == 1) "film" else "films"
                    Kind.SERIES -> "series"
                }
                "${g.count} $what in ${g.group}"
            }
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("New in ${notice.playlistName}")
                .setContentText(notice.summary())
                .setStyle(NotificationCompat.BigTextStyle().bigText(notice.summary() + if (lines.isNotEmpty()) "\n$lines" else ""))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            // One notification per playlist: a newer refresh replaces the older one.
            NotificationManagerCompat.from(context).notify(notice.playlistId.hashCode(), n)
        } catch (e: SecurityException) {
            // permission withdrawn meanwhile
        } catch (e: Exception) {
            // notifications are a convenience
        }
    }
}
