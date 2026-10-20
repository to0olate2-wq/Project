package com.novatv.player.ui

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.novatv.player.data.Channel
import com.novatv.player.data.Epg
import com.novatv.player.data.Kind
import com.novatv.player.data.Repository
import com.novatv.player.data.progressKey
import com.novatv.player.repo

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_INDEX = "index"
    }

    private lateinit var playerView: PlayerView
    private lateinit var bannerView: TextView
    private lateinit var messageView: TextView
    private lateinit var aspectButton: TextView
    private var player: ExoPlayer? = null

    private var queue: List<Channel> = emptyList()
    private var index = 0
    private var retried = false
    private var resumePositionMs = C.TIME_UNSET
    private var inPip = false
    private var currentUrl = ""
    private var aspect = 0

    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    )
    private val aspectNames = arrayOf("Fit", "Fill", "Zoom")

    private val handler = Handler(Looper.getMainLooper())
    private val hideBanner = Runnable { bannerView.visibility = View.GONE }
    private val timeFormat by lazy { android.text.format.DateFormat.getTimeFormat(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        queue = repo.playQueue
        if (queue.isEmpty()) {
            finish()
            return
        }
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, queue.lastIndex)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            keepScreenOn = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            controllerShowTimeoutMs = 3500
            setShowNextButton(true)
            setShowPreviousButton(true)
            setShowSubtitleButton(true)
        }
        aspect = repo.state.value.settings.aspect.coerceIn(0, 2)
        playerView.resizeMode = resizeModes[aspect]
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        bannerView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setLineSpacing(0f, 1.15f)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xCC000000.toInt())
            }
            setPadding(dp(18), dp(12), dp(18), dp(12))
            visibility = View.GONE
        }
        root.addView(
            bannerView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
                .apply { setMargins(dp(24), dp(24), dp(24), dp(24)) },
        )

        messageView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xCC000000.toInt())
            }
            setPadding(dp(20), dp(14), dp(20), dp(14))
            visibility = View.GONE
        }
        root.addView(
            messageView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )

        // Picture-size button, shown together with the player controls.
        aspectButton = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            text = "⛶  ${aspectNames[aspect]}"
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0x99000000.toInt())
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isFocusable = true
            isClickable = true
            setOnClickListener { cycleAspect() }
            visibility = View.GONE
        }
        root.addView(
            aspectButton,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
                .apply { setMargins(dp(20), dp(20), dp(20), dp(20)) },
        )
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                aspectButton.visibility = if (inPip) View.GONE else visibility
            },
        )

        setContentView(root)
        hideSystemBars()
        playerView.requestFocus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val q = repo.playQueue
        if (q.isEmpty()) return
        saveProgress()
        queue = q
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, queue.lastIndex)
        retried = false
        resumePositionMs = C.TIME_UNSET
        playCurrent(showInfo = true)
    }

    override fun onStart() {
        super.onStart()
        if (::playerView.isInitialized) initPlayer()
    }

    override fun onStop() {
        super.onStop()
        if (::playerView.isInitialized) releasePlayer()
        // Closing the picture-in-picture window stops the activity: finish so it doesn't linger.
        if (inPip) finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::playerView.isInitialized) hideSystemBars()
    }

    // ---- playback ----

    private fun initPlayer() {
        if (player != null) return
        val audio = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        val builder = ExoPlayer.Builder(this)
            .setAudioAttributes(audio, true)
            .setHandleAudioBecomingNoisy(true)
        if (repo.state.value.settings.bigBuffer) {
            builder.setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(30_000, 120_000, 4_000, 8_000)
                    .build(),
            )
        }
        val exo = builder.build()
        exo.addListener(listener)
        exo.playWhenReady = true
        player = exo
        playerView.player = ZapPlayer(exo)
        playCurrent(showInfo = true)
    }

    private fun releasePlayer() {
        val p = player ?: return
        saveProgress()
        val ch = queue.getOrNull(index)
        resumePositionMs = if (ch != null && ch.kind != Kind.LIVE) p.currentPosition else C.TIME_UNSET
        playerView.player = null
        p.release()
        player = null
    }

    private fun playCurrent(showInfo: Boolean, overrideUrl: String? = null) {
        val p = player ?: return
        val ch = queue.getOrNull(index) ?: return
        val settings = repo.state.value.settings
        val url = overrideUrl
            ?: if (settings.preferHls && ch.url.contains("/live/") && ch.url.endsWith(".ts")) {
                ch.url.removeSuffix(".ts") + ".m3u8"
            } else {
                ch.url
            }
        currentUrl = url
        val ua = ch.userAgent
            .ifBlank { repo.currentPlaylist?.userAgent.orEmpty() }
            .ifBlank { Repository.DEFAULT_UA }

        val dataSource = OkHttpDataSource.Factory(repo.http).setUserAgent(ua)
        if (ch.referrer.isNotBlank()) dataSource.setDefaultRequestProperties(mapOf("Referer" to ch.referrer))
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)

        val itemBuilder = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(ch.name).build())
        when {
            url.contains("m3u8", ignoreCase = true) -> itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            url.contains(".mpd", ignoreCase = true) -> itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        val source = DefaultMediaSourceFactory(dataSource, extractors).createMediaSource(itemBuilder.build())

        hideMessage()
        // Resume movies/episodes where they were left (this session first, then saved progress).
        val saved = repo.currentPlaylist?.let { pl -> repo.state.value.progress[progressKey(pl.id, ch.id)]?.pos }
        val resume = when {
            ch.kind == Kind.LIVE -> C.TIME_UNSET
            resumePositionMs > 0 -> resumePositionMs
            saved != null -> saved
            else -> C.TIME_UNSET
        }
        resumePositionMs = C.TIME_UNSET
        if (overrideUrl == null && resume > 0) p.setMediaSource(source, resume) else p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
        if (showInfo) showBanner(ch)
    }

    private fun zap(delta: Int) {
        if (queue.size < 2) return
        saveProgress()
        index = (index + delta).mod(queue.size)
        retried = false
        resumePositionMs = C.TIME_UNSET
        playCurrent(showInfo = true)
    }

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            val ch = queue.getOrNull(index) ?: return
            if (!retried) {
                retried = true
                // Xtream live TV comes as .ts or HLS; if one format fails, try the other once.
                val cur = currentUrl
                val alt = when {
                    !cur.contains("/live/") -> null
                    cur.endsWith(".ts") -> cur.removeSuffix(".ts") + ".m3u8"
                    cur.endsWith(".m3u8") -> cur.removeSuffix(".m3u8") + ".ts"
                    else -> null
                }
                if (alt != null) {
                    playCurrent(showInfo = false, overrideUrl = alt)
                } else {
                    handler.postDelayed({ player?.prepare() }, 1500)
                }
                return
            }
            val what = if (ch.kind == Kind.LIVE) "channel" else "video"
            showMessage("Can't play this $what right now\n${error.errorCodeName}")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                retried = false
                hideMessage()
            }
            if (playbackState == Player.STATE_ENDED) {
                saveProgress()
                val ch = queue.getOrNull(index)
                // Episodes: go straight to the next one.
                if (ch != null && ch.kind == Kind.SERIES && index < queue.lastIndex) zap(1)
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { setPictureInPictureParams(pipParams()) }
            }
        }
    }

    /** Lets the player's previous/next buttons switch channels in the current list. */
    private inner class ZapPlayer(p: Player) : ForwardingPlayer(p) {
        private val zapCommands = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )

        override fun getAvailableCommands(): Player.Commands {
            val base = super.getAvailableCommands()
            return if (queue.size < 2) base else base.buildUpon().addAll(*zapCommands).build()
        }

        override fun isCommandAvailable(command: Int): Boolean =
            (queue.size > 1 && command in zapCommands) || super.isCommandAvailable(command)

        override fun hasNextMediaItem(): Boolean = queue.size > 1
        override fun hasPreviousMediaItem(): Boolean = queue.size > 1
        override fun seekToNext() = zap(1)
        override fun seekToNextMediaItem() = zap(1)
        override fun seekToPrevious() = zap(-1)
        override fun seekToPreviousMediaItem() = zap(-1)
    }

    // ---- remote control keys ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::playerView.isInitialized) return super.dispatchKeyEvent(event)
        val live = queue.getOrNull(index)?.kind == Kind.LIVE
        val controlsShown = playerView.isControllerFullyVisible
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> if (live && !controlsShown) { zap(1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (live && !controlsShown) { zap(-1); return true }
                KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> {
                    queue.getOrNull(index)?.let { showBanner(it) }
                    return true
                }
                KeyEvent.KEYCODE_BACK -> if (controlsShown) { playerView.hideController(); return true }
                KeyEvent.KEYCODE_MENU -> { cycleAspect(); return true }
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    // ---- picture-in-picture ----

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            player?.isPlaying == true &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            runCatching { enterPictureInPictureMode(pipParams()) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipParams(): PictureInPictureParams {
        val vs = player?.videoSize
        var ratio = if (vs != null && vs.width > 0 && vs.height > 0) {
            vs.width * vs.pixelWidthHeightRatio / vs.height
        } else {
            16f / 9f
        }
        ratio = ratio.coerceIn(0.42f, 2.38f)
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            .build()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        playerView.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            bannerView.visibility = View.GONE
            aspectButton.visibility = View.GONE
            hideMessage()
        } else if (lifecycle.currentState == Lifecycle.State.CREATED) {
            finish()
        }
    }

    // ---- progress & picture size ----

    /** Saves how far a movie/episode got; finished or barely started items are cleared. */
    private fun saveProgress() {
        val p = player ?: return
        val ch = queue.getOrNull(index) ?: return
        if (ch.kind == Kind.LIVE) return
        val pl = repo.currentPlaylist ?: return
        val dur = p.duration
        if (dur <= 0) return
        val pos = p.currentPosition
        val finished = p.playbackState == Player.STATE_ENDED || pos > dur - 120_000
        repo.saveProgress(progressKey(pl.id, ch.id), if (finished || pos < 30_000) 0 else pos, dur)
    }

    private fun cycleAspect() {
        aspect = (aspect + 1) % resizeModes.size
        playerView.resizeMode = resizeModes[aspect]
        aspectButton.text = "⛶  ${aspectNames[aspect]}"
        Toast.makeText(this, "Picture: ${aspectNames[aspect]}", Toast.LENGTH_SHORT).show()
        repo.updateSettings { it.copy(aspect = aspect) }
    }

    // ---- overlays ----

    private fun showBanner(ch: Channel) {
        if (inPip) return
        val now = System.currentTimeMillis()
        val programs = repo.epg.value[ch.epgKey]
        val current = Epg.current(programs, now)
        val next = Epg.next(programs, now)

        val text = SpannableStringBuilder()
        val title = if (ch.kind == Kind.LIVE) "${index + 1}   ${ch.name}" else ch.name
        text.append(title, StyleSpan(Typeface.BOLD), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (current != null) {
            text.append("\nNow   ${time(current.start)}–${time(current.stop)}   ${current.title}")
        }
        if (next != null) {
            text.append("\nNext  ${time(next.start)}   ${next.title}")
        }
        if (current == null && next == null) text.append("\n${ch.group}")

        bannerView.text = text
        bannerView.visibility = View.VISIBLE
        handler.removeCallbacks(hideBanner)
        handler.postDelayed(hideBanner, 4500)
    }

    private fun showMessage(text: String) {
        messageView.text = text
        messageView.visibility = View.VISIBLE
    }

    private fun hideMessage() {
        if (::messageView.isInitialized) messageView.visibility = View.GONE
    }

    private fun time(ms: Long): String = timeFormat.format(java.util.Date(ms))

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
