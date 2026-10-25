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
import app.falak.player.data.PrayerTimes
import app.falak.player.data.Ramadan
import app.falak.player.data.RamadanCalendar
import app.falak.player.data.Repository
import app.falak.player.ui.MainActivity
import app.falak.player.ui.detectTv
import java.util.Calendar
import java.util.TimeZone

/** Ramadan mode where the user is: today's phase, and the lines Home and the ambient screen show. */
object RamadanNow {
    /** Screenshot tests: a moment in Ramadan. */
    @androidx.annotation.VisibleForTesting
    @Volatile
    var nowForTest: Long? = null

    fun now(): Long = nowForTest ?: System.currentTimeMillis()
    private fun day(ymd: Int, zone: TimeZone): Calendar = Calendar.getInstance(zone).apply {
        clear()
        set(ymd / 10_000, ymd / 100 % 100 - 1, ymd % 100, 12, 0, 0)
    }

    /** This year's Ramadan (dates moved in Settings included); null when Ramadan mode is off. */
    fun span(repo: Repository, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): Ramadan.Span? {
        val s = repo.state.value.settings
        if (s.ramadanMode == "off") return null
        return runCatching { RamadanCalendar.current(now, s.ramadanDates, zone) }.getOrNull()
    }

    /** Where [now] stands (see [Ramadan.Phase]); null when there's nothing to show. */
    fun phase(context: Context, repo: Repository, now: Long = System.currentTimeMillis()): Pair<Ramadan.Span, Ramadan.Phase>? {
        val zone = TimeZone.getDefault()
        val span = span(repo, now, zone) ?: return null
        val phase = runCatching {
            Ramadan.phase(span, now, zone) { ymd -> PrayerAlerts.today(context, repo, day(ymd, zone)) }
        }.getOrNull() ?: return null
        return span to phase
    }

    /** A title and a line under it, for Home and the ambient screen. */
    fun lines(context: Context, phase: Ramadan.Phase, now: Long): Pair<String, String> {
        val time = android.text.format.DateFormat.getTimeFormat(context)
        fun clock(at: Long) = time.format(java.util.Date(at))
        return when (phase) {
            is Ramadan.Phase.Fasting ->
                "Iftar in ${Ramadan.countdown(now, phase.iftar)}" to "Ramadan · day ${phase.day} · Maghrib at ${clock(phase.iftar)}"
            is Ramadan.Phase.Night ->
                "Suhoor until ${clock(phase.imsak)}" to "Ramadan · day ${phase.day} begins · Imsak in ${Ramadan.countdown(now, phase.imsak)}"
            is Ramadan.Phase.Soon -> {
                val first = java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault())
                    .format(java.util.Date(Ramadan.startOf(phase.first) + 12 * 3_600_000L))
                (if (phase.days == 1) "Ramadan begins tomorrow" else "Ramadan begins in ${phase.days} days") to
                    "Ramadan Kareem · the first fast is on $first"
            }
            Ramadan.Phase.Eid -> "Eid Mubarak" to "Eid al-Fitr · Ramadan is over"
        }
    }
}

/**
 * Ramadan mode on phones: a notification a set time before Imsak (Settings → Prayer times), while
 * the app is closed too (an ordinary, inexact alarm through [AlertReceiver]). Planned each time the
 * app opens and after each reminder; on a TV nothing (its notifications aren't seen).
 */
internal object SuhoorAlarm {
    const val ACTION = "app.falak.player.SUHOOR_ALARM"
    private const val EXTRA_IMSAK = "imsak"
    private const val CHANNEL = "suhoor"

    fun plan(context: Context, repo: Repository) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val s = repo.state.value.settings
        val now = System.currentTimeMillis()
        val zone = TimeZone.getDefault()
        val span = if (s.suhoorReminder > 0 && !detectTv(context)) RamadanNow.span(repo, now, zone) else null
        var next: Pair<Long, Long>? = null
        if (span != null) {
            var d = maxOf(Ramadan.ymd(now, zone), span.first)
            while (d <= span.last && next == null) {
                val fajr = runCatching {
                    PrayerAlerts.today(context, repo, Calendar.getInstance(zone).apply {
                        clear()
                        set(d / 10_000, d / 100 % 100 - 1, d % 100, 12, 0, 0)
                    }).getOrNull(0)?.minute
                }.getOrNull()
                if (fajr != null) {
                    val imsak = Ramadan.startOf(d, zone) + (fajr - Ramadan.IMSAK_MINUTES) * 60_000L
                    val at = imsak - s.suhoorReminder * 60_000L
                    if (at > now + 60_000L) next = at to imsak
                }
                d = Ramadan.plusDays(d, 1)
            }
        }
        val (at, imsak) = next ?: run {
            runCatching { am.cancel(pending(context, 0L)) }
            return
        }
        // Rings in the ten minutes before the chosen time (Android may hold it a little).
        runCatching { am.setWindow(AlarmManager.RTC_WAKEUP, at - 10 * 60_000L, 10 * 60_000L, pending(context, imsak)) }
    }

    fun onAlarm(context: Context, intent: Intent) {
        val app = context.applicationContext as? FalakApp ?: return
        val imsak = intent.getLongExtra(EXTRA_IMSAK, 0L)
        if (imsak > System.currentTimeMillis()) notify(context, imsak)
        plan(context, app.repo)
    }

    private fun pending(context: Context, imsak: Long): PendingIntent {
        val intent = Intent(context, AlertReceiver::class.java).setAction(ACTION).putExtra(EXTRA_IMSAK, imsak)
        return PendingIntent.getBroadcast(context, 9, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notify(context: Context, imsak: Long) {
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
                        NotificationChannel(CHANNEL, "Suhoor", NotificationManager.IMPORTANCE_HIGH).apply {
                            description = "During Ramadan: a reminder before Imsak"
                        },
                    )
                }
            }
            val time = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(imsak))
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val tap = PendingIntent.getActivity(context, 9, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Suhoor · Imsak at $time")
                .setContentText("${Ramadan.countdown(System.currentTimeMillis(), imsak)} left to eat before the fast")
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(9_301, n)
        } catch (e: SecurityException) {
            // permission withdrawn meanwhile
        } catch (e: Exception) {
            // a reminder is a convenience
        }
    }
}
