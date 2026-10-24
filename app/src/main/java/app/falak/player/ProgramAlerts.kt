package app.falak.player

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.falak.player.data.Alerts
import app.falak.player.data.ProgramAlert
import app.falak.player.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Programme reminders and alert words, as they happen. While the app is on screen, a card comes
 * up [Alerts.LEAD_MS] before the start with "Watch now" (the lists and the player show it). While
 * it isn't, a notification comes shortly before instead (Android may hold it a few minutes to save
 * battery). The list of what's coming up is worked out by the library screen ([update]).
 */
object ProgramAlerts {
    private val _current = MutableStateFlow<ProgramAlert?>(null)

    /** The reminder on screen now, if any. */
    val current: StateFlow<ProgramAlert?> = _current.asStateFlow()

    @Volatile private var upcoming: List<ProgramAlert> = emptyList()

    /** Shown (or dismissed) since the app started: each one comes up once. */
    private val shown = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())
    private val tick = Runnable { check() }
    private var visible = false

    /** What's coming up, soonest first (see [Alerts.upcoming]). Any thread. */
    fun update(list: List<ProgramAlert>) {
        main.post {
            upcoming = list
            if (visible) check()
        }
    }

    /** The app came on screen ([on]) or left it: in-app cards, or notifications planned instead. */
    fun setVisible(context: Context, on: Boolean) {
        visible = on
        if (on) {
            AlertAlarm.cancel(context)
            check()
        } else {
            main.removeCallbacks(tick)
            val now = System.currentTimeMillis()
            val left = upcoming.filter { it.key !in shown && it.start > now }
            Thread({ runCatching { AlertAlarm.plan(context.applicationContext, left) } }, "alert-plan").start()
        }
    }

    /** Closes the card (it doesn't come back). */
    fun dismiss() {
        _current.value = null
        check()
    }

    /** Screenshot tests: a reminder on screen. */
    @androidx.annotation.VisibleForTesting
    fun showForTest(alert: ProgramAlert?) {
        _current.value = alert
    }

    private fun check() {
        main.removeCallbacks(tick)
        if (!visible) return
        val now = System.currentTimeMillis()
        _current.value?.let { cur ->
            // Over, or the reminder was cancelled meanwhile.
            if (now >= cur.start + Alerts.LATE_MS || upcoming.none { it.key == cur.key }) _current.value = null
        }
        if (_current.value == null) {
            val due = upcoming.firstOrNull { it.key !in shown && now >= it.start - Alerts.LEAD_MS && now < it.start + Alerts.LATE_MS }
            if (due != null) {
                shown += due.key
                _current.value = due
            }
        }
        val nextDue = upcoming.asSequence().filter { it.key !in shown && it.start - Alerts.LEAD_MS > now }.map { it.start - Alerts.LEAD_MS }.minOrNull()
        val expiry = _current.value?.let { it.start + Alerts.LATE_MS }
        val at = listOfNotNull(nextDue, expiry).minOrNull() ?: return
        main.postDelayed(tick, (at - now).coerceIn(1_000L, 3_600_000L))
    }
}

/**
 * Notifications for reminders while the app is closed: the list is saved, and one alarm (not an
 * exact one: no special permission) wakes the app in the 10 minutes before the next programme.
 */
internal object AlertAlarm {
    private const val FILE = "alerts.json"
    private const val CHANNEL = "reminders"
    private const val WINDOW_MS = 10 * 60_000L

    /** Extras on a notification's tap: play this channel of this playlist. */
    const val EXTRA_PLAYLIST = "alert_playlist"
    const val EXTRA_CHANNEL = "alert_channel"

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ProgramAlert.serializer())

    fun plan(context: Context, list: List<ProgramAlert>) {
        val keep = list.sortedBy { it.start }.take(80)
        write(context, keep)
        schedule(context, keep.firstOrNull()?.start)
    }

    fun cancel(context: Context) {
        runCatching { context.getSystemService(AlarmManager::class.java)?.cancel(pending(context)) }
    }

    /** The alarm went off: notify what's about to start, then wait for the next one. */
    fun onAlarm(context: Context) {
        val now = System.currentTimeMillis()
        val list = read(context)
        val due = list.filter { now >= it.start - WINDOW_MS && now < it.start + Alerts.LATE_MS }
        due.take(4).forEach { notify(context, it) }
        val rest = list.filter { it.start - WINDOW_MS > now }
        write(context, rest)
        schedule(context, rest.firstOrNull()?.start)
    }

    private fun schedule(context: Context, start: Long?) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context)
        if (start == null) {
            am.cancel(pi)
            return
        }
        val now = System.currentTimeMillis()
        val from = maxOf(now + 5_000L, start - WINDOW_MS)
        runCatching { am.setWindow(AlarmManager.RTC_WAKEUP, from, (start - from).coerceAtLeast(60_000L), pi) }
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, AlertReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun write(context: Context, list: List<ProgramAlert>) {
        val f = File(context.filesDir, FILE)
        if (list.isEmpty()) f.delete() else f.writeText(json.encodeToString(serializer, list))
    }

    private fun read(context: Context): List<ProgramAlert> = try {
        val f = File(context.filesDir, FILE)
        if (f.exists()) json.decodeFromString(serializer, f.readText()) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun notify(context: Context, a: ProgramAlert) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (nm?.getNotificationChannel(CHANNEL) == null) {
                    nm?.createNotificationChannel(
                        NotificationChannel(CHANNEL, "Programme reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                            description = "Programmes you asked to be reminded of, and your alert words"
                        },
                    )
                }
            }
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_PLAYLIST, a.playlistId)
                .putExtra(EXTRA_CHANNEL, a.channelId)
            val tap = PendingIntent.getActivity(context, a.key.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val time = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(a.start))
            val text = buildString {
                append(a.channelName).append(" · ").append(time)
                if (a.alsoOn > 0) append(" · also on ").append(a.alsoOn).append(if (a.alsoOn == 1) " other channel" else " other channels")
            }
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(a.title)
                .setContentText(text)
                .setSubText(if (a.word != null) "Alert: ${a.word}" else "Reminder")
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setWhen(a.start)
                .setShowWhen(true)
                .setTimeoutAfter((a.start + Alerts.LATE_MS - System.currentTimeMillis()).coerceAtLeast(60_000L))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(a.key.hashCode(), n)
        } catch (e: SecurityException) {
            // permission withdrawn meanwhile
        } catch (e: Exception) {
            // a reminder is a convenience
        }
    }
}

/** Wakes up for [AlertAlarm]. */
class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val done = goAsync()
        Thread({
            try {
                AlertAlarm.onAlarm(context.applicationContext)
            } catch (e: Exception) {
                // nothing to do
            } finally {
                done.finish()
            }
        }, "alert-alarm").start()
    }
}
