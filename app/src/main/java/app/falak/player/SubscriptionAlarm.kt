package app.falak.player

import android.app.AlarmManager
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
import app.falak.player.data.Account
import app.falak.player.data.AccountStore
import app.falak.player.data.Accounts
import app.falak.player.ui.MainActivity
import app.falak.player.ui.detectTv

/**
 * Subscription watch on phones and tablets: a notification 7 days and 1 day before an Xtream
 * subscription ends, while the app is closed (an ordinary, inexact alarm; it rings through
 * [AlertReceiver]). On a TV the reminder shows in the app only. Planned again each time the
 * account is read, so a renewal moves or cancels it.
 */
internal object SubscriptionAlarm {
    const val ACTION = "app.falak.player.SUBSCRIPTION_ALARM"
    private const val EXTRA_PLAYLIST = "sub_playlist"
    private const val EXTRA_NAME = "sub_name"
    private const val EXTRA_EXPIRES = "sub_expires"
    private const val CHANNEL = "subscription"
    private const val DAY = 86_400_000L

    fun plan(context: Context, playlistId: String, playlistName: String, a: Account?) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val exp = a?.expires
        val at = if (exp == null || detectTv(context)) null else listOf(exp - 7 * DAY, exp - DAY).firstOrNull { it > now + 60_000L }
        val pi = pending(context, playlistId, playlistName, exp ?: 0L)
        if (at == null) {
            runCatching { am.cancel(pi) }
            return
        }
        runCatching { am.setWindow(AlarmManager.RTC_WAKEUP, at, 60 * 60_000L, pi) }
    }

    fun cancel(context: Context, playlistId: String) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(pending(context, playlistId, "", 0L)) }
    }

    /** The alarm rang: remind, unless the subscription was renewed meanwhile; then plan the next one. */
    fun onAlarm(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_PLAYLIST) ?: return
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val expires = intent.getLongExtra(EXTRA_EXPIRES, 0L)
        val saved = AccountStore.get(context, id)
        // Renewed or removed since: nothing to say.
        if (saved == null || saved.expires != expires || expires <= System.currentTimeMillis()) return
        val app = context.applicationContext as? FalakApp
        if (app != null && app.repo.state.value.playlists.none { it.id == id }) return
        notify(context, id, name, saved)
        plan(context, id, name, saved)
    }

    private fun pending(context: Context, playlistId: String, name: String, expires: Long): PendingIntent {
        val intent = Intent(context, AlertReceiver::class.java)
            .setAction(ACTION)
            // One alarm per playlist: the data URI tells them apart.
            .setData(android.net.Uri.parse("falak-sub://" + android.net.Uri.encode(playlistId)))
            .putExtra(EXTRA_PLAYLIST, playlistId)
            .putExtra(EXTRA_NAME, name)
            .putExtra(EXTRA_EXPIRES, expires)
        return PendingIntent.getBroadcast(context, 7, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notify(context: Context, playlistId: String, name: String, a: Account) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val exp = a.expires ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (nm?.getNotificationChannel(CHANNEL) == null) {
                    nm?.createNotificationChannel(
                        NotificationChannel(CHANNEL, "Subscription", NotificationManager.IMPORTANCE_DEFAULT).apply {
                            description = "A reminder before your playlist subscription ends"
                        },
                    )
                }
            }
            val format = android.text.format.DateFormat.getMediumDateFormat(context)
            val ends = Accounts.endsLabel(exp, System.currentTimeMillis(), { format.format(java.util.Date(it)) })
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val tap = PendingIntent.getActivity(context, 7, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Your subscription $ends")
                .setContentText(
                    (if (name.isNotBlank()) "$name · " else "") + format.format(java.util.Date(exp)) +
                        ". Renew it with your provider to keep watching.",
                )
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(("sub:$playlistId").hashCode(), n)
        } catch (e: SecurityException) {
            // permission withdrawn meanwhile
        } catch (e: Exception) {
            // a reminder is a convenience
        }
    }
}
