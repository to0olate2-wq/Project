package app.falak.player.record

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.falak.player.R
import app.falak.player.data.Notifier
import app.falak.player.repo
import app.falak.player.ui.MainActivity

/**
 * Keeps recordings going in the background (Android needs a notification for that), and stops
 * itself when nothing records and nothing is about to start.
 */
class RecordingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var stopped = false

    private val tick = object : Runnable {
        override fun run() {
            RecordCenter.tickAsync { busy ->
                handler.post {
                    if (stopped) return@post
                    if (busy) {
                        show()
                        handler.removeCallbacks(this)
                        handler.postDelayed(this, 5_000)
                    } else {
                        finishUp()
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        RecordCenter.init(this)
        stopped = false
        try {
            val n = notification()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(NOTE_ID, n)
        } catch (e: Exception) {
            // Android won't let it run in the background now: the alarm tries again.
            RecordCenter.planAlarm()
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    /** Android 15+: background work of this kind may run 6 hours a day. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        RecordCenter.stopAll("Stopped: Android lets the app record in the background for 6 hours a day")
        finishUp()
    }

    private fun finishUp() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        RecordCenter.planAlarm()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun show() {
        if (!canNotify(this)) return
        runCatching { NotificationManagerCompat.from(this).notify(NOTE_ID, notification()) }
    }

    private fun notification(): Notification {
        channel(this)
        val now = RecordCenter.book.value.recordings.filter { it.state == RecState.RECORDING }
        val first = now.firstOrNull()
        val title = when {
            first == null -> "Getting ready to record"
            now.size == 1 -> "Recording ${first.title}"
            else -> "Recording ${now.size} programmes"
        }
        val text = when {
            first == null -> "The Player"
            now.size == 1 -> first.channelName + "  ·  " + app.falak.player.OfflineCenter.sizeLabel(first.size)
            else -> now.joinToString(" · ") { it.channelName }
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openRecordings(this))
            .build()
    }

    companion object {
        private const val NOTE_ID = 4470
        private const val CHANNEL = "recordings"

        internal fun channel(context: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (nm?.getNotificationChannel(CHANNEL) == null) {
                    nm?.createNotificationChannel(
                        NotificationChannel(CHANNEL, "Recordings", NotificationManager.IMPORTANCE_LOW).apply {
                            description = "While a programme is being recorded"
                        },
                    )
                }
            }
        }

        internal fun openRecordings(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(Notifier.EXTRA_OPEN, "RECORDINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(context, 4470, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        internal fun canNotify(context: Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        /** A recording couldn't start with the app closed: a note to open the app. */
        internal fun tellToOpen(context: Context) {
            if (!canNotify(context)) return
            channel(context)
            val next = RecordCenter.book.value.recordings.firstOrNull { it.state == RecState.SCHEDULED || it.state == RecState.RECORDING }
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Open The Player to record" + (next?.let { " ${it.title}" } ?: ""))
                .setContentText("Android didn't let the recording start in the background")
                .setAutoCancel(true)
                .setContentIntent(openRecordings(context))
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(4472, n) }
        }
    }
}

/** The alarm for the next recording, and the device starting (alarms are cleared then). */
class RecordReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        RecordCenter.init(app)
        when (intent.action) {
            RecordCenter.ALARM_ACTION -> {
                try {
                    ContextCompat.startForegroundService(app, Intent(app, RecordingService::class.java))
                } catch (e: Exception) {
                    RecordingService.tellToOpen(app)
                }
            }
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON" -> {
                // Android 15 won't start this kind of background work straight from start-up: an alarm in a moment does.
                RecordCenter.planAlarm(soon = RecordCenter.book.value.recordings.any { it.state == RecState.RECORDING })
                StartWithTv.onBoot(app)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> RecordCenter.planAlarm()
        }
    }
}

/**
 * "Open when the TV starts" (TVs, off unless switched on): the app opens when the TV starts, and
 * when it wakes from standby while the app was left open. Android 10+ lets an app open itself
 * only with "Display over other apps" allowed for it.
 */
object StartWithTv {
    @Volatile private var watching = false

    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 || android.provider.Settings.canDrawOverlays(context)

    /** Settings for "Display over other apps", for this app (or the list, where a TV has no page per app). */
    fun askIntent(context: Context): List<Intent> = listOf(
        Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + context.packageName)),
        Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
    )

    private fun wanted(context: Context): Boolean =
        context.repo.state.value.settings.startOnBoot && app.falak.player.ui.detectTv(context)

    internal fun onBoot(context: Context) {
        if (wanted(context)) open(context)
    }

    private fun open(context: Context) {
        if (!allowed(context)) return
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            )
        }
    }

    /** While the app is in memory: the TV waking from standby opens it too (when it isn't on screen already). */
    fun watchWake(context: Context, onScreen: () -> Boolean) {
        if (watching) return
        watching = true
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == Intent.ACTION_SCREEN_ON && wanted(app) && !onScreen()) open(app)
            }
        }
        runCatching { ContextCompat.registerReceiver(app, receiver, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED) }
    }
}
