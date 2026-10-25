package app.falak.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import app.falak.player.player.SleepTimer
import app.falak.player.ui.PlayerActivity

/**
 * Listen mode (phones, Settings → Picture & playback, off unless chosen): leaving the player or
 * turning the screen off while something plays hands it to this service, which carries on with
 * the sound only (no picture is decoded), with play/pause on the lock screen and in the
 * notification. Coming back to the player takes it back where it got to.
 *
 * Everything here runs on the main thread.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class ListenService : Service() {

    /** What to play: the player's current stream, as it was playing it. */
    class Item(
        val url: String,
        val title: String,
        val subtitle: String,
        val userAgent: String,
        val referrer: String,
        /** Films and episodes: where they got to; live: [C.TIME_UNSET]. */
        val position: Long,
        val live: Boolean,
        /** The sound track chosen in the player (language code), if any. */
        val audioLanguage: String?,
        /** The player's place in its list, to come back to it. */
        val index: Int,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var item: Item? = null
    private var failures = 0
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> {
                val p = player
                if (p == null) {
                    stopNow()
                } else if (p.playWhenReady) {
                    p.pause()
                } else {
                    if (p.playbackState == Player.STATE_IDLE) {
                        if (item?.live == true) p.seekToDefaultPosition()
                        p.prepare()
                    }
                    p.play()
                }
            }
            ACTION_STOP -> stopNow()
            else -> {
                val wanted = pending
                pending = null
                if (wanted != null) {
                    play(wanted)
                } else if (player == null) {
                    // Taken back before it started: Android still wants it in the foreground for a moment.
                    goForeground(basicNotification())
                    stopNow()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun play(i: Item) {
        release()
        // A sleep timer that ran out before the sound got here is over.
        if (SleepTimer.due(android.os.SystemClock.elapsedRealtime())) SleepTimer.cancel()
        item = i
        failures = 0
        val app = application as FalakApp
        val http = OkHttpDataSource.Factory(app.repo.http).setUserAgent(i.userAgent)
        if (i.referrer.isNotBlank()) http.setDefaultRequestProperties(mapOf("Referer" to i.referrer))
        val data = DefaultDataSource.Factory(this, http)
        val media = MediaItem.Builder()
            .setUri(i.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(i.title).setArtist(i.subtitle.ifBlank { null }).build())
            .apply {
                when {
                    i.url.contains("m3u8", ignoreCase = true) -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    i.url.contains(".mpd", ignoreCase = true) -> setMimeType(MimeTypes.APPLICATION_MPD)
                }
            }
            .build()
        val source = when {
            i.url.contains("m3u8", ignoreCase = true) -> HlsMediaSource.Factory(data).createMediaSource(media)
            i.url.contains(".mpd", ignoreCase = true) -> DashMediaSource.Factory(data).createMediaSource(media)
            else -> {
                val extractors = DefaultExtractorsFactory().setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
                DefaultMediaSourceFactory(data, extractors).createMediaSource(media)
            }
        }
        // FFmpeg takes the sound the phone can't decode itself (AC-3, E-AC-3, DTS, MP2…), as in the player.
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val audio = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
        val p = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(audio, true) // pauses for calls and other sound
            .setHandleAudioBecomingNoisy(true) // headphones out: pause
            .setWakeMode(C.WAKE_MODE_NETWORK) // keeps going with the screen off
            .build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .apply { i.audioLanguage?.takeIf { it.isNotBlank() }?.let { setPreferredAudioLanguage(it) } }
            .build()
        p.addListener(listener)
        if (i.position > 0) p.setMediaSource(source, i.position) else p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
        player = p
        session = runCatching {
            MediaSession.Builder(this, p)
                .setId("listen")
                .setSessionActivity(openPlayer(i.index))
                .build()
        }.getOrNull()
        goForeground(notification())
        app.repo.listening = true
        handler.post(sleepTick)
    }

    /** The player's sleep timer carries on here: the sound fades over the last minute, then stops. */
    private val sleepTick = object : Runnable {
        override fun run() {
            val p = player ?: return
            val now = android.os.SystemClock.elapsedRealtime()
            if (SleepTimer.mode == SleepTimer.Mode.AT_TIME) {
                p.volume = SleepTimer.volume(now)
                if (SleepTimer.due(now)) {
                    SleepTimer.cancel()
                    stopNow()
                    return
                }
            } else if (p.volume != 1f) {
                p.volume = 1f
            }
            handler.postDelayed(this, 1_000)
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = refresh()

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = refresh()

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> failures = 0
                // A film or episode heard to its end.
                Player.STATE_ENDED -> stopNow()
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // A dropped connection: try again a few times, then stop (the notification goes away).
            val p = player ?: return
            if (++failures > 3) {
                stopNow()
                return
            }
            handler.postDelayed({
                if (player === p) {
                    if (item?.live == true || error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) p.seekToDefaultPosition()
                    p.prepare()
                    p.play()
                }
            }, 2_000L * failures)
        }
    }

    private fun refresh() {
        if (player == null || !foreground) return
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification())
        } catch (e: SecurityException) {
            // notifications off: the sound carries on all the same
        }
    }

    private fun goForeground(n: Notification) {
        ensureChannel()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
            foreground = true
        } catch (e: Exception) {
            // Not allowed now (the app had already left the screen): no listening this time.
            release()
            stopSelf()
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Listen mode", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Play and pause while the sound plays on with the screen off"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun openPlayer(index: Int): PendingIntent {
        val open = Intent(this, PlayerActivity::class.java)
            .setAction(ACTION_BACK)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(PlayerActivity.EXTRA_INDEX, index)
        return PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(name: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, ListenService::class.java).setAction(name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun basicNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Listen mode")
            .setSilent(true)
            .build()

    private fun notification(): Notification {
        val i = item
        val playing = player?.playWhenReady == true
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(i?.title.orEmpty())
            .setContentText(i?.subtitle?.ifBlank { null } ?: "Listen mode · tap for the picture")
            .setContentIntent(openPlayer(i?.index ?: 0))
            .setDeleteIntent(action(ACTION_STOP, 3))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(playing)
            .setShowWhen(false)
            .addAction(
                if (playing) R.drawable.ic_ls_pause else R.drawable.ic_ls_play,
                if (playing) "Pause" else "Play",
                action(ACTION_TOGGLE, 2),
            )
            .addAction(R.drawable.ic_pl_close, "Stop", action(ACTION_STOP, 3))
        session?.let { s -> b.setStyle(MediaStyleNotificationHelper.MediaStyle(s).setShowActionsInCompactView(0, 1)) }
        return b.build()
    }

    /** Lets go of the stream at once (many providers allow one connection at a time). */
    private fun release() {
        handler.removeCallbacksAndMessages(null)
        val p = player
        if (p != null) {
            val i = item
            lastUrl = i?.url
            lastPosition = if (i != null && !i.live && p.playbackState != Player.STATE_ENDED) p.currentPosition else C.TIME_UNSET
            p.removeListener(listener)
            p.release()
        }
        player = null
        session?.release()
        session = null
        (application as FalakApp).repo.listening = false
    }

    private fun stopNow() {
        release()
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The app swiped away from the recent apps: the sound stops with it.
        stopNow()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        release()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_BACK = "app.falak.player.LISTEN_BACK"
        private const val ACTION_TOGGLE = "app.falak.player.LISTEN_TOGGLE"
        private const val ACTION_STOP = "app.falak.player.LISTEN_STOP"
        private const val CHANNEL = "listen"
        private const val NOTIFICATION_ID = 7_301

        private var instance: ListenService? = null
        private var pending: Item? = null
        private var lastUrl: String? = null
        private var lastPosition: Long = C.TIME_UNSET

        /** Hands [item] over to the service (from the player, while it's still on screen). */
        fun start(context: Context, item: Item): Boolean {
            pending = item
            lastUrl = null
            return try {
                ContextCompat.startForegroundService(context, Intent(context, ListenService::class.java))
                true
            } catch (e: Exception) {
                // Android didn't allow it now.
                pending = null
                false
            }
        }

        /**
         * Back in the player: listening stops now. Returns where a film or episode got to (for
         * [url], the stream that was handed over), or null.
         */
        fun handBack(url: String): Long? {
            pending = null // not started yet: it stops as soon as it does
            instance?.stopNow()
            val pos = lastPosition.takeIf { lastUrl == url && it != C.TIME_UNSET && it > 0 }
            lastUrl = null
            lastPosition = C.TIME_UNSET
            return pos
        }
    }
}
