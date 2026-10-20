package app.falak.player.ui

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.text.CueGroup
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTrackNameProvider
import androidx.media3.ui.PlayerView
import app.falak.player.R
import app.falak.player.player.CaptionSniffer
import app.falak.player.player.Captions
import app.falak.player.player.Engine
import app.falak.player.player.EngineAdvisor
import app.falak.player.player.SniffingExtractorsFactory
import app.falak.player.player.SniffingHlsExtractorFactory
import app.falak.player.player.VlcPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import app.falak.player.data.Channel
import app.falak.player.data.Epg
import app.falak.player.data.Kind
import app.falak.player.data.OpenSubtitles
import app.falak.player.data.SubtitleResult
import app.falak.player.data.Repository
import app.falak.player.data.progressKey
import app.falak.player.repo

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_INDEX = "index"
        private const val SEEK_STEP_MS = 10_000L

        /** Automatic mode: items ExoPlayer couldn't open this session; they go straight to VLC. */
        private val needsVlc = HashSet<String>()

        /** Player picked in the player for this session (overrides Settings until the app restarts). */
        private var sessionEngine: Engine? = null
    }

    private lateinit var playerView: PlayerView
    private lateinit var bannerView: TextView
    private lateinit var messageView: TextView
    private lateinit var seekView: TextView
    private lateinit var aspectButton: TextView
    private lateinit var audioButton: TextView
    private lateinit var ccButton: TextView
    private lateinit var toolbar: FrameLayout
    private lateinit var rotateButton: TextView
    private lateinit var browseButton: TextView
    private lateinit var engineButton: TextView
    private lateinit var browserView: ComposeView
    private lateinit var root: FrameLayout

    // In-player channel & group browser
    private var browserOpen by mutableStateOf(false)
    private var browserGroups by mutableStateOf<Map<String, List<Channel>>>(emptyMap())
    private var currentChannelId by mutableStateOf<String?>(null)
    /** The key that opened/closed the browser: its release must not reach the newly shown views. */
    private var swallowUpKey = KeyEvent.KEYCODE_UNKNOWN
    private val hideBrowserView = Runnable { if (!browserOpen) browserView.visibility = View.GONE }
    private val trackNames by lazy { DefaultTrackNameProvider(resources) }
    private var autoSubtitlesDone = false
    private var bannerFor: Channel? = null
    private var currentItem: MediaItem? = null
    private var currentDataSource: DataSource.Factory? = null
    private var currentExtractors: ExtractorsFactory? = null

    // Two engines behind the same Player interface: ExoPlayer, or VLC through VlcPlayer.
    private var exo: ExoPlayer? = null
    private var vlc: VlcPlayer? = null
    private val player: Player? get() = exo ?: vlc
    private var engine = Engine.EXO
    private var vlcLayout: VLCVideoLayout? = null
    /** Watches the captions of the current item (see Captions.kt). */
    private var sniffer = CaptionSniffer { onCaptionsChanged() }
    /** Teletext asked for from the CC menu: pick a VLC subtitle track once VLC lists them. */
    private var wantVlcSubtitles = false

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
        tvLayout = when (repo.state.value.settings.layout) {
            "tv" -> true
            "phone" -> false
            else -> detectTv(this)
        }

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            // VLC draws underneath this view, so nothing here may paint the background.
            setShutterBackgroundColor(Color.TRANSPARENT)
            setBackgroundColor(Color.TRANSPARENT)
            keepScreenOn = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            controllerShowTimeoutMs = 3500
            setShowNextButton(true)
            setShowPreviousButton(true)
            // Our CC button lists only captions that really carry text; the built-in one would list them all.
            setShowSubtitleButton(false)
        }
        aspect = repo.state.value.settings.aspect.coerceIn(0, 2)
        playerView.resizeMode = resizeModes[aspect]
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        bannerView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setLineSpacing(0f, 1.2f)
            // Falak glass: night→violet gradient with a thin light edge
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xE6111010.toInt())
                setStroke(dp(1), 0x26FFFFFF)
            }
            typeface = ResourcesCompat.getFont(this@PlayerActivity, R.font.inter_medium)
            setPadding(dp(20), dp(14), dp(22), dp(14))
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

        // Channels / Audio / Subtitles / Picture size / Player: one tidy row on the bottom line of the
        // screen, under the seek bar, shown and hidden together with the player controls.
        browseButton = pill("Channels", R.drawable.ic_pl_channels) { openBrowser() }
        audioButton = pill("Audio", R.drawable.ic_pl_audio) { showTrackDialog(C.TRACK_TYPE_AUDIO) }.apply { visibility = View.GONE }
        ccButton = pill("Subtitles", R.drawable.ic_pl_subtitles) { showTrackDialog(C.TRACK_TYPE_TEXT) }.apply { visibility = View.GONE }
        aspectButton = pill(aspectNames[aspect], R.drawable.ic_pl_fit) { cycleAspect() }
        rotateButton = pill(rotationLabel(), R.drawable.ic_pl_rotate) { cycleRotation() }
            .apply { visibility = if (tvLayout) View.GONE else View.VISIBLE }
        engineButton = pill("Player", R.drawable.ic_pl_player) { showEngineDialog() }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Side padding leaves room for the focused button to grow without being cut off.
            setPadding(dp(16), 0, dp(16), 0)
            clipToPadding = false
            clipChildren = false
            for (b in listOf(browseButton, audioButton, ccButton, aspectButton, rotateButton, engineButton)) {
                addView(
                    b,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(if (tvLayout) 44 else 40))
                        .apply { setMargins(dp(6), 0, dp(6), 0) },
                )
            }
        }
        // Centred when it fits, scrollable sideways on narrow phones.
        val scroller = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(buttons, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        toolbar = FrameLayout(this).apply {
            addView(
                scroller,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL),
            )
        }
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_controller)?.let { styleControls(it) }
        attachToolbar()
        applyRotation()
        // Seek bar: 10 seconds per press of the remote (Media3's default is 1/20 of the film).
        playerView.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
            setKeyTimeIncrement(SEEK_STEP_MS)
            setScrubberColor(Color.WHITE)
            // A soft band shows when the remote is on the seek bar (the dot also grows).
            background = android.graphics.drawable.StateListDrawable().apply {
                val band = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(0x2EFFFFFF)
                }
                addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.InsetDrawable(band, dp(8), dp(6), dp(8), dp(6)))
                addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            }
        }
        updateTimeViews()

        // "+30s · 12:30 / 1:45:00" while seeking with the remote or by double-tapping.
        seekView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            typeface = ResourcesCompat.getFont(this@PlayerActivity, R.font.inter_semibold)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xD9111010.toInt())
            }
            setPadding(dp(26), dp(14), dp(26), dp(14))
            visibility = View.GONE
        }
        root.addView(
            seekView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )

        // Touch: double-tap the left or right side to jump 10 s (films and episodes); single tap shows the controls.
        val gestures = android.view.GestureDetector(
            this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: android.view.MotionEvent): Boolean {
                    swipeStart = -1L
                    return true
                }

                // Sliding a finger sideways across the picture scrubs precisely: one screen width ≈ 3 minutes.
                override fun onScroll(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                    val start = e1 ?: return false
                    val dx = e2.x - start.x
                    if (!canSeek() || kotlin.math.abs(dx) < kotlin.math.abs(e2.y - start.y) || kotlin.math.abs(dx) < dp(24)) return false
                    val p = player ?: return false
                    if (swipeStart < 0) swipeStart = pendingSeek ?: p.currentPosition
                    val offset = (dx / playerView.width.coerceAtLeast(1) * 180_000f).toLong()
                    showSeekTarget(swipeStart, swipeStart + offset, commitDelayMs = -1)
                    return true
                }

                override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                    if (canSeek()) return false // wait: it may be the first tap of a double tap
                    toggleControls()
                    return true
                }

                override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                    if (canSeek()) toggleControls()
                    return true
                }

                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    if (!canSeek()) return false
                    val w = playerView.width.coerceAtLeast(1)
                    when {
                        e.x < w / 3f -> seekBy(-SEEK_STEP_MS)
                        e.x > w * 2 / 3f -> seekBy(SEEK_STEP_MS)
                        else -> toggleControls()
                    }
                    return true
                }
            },
        )
        playerView.setOnTouchListener { _, e ->
            gestures.onTouchEvent(e)
            if ((e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) && swipeStart >= 0) {
                swipeStart = -1L
                handler.removeCallbacks(commitSeek)
                handler.post(commitSeek)
            }
            true
        }

        browserView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            visibility = View.GONE
            setContent {
                val app by repo.state.collectAsState()
                val epg by repo.epg.collectAsState()
                FalakTheme(app.settings.theme, app.settings.layout) {
                    ChannelBrowser(
                        open = browserOpen,
                        groups = browserGroups,
                        currentId = currentChannelId,
                        epg = epg,
                        onPick = { list, ch -> pickFromBrowser(list, ch) },
                        onClose = { closeBrowser() },
                    )
                }
            }
        }
        root.addView(browserView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

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
        playCurrent(showInfo = true)
    }

    /** Settings' choice, the in-player choice, or (Automatic) what suits this TV and this channel. */
    private fun desiredEngine(ch: Channel): Engine {
        sessionEngine?.let { if (it != Engine.EXTERNAL) return it }
        val setting = repo.state.value.settings.player
        val auto = Engine.of(setting) == null
        val base = EngineAdvisor.resolve(this, setting).let { if (it == Engine.EXTERNAL) EngineAdvisor.advise(this).engine else it }
        return if (auto && ch.id in needsVlc) Engine.VLC else base
    }

    private val isAutomatic: Boolean
        get() = sessionEngine == null && Engine.of(repo.state.value.settings.player) == null

    private fun startEngine(e: Engine) {
        releaseEngine()
        engine = e
        if (e == Engine.VLC) createVlc() else createExo(software = e == Engine.EXO_SOFT)
        engineButton.text = e.short
    }

    private fun createExo(software: Boolean) {
        val s = repo.state.value.settings
        val audio = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        // FFmpeg takes over audio the device can't decode (AC-3, E-AC-3, DTS, MP2…),
        // or all audio when "Software audio" is on.
        val level = s.levelVolume
        val renderers = object : DefaultRenderersFactory(this) {
            // Volume levelling runs inside the audio output, for every decoder (hardware or FFmpeg).
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink? {
                if (!level) return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false) // the leveller works on 16-bit sound
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf<androidx.media3.common.audio.AudioProcessor>(app.falak.player.player.VolumeLeveler()))
                    .build()
            }
        }
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(
                if (s.softwareAudio || software) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            )
        if (software) {
            // Software decoding: slower, but avoids broken hardware decoders (green or frozen picture).
            renderers.setMediaCodecSelector(
                MediaCodecSelector { mime, secure, tunneling ->
                    MediaCodecUtil.getDecoderInfos(mime, secure, tunneling).sortedBy { if (it.softwareOnly) 0 else 1 }
                },
            )
        }
        val builder = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(audio, true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
        if (s.bigBuffer) {
            builder.setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(30_000, 120_000, 4_000, 8_000)
                    .build(),
            )
        }
        val p = builder.build()
        // Language preferences from Settings.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().apply {
            if (s.audioLang.isNotEmpty()) setPreferredAudioLanguage(s.audioLang)
            if (s.subtitlesOn) {
                setPreferredTextLanguage(s.subtitleLang.ifEmpty { null })
                setSelectUndeterminedTextLanguage(true)
            }
        }.build()
        p.addListener(listener)
        p.playWhenReady = true
        playerView.videoSurfaceView?.visibility = View.VISIBLE
        playerView.player = ZapPlayer(p)
        exo = p
    }

    private fun createVlc() {
        val caching = if (repo.state.value.settings.bigBuffer) 5_000 else 1_500
        val v = VlcPlayer(this, caching, repo.state.value.settings.levelVolume)
        val layout = VLCVideoLayout(this)
        // Underneath the controls; ExoPlayer's own picture surface steps aside.
        root.addView(layout, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        playerView.videoSurfaceView?.visibility = View.GONE
        v.attach(layout)
        v.setScale(aspect)
        v.addListener(listener)
        v.onTracksChanged = {
            updateTrackButtons()
            pickVlcSubtitle()
            if (bannerView.visibility == View.VISIBLE) bannerFor?.let { renderBanner(it) }
        }
        playerView.player = ZapPlayer(v)
        vlcLayout = layout
        vlc = v
    }

    /** Releases whichever engine is running (keeps the activity's views). */
    private fun releaseEngine() {
        playerView.player = null
        exo?.let {
            it.removeListener(listener)
            it.release()
        }
        exo = null
        vlc?.let {
            it.removeListener(listener)
            it.detach()
            it.release()
        }
        vlc = null
        vlcLayout?.let { root.removeView(it) }
        vlcLayout = null
        playerView.videoSurfaceView?.visibility = View.VISIBLE
    }

    private fun releasePlayer() {
        val p = player ?: return
        saveProgress()
        val ch = queue.getOrNull(index)
        resumePositionMs = if (ch != null && ch.kind != Kind.LIVE) p.currentPosition else C.TIME_UNSET
        releaseEngine()
    }

    /** Switch player for this session, keeping the position of films and episodes. */
    private fun switchEngine(e: Engine) {
        val ch = queue.getOrNull(index) ?: return
        if (e == Engine.EXTERNAL) {
            openExternally()
            return
        }
        player?.let { p -> if (ch.kind != Kind.LIVE) resumePositionMs = p.currentPosition }
        saveProgress()
        sessionEngine = e
        retried = false
        startEngine(e)
        playCurrent(showInfo = false)
        toast("Playing with ${e.short}")
    }

    private fun showEngineDialog() {
        val choices = listOf(Engine.EXO, Engine.EXO_SOFT, Engine.VLC, Engine.EXTERNAL)
        val advice = EngineAdvisor.advise(this)
        val labels = choices.map { e ->
            e.label + if (e == advice.engine) "  (best for this TV)" else ""
        }.toTypedArray()
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Play with")
            .setSingleChoiceItems(labels, choices.indexOf(engine)) { d, which ->
                d.dismiss()
                val e = choices[which]
                if (e != engine || e == Engine.EXTERNAL) switchEngine(e)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun playCurrent(showInfo: Boolean, overrideUrl: String? = null) {
        val ch = queue.getOrNull(index) ?: return
        val wanted = desiredEngine(ch)
        if (player == null || wanted != engine) startEngine(wanted)
        currentChannelId = ch.id
        // Remembered so the list can put the selection back on this item after the player closes.
        if (!ch.id.startsWith("cu:")) repo.lastPlayedId = ch.id
        browseButton.text = when (ch.kind) {
            Kind.LIVE -> "Channels"
            Kind.MOVIE -> "Films"
            Kind.SERIES -> "Episodes"
        }
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

        // A fresh caption watcher for every item.
        sniffer = CaptionSniffer { onCaptionsChanged() }
        autoSubtitlesDone = false
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

        val v = vlc
        if (v != null) {
            currentItem = null
            currentDataSource = null
            v.load(url, ch.name, ua, ch.referrer, if (overrideUrl == null && resume > 0) resume else 0L, hardware = true)
            updateTrackButtons()
            if (showInfo) showBanner(ch)
            return
        }
        val p = exo ?: return
        val httpSource = OkHttpDataSource.Factory(repo.http).setUserAgent(ua)
        if (ch.referrer.isNotBlank()) httpSource.setDefaultRequestProperties(mapOf("Referer" to ch.referrer))
        // DefaultDataSource adds local files (downloaded subtitles) on top of the network source.
        val dataSource = DefaultDataSource.Factory(this, httpSource)
        // Media3 only exposes captions embedded in the video when the stream declares them, and
        // IPTV restreams almost never do: offer CC1–CC3 and CEA-708 and show the ones that carry text.
        val extractors = SniffingExtractorsFactory(sniffer)
        currentDataSource = dataSource
        currentExtractors = extractors

        val itemBuilder = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(ch.name).build())
        when {
            url.contains("m3u8", ignoreCase = true) -> itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            url.contains(".mpd", ignoreCase = true) -> itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        val item = itemBuilder.build()
        currentItem = item
        val source = if (url.contains("m3u8", ignoreCase = true)) {
            // Expose closed captions (CEA-608) carried inside HLS video even when the playlist doesn't declare them.
            HlsMediaSource.Factory(dataSource)
                .setExtractorFactory(SniffingHlsExtractorFactory(sniffer))
                .createMediaSource(item)
        } else {
            DefaultMediaSourceFactory(dataSource, extractors).createMediaSource(item)
        }
        if (overrideUrl == null && resume > 0) p.setMediaSource(source, resume) else p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
        updateTrackButtons()
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
            if (exo != null && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
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
            // Automatic mode: what ExoPlayer can't open (unusual codecs, odd streams) often plays in VLC.
            if (engine != Engine.VLC && isAutomatic) {
                needsVlc += ch.id
                retried = false
                showMessage("Trying VLC…")
                handler.post { playCurrent(showInfo = false) } // not from inside ExoPlayer's own callback
                return
            }
            val what = if (ch.kind == Kind.LIVE) "channel" else "video"
            val hint = if (engine != Engine.VLC) "\nTry the ${engine.short} button below → VLC" else ""
            showMessage("Can't play this $what right now\n${error.errorCodeName}$hint")
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

        override fun onCues(cueGroup: CueGroup) {
            if (cueGroup.cues.isNotEmpty()) {
                captionsSeen = true
                handler.removeCallbacks(captionWatch)
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateTrackButtons()
            maybeAutoSubtitles()
            if (bannerView.visibility == View.VISIBLE) bannerFor?.let { renderBanner(it) }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
                )
            ) {
                updateTimeViews()
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

    // ---- channel & group browser ----

    /** Groups for the browser: favourites first, then the playlist's visible groups of the same kind. */
    private fun browserGroupsFor(ch: Channel): Map<String, List<Channel>> {
        val idx = repo.currentIndex
        val out = LinkedHashMap<String, List<Channel>>()
        if (ch.kind == Kind.SERIES || ch.id.startsWith("cu:") || idx.all.isEmpty()) {
            out[if (ch.kind == Kind.SERIES) "Episodes" else "Now playing"] = queue
            return out
        }
        val excluded = repo.excludedGroups
        val pl = repo.currentPlaylist
        if (pl != null) {
            val favs = repo.state.value.favorites[pl.id].orEmpty()
                .mapNotNull { idx.byId[it] }
                .filter { it.kind == ch.kind && it.group !in excluded }
            if (favs.isNotEmpty()) out["★ Favourites"] = favs
            for (l in repo.state.value.favLists[pl.id].orEmpty()) {
                val items = l.ids.mapNotNull { idx.byId[it] }.filter { it.kind == ch.kind && it.group !in excluded }
                if (items.isNotEmpty()) out["★ ${l.name}"] = items
            }
        }
        for ((g, list) in idx.groupsOf(ch.kind)) if (g !in excluded) out[g] = list
        // Played from a list that isn't a group (search, recents…): keep that list too.
        if (out.values.none { l -> l.any { it.id == ch.id } }) {
            val withQueue = LinkedHashMap<String, List<Channel>>()
            withQueue["Now playing"] = queue
            withQueue.putAll(out)
            return withQueue
        }
        return out
    }

    private fun openBrowser() {
        if (inPip) return
        val ch = queue.getOrNull(index) ?: return
        val groups = browserGroupsFor(ch)
        if (groups.isEmpty()) return
        browserGroups = groups
        handler.removeCallbacks(hideBrowserView)
        playerView.hideController()
        bannerView.visibility = View.GONE
        browserView.visibility = View.VISIBLE
        browserOpen = true
        browserView.requestFocus()
    }

    private fun closeBrowser() {
        if (!browserOpen) return
        browserOpen = false
        handler.postDelayed(hideBrowserView, 320)
        playerView.requestFocus()
    }

    private fun pickFromBrowser(list: List<Channel>, ch: Channel) {
        closeBrowser()
        if (ch.id == queue.getOrNull(index)?.id) return
        val at = list.indexOfFirst { it.id == ch.id }
        if (at < 0) return
        saveProgress()
        // Zapping now follows the group the channel was picked from.
        queue = list
        repo.playQueue = list
        index = at
        retried = false
        resumePositionMs = C.TIME_UNSET
        if (ch.kind != Kind.SERIES) repo.currentPlaylist?.let { repo.addRecent(it.id, ch.id) }
        playCurrent(showInfo = true)
    }

    // ---- remote control keys ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::playerView.isInitialized) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == swallowUpKey) {
            swallowUpKey = KeyEvent.KEYCODE_UNKNOWN
            return true
        }
        if (browserOpen && event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE)
        ) {
            closeBrowser()
            swallowUpKey = event.keyCode
            return true
        }
        // While the browser is open, the remote moves through it instead of zapping.
        if (browserOpen) return super.dispatchKeyEvent(event)
        val live = queue.getOrNull(index)?.kind == Kind.LIVE
        val controlsShown = playerView.isControllerFullyVisible
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                    if (live && !controlsShown && event.repeatCount == 0) {
                        openBrowser()
                        swallowUpKey = event.keyCode
                        return true
                    }
                KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> if (live && !controlsShown) { zap(1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (live && !controlsShown) { zap(-1); return true }
                // Films and episodes: left/right jump 10 s per press, faster while held; the jump
                // happens once you stop pressing, so it is quick and precise.
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (!controlsShown && canSeek()) {
                    val dir = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                    val step = when {
                        event.repeatCount < 8 -> SEEK_STEP_MS
                        event.repeatCount < 24 -> 30_000L
                        else -> 60_000L
                    }
                    seekBy(dir * step)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (canSeek()) { seekBy(30_000L); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> if (canSeek()) { seekBy(-30_000L); return true }
                KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> {
                    queue.getOrNull(index)?.let { showBanner(it) }
                    return true
                }
                KeyEvent.KEYCODE_BACK -> if (controlsShown) { playerView.hideController(); return true }
                KeyEvent.KEYCODE_MENU -> { cycleAspect(); return true }
                KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> { showTrackDialog(C.TRACK_TYPE_AUDIO); return true }
                KeyEvent.KEYCODE_CAPTIONS -> { showTrackDialog(C.TRACK_TYPE_TEXT); return true }
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        // The key-up of a seek press must not pop up the controls.
        if (event.action == KeyEvent.ACTION_UP && pendingSeek != null &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            return true
        }
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    // ---- precise seeking ----

    /** Where the pending jump will land; several presses add up before seeking once. */
    private var pendingSeek: Long? = null
    private val commitSeek = Runnable {
        val target = pendingSeek
        pendingSeek = null
        if (target != null) player?.seekTo(target)
        handler.postDelayed(hideSeek, 900)
    }
    private val hideSeek = Runnable { if (pendingSeek == null) seekView.visibility = View.GONE }
    private var seekFrom = 0L

    /** Films, episodes and replays that know their length can be sought; live TV can't. */
    private fun canSeek(): Boolean {
        val p = player ?: return false
        return p.duration > 0 && p.isCurrentMediaItemSeekable && !inPip
    }

    /** Finger position when a sideways swipe started (−1 = not swiping). */
    private var swipeStart = -1L

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val from = if (pendingSeek == null) p.currentPosition else seekFrom
        showSeekTarget(from, (pendingSeek ?: p.currentPosition) + deltaMs, commitDelayMs = 650)
    }

    /** Shows where a jump will land; it is made [commitDelayMs] after the last change (−1: on release). */
    private fun showSeekTarget(from: Long, wanted: Long, commitDelayMs: Long) {
        val p = player ?: return
        val duration = p.duration.takeIf { it > 0 } ?: return
        seekFrom = from
        val target = wanted.coerceIn(0L, (duration - 1_000).coerceAtLeast(0L))
        pendingSeek = target
        val diff = (target - from) / 1000
        val sign = if (diff >= 0) "+" else "−"
        val arrow = if (diff >= 0) "⏩" else "⏪"
        seekView.text = "$arrow  $sign${clock(kotlin.math.abs(diff) * 1000, short = true)}   ·   ${clock(target)} / ${clock(duration)}"
        seekView.visibility = View.VISIBLE
        handler.removeCallbacks(hideSeek)
        handler.removeCallbacks(commitSeek)
        if (commitDelayMs >= 0) handler.postDelayed(commitSeek, commitDelayMs)
    }

    private fun toggleControls() {
        if (inPip) return
        if (playerView.isControllerFullyVisible) playerView.hideController() else playerView.showController()
    }

    /** 1:05:09, 4:07, or with [short] "45s" / "2m 30s". */
    private fun clock(ms: Long, short: Boolean = false): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        if (short) {
            return when {
                total < 60 -> "${sec}s"
                h == 0L -> if (sec == 0L) "${m}m" else "${m}m ${sec}s"
                else -> "${h}h ${m}m"
            }
        }
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(java.util.Locale.US, "%d:%02d", m, sec)
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
        updateTimeViews()
        if (isInPictureInPictureMode) {
            if (browserOpen) {
                browserOpen = false
                browserView.visibility = View.GONE
            }
            bannerView.visibility = View.GONE
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
        if (ch.kind == Kind.LIVE || ch.id.startsWith("cu:")) return
        val pl = repo.currentPlaylist ?: return
        val dur = p.duration
        if (dur <= 0) return
        val pos = p.currentPosition
        val finished = p.playbackState == Player.STATE_ENDED || pos > dur - 120_000
        // Episodes are saved with the show's name and artwork so "Continue watching" looks right.
        val show = repo.currentSeries
        val meta = if (show != null && ch.kind == Kind.SERIES) {
            val episode = ch.info.split(" · ").take(2).joinToString(" ").ifBlank { ch.name }
            ch.copy(name = "${show.name} · $episode", logo = show.logo.ifBlank { ch.logo })
        } else {
            ch
        }
        repo.saveProgress(
            progressKey(pl.id, ch.id),
            if (finished || pos < 30_000) 0 else pos,
            dur,
            meta,
            show?.seriesId.orEmpty(),
        )
    }

    // ---- rotation (phone layout) ----

    private var tvLayout = false

    private fun rotationLabel(): String = when (repo.state.value.settings.playerRotation) {
        "landscape" -> "Landscape"
        "portrait" -> "Portrait"
        else -> "Auto-rotate"
    }

    /** Auto follows the phone as you turn it (even with rotation locked in the system). */
    private fun applyRotation() {
        if (tvLayout) return
        requestedOrientation = when (repo.state.value.settings.playerRotation) {
            "landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }

    private fun cycleRotation() {
        val next = when (repo.state.value.settings.playerRotation) {
            "auto" -> "landscape"
            "landscape" -> "portrait"
            else -> "auto"
        }
        repo.updateSettings { it.copy(playerRotation = next) }
        rotateButton.text = rotationLabel()
        applyRotation()
    }

    private fun cycleAspect() {
        aspect = (aspect + 1) % resizeModes.size
        playerView.resizeMode = resizeModes[aspect]
        vlc?.setScale(aspect)
        aspectButton.text = aspectNames[aspect]
        Toast.makeText(this, "Picture: ${aspectNames[aspect]}", Toast.LENGTH_SHORT).show()
        repo.updateSettings { it.copy(aspect = aspect) }
    }

    // ---- overlays ----

    // ---- audio & subtitle tracks ----

    private class TrackOption(val label: String, val selected: Boolean, val select: () -> Unit)

    /** A caption track we added that turned out to carry text, or any track the stream declared. */
    private fun isReal(f: androidx.media3.common.Format): Boolean =
        !Captions.isProbe(f) || (sniffer.found and Captions.bitOf(f)) != 0

    private fun trackOptions(type: Int): List<TrackOption> {
        vlc?.let { return vlcTrackOptions(it, type) }
        val p = exo ?: return emptyList()
        val out = ArrayList<TrackOption>()
        if (type == C.TRACK_TYPE_TEXT) {
            val anyOn = p.currentTracks.groups.any { it.type == type && it.isSelected }
            out += TrackOption("Off", !anyOn) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(type)
                    .setTrackTypeDisabled(type, true)
                    .build()
            }
        }
        val seen = HashMap<String, Int>()
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                if (type == C.TRACK_TYPE_TEXT && !isReal(g.getTrackFormat(i))) continue
                val base = trackLabel(g.getTrackFormat(i))
                val n = (seen[base] ?: 0) + 1
                seen[base] = n
                val label = if (n > 1) "$base ($n)" else base
                out += TrackOption(label, g.isTrackSelected(i)) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                        .build()
                    if (type == C.TRACK_TYPE_TEXT) watchCaptions()
                }
            }
        }
        // Teletext subtitles: Media3 can't draw them, VLC can.
        if (type == C.TRACK_TYPE_TEXT) {
            for (lang in sniffer.teletext) {
                out += TrackOption("Teletext subtitles · ${languageName(lang) ?: lang.uppercase()}  (plays with VLC)", false) {
                    wantVlcSubtitles = true
                    switchEngine(Engine.VLC)
                }
            }
        }
        return out
    }

    private fun vlcTrackOptions(v: VlcPlayer, type: Int): List<TrackOption> {
        val out = ArrayList<TrackOption>()
        if (type == C.TRACK_TYPE_TEXT) {
            out += TrackOption("Off", v.selectedSubtitle < 0) { v.selectSubtitle(-1) }
            for (t in v.subtitleTracks()) out += TrackOption(t.name.ifBlank { "Subtitles ${t.id}" }, t.id == v.selectedSubtitle) { v.selectSubtitle(t.id) }
        } else if (type == C.TRACK_TYPE_AUDIO) {
            for (t in v.audioTracks()) out += TrackOption(t.name.ifBlank { "Audio ${t.id}" }, t.id == v.selectedAudio) { v.selectAudio(t.id) }
        }
        return out
    }

    /** VLC lists subtitles a moment after starting: turn them on when asked to (teletext) or by Settings. */
    private fun pickVlcSubtitle() {
        val v = vlc ?: return
        val subtitlesOn = repo.state.value.settings.subtitlesOn
        if (!wantVlcSubtitles && (!subtitlesOn || autoSubtitlesDone)) return
        val tracks = v.subtitleTracks()
        if (tracks.isEmpty()) return
        if (v.selectedSubtitle >= 0) {
            wantVlcSubtitles = false
            autoSubtitlesDone = true
            return
        }
        val lang = repo.state.value.settings.subtitleLang
        val langName = languageName(lang)
        val pick = tracks.firstOrNull { t -> langName != null && t.name.contains(langName, ignoreCase = true) }
            ?: tracks.firstOrNull { it.name.contains("teletext", ignoreCase = true) && wantVlcSubtitles }
            ?: tracks.first()
        v.selectSubtitle(pick.id)
        wantVlcSubtitles = false
        autoSubtitlesDone = true
    }

    private fun languageName(code: String): String? =
        code.takeIf { it.isNotBlank() && it != "und" }?.let { java.util.Locale(it).displayLanguage.replaceFirstChar(Char::uppercase) }

    /** Names that say what a track really is (CC vs DVB subtitles), with the language. */
    private fun trackLabel(f: androidx.media3.common.Format): String {
        val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { java.util.Locale(it).displayLanguage.replaceFirstChar(Char::uppercase) }
        return when (f.sampleMimeType) {
            MimeTypes.APPLICATION_CEA608 -> buildString {
                append("Closed captions")
                if (f.accessibilityChannel != androidx.media3.common.Format.NO_VALUE) append(" (CC").append(f.accessibilityChannel).append(")")
                if (lang != null) append(" · ").append(lang)
            }
            MimeTypes.APPLICATION_CEA708 -> "Closed captions (digital)" + (lang?.let { " · $it" } ?: "")
            MimeTypes.APPLICATION_DVBSUBS -> "Subtitles" + (lang?.let { " · $it" } ?: "") + " (DVB)"
            else -> trackNames.getTrackName(f)
        }
    }

    private var captionsSeen = false
    private val captionWatch = Runnable {
        val textOn = exo?.currentTracks?.groups?.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected } == true
        if (!captionsSeen && textOn) {
            toast("This channel isn't sending captions right now. Try another track, or switch the player to VLC.")
        }
    }

    /** Many streams advertise captions but send none; tell the user instead of showing nothing. */
    private fun watchCaptions() {
        captionsSeen = false
        handler.removeCallbacks(captionWatch)
        handler.postDelayed(captionWatch, 25_000)
    }

    /** Hands the stream to VLC or another player (for teletext subtitles, rare codecs, …). */
    private fun openExternally() {
        val ch = queue.getOrNull(index) ?: return
        val url = currentUrl.ifBlank { ch.url }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(url), "video/*")
            .putExtra("title", ch.name)
        try {
            startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: Exception) {
            toast("No other video player is installed. VLC for Android is free.")
        }
    }

    private fun countTracks(type: Int): Int {
        vlc?.let { v ->
            return if (type == C.TRACK_TYPE_AUDIO) v.audioTracks().size else v.subtitleTracks().size
        }
        val groups = exo?.currentTracks?.groups ?: return 0
        val real = groups.filter { it.type == type }.sumOf { g ->
            (0 until g.length).count { i ->
                g.isTrackSupported(i) && (type != C.TRACK_TYPE_TEXT || isReal(g.getTrackFormat(i)))
            }
        }
        return real + if (type == C.TRACK_TYPE_TEXT) sniffer.teletext.size else 0
    }

    /** The caption watcher found text (or teletext): refresh the CC button, maybe turn captions on. */
    private fun onCaptionsChanged() {
        if (exo == null) return
        updateTrackButtons()
        maybeAutoSubtitles()
        if (bannerView.visibility == View.VISIBLE) bannerFor?.let { renderBanner(it) }
    }

    private fun showTrackDialog(type: Int) {
        val options = ArrayList(trackOptions(type))
        val real = if (type == C.TRACK_TYPE_TEXT) options.size - 1 else options.size
        // Movies and episodes can also fetch subtitles online.
        val vod = queue.getOrNull(index)?.kind?.let { it != Kind.LIVE } == true
        if (type == C.TRACK_TYPE_TEXT && vod) {
            options += TrackOption("🔎  Find subtitles online…", false) { searchOnlineSubtitles() }
        }
        if ((real <= 0 && !(type == C.TRACK_TYPE_TEXT && vod)) || (type == C.TRACK_TYPE_AUDIO && real == 1)) {
            val what = if (type == C.TRACK_TYPE_AUDIO) "Only one audio track" else "No subtitles"
            Toast.makeText(this, "$what in this stream", Toast.LENGTH_SHORT).show()
            return
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (type == C.TRACK_TYPE_AUDIO) "Audio" else "Subtitles")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), options.indexOfFirst { it.selected }) { d, which ->
                d.dismiss()
                options[which].select()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun updateTrackButtons() {
        val audio = countTracks(C.TRACK_TYPE_AUDIO)
        val text = countTracks(C.TRACK_TYPE_TEXT)
        val vod = queue.getOrNull(index)?.kind?.let { it != Kind.LIVE } == true
        audioButton.visibility = if (audio > 1) View.VISIBLE else View.GONE
        audioButton.text = "Audio ($audio)"
        ccButton.visibility = if (text > 0 || vod) View.VISIBLE else View.GONE
    }

    // ---- online subtitles (OpenSubtitles.com) ----

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun searchOnlineSubtitles() {
        val s = repo.state.value.settings
        val ch = queue.getOrNull(index) ?: return
        if (s.osApiKey.isBlank()) {
            toast("Add your free OpenSubtitles API key in Settings → Online subtitles")
            return
        }
        val show = repo.currentSeries
        val (title, year) = OpenSubtitles.cleanTitle(if (show != null && ch.kind == Kind.SERIES) show.name else ch.name)
        val se = Regex("S(\\d+) · E(\\d+)").find(ch.info)
        val season = se?.groupValues?.get(1)?.toIntOrNull()
        val episode = se?.groupValues?.get(2)?.toIntOrNull()
        val langs = listOf(s.subtitleLang.ifEmpty { "en" }, "en").distinct()
        val client = OpenSubtitles(repo.http, s.osApiKey, s.osUser, s.osPass)
        toast("Searching subtitles for “$title”…")
        lifecycleScope.launch {
            val results = try {
                withContext(Dispatchers.IO) { client.search(title, year, season, episode, langs) }
            } catch (e: Exception) {
                toast(e.message ?: "Subtitle search failed")
                return@launch
            }
            if (results.isEmpty()) {
                toast("No subtitles found for “$title”")
                return@launch
            }
            val shown = results.take(40)
            android.app.AlertDialog.Builder(this@PlayerActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Subtitles · $title")
                .setItems(shown.map { "${it.language.uppercase()}  ·  ${it.release}  (${it.downloads}↓)" }.toTypedArray()) { _, which ->
                    downloadSubtitle(client, shown[which])
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun downloadSubtitle(client: OpenSubtitles, result: SubtitleResult) {
        lifecycleScope.launch {
            val file = try {
                withContext(Dispatchers.IO) { client.download(result, java.io.File(cacheDir, "subs")) }
            } catch (e: Exception) {
                toast(e.message ?: "Subtitle download failed")
                return@launch
            }
            attachSubtitle(file, result.language)
        }
    }

    /** Re-prepares the current video with the downloaded subtitle, keeping the position. */
    private fun attachSubtitle(file: java.io.File, lang: String) {
        vlc?.let {
            it.addSubtitleFile(file)
            toast("Subtitles added")
            return
        }
        val p = exo ?: return
        val item = currentItem ?: return
        val dataSource = currentDataSource ?: return
        val sub = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage(lang)
            .setLabel("Online (${lang.uppercase()})")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val withSub = item.buildUpon().setSubtitleConfigurations(listOf(sub)).build()
        val source = DefaultMediaSourceFactory(dataSource, currentExtractors ?: return).createMediaSource(withSub)
        val pos = p.currentPosition
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguage(lang)
            .build()
        p.setMediaSource(source, pos)
        p.prepare()
        toast("Subtitles added")
    }

    /** With "Show subtitles automatically" on, turn on the first matching subtitle track. */
    private fun maybeAutoSubtitles() {
        val p = exo ?: return
        if (autoSubtitlesDone || !repo.state.value.settings.subtitlesOn) return
        val groups = p.currentTracks.groups.filter { g ->
            g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { isReal(g.getTrackFormat(it)) }
        }
        if (groups.isEmpty()) return
        autoSubtitlesDone = true
        if (groups.any { it.isSelected }) return
        val lang = repo.state.value.settings.subtitleLang
        val pick = groups.firstOrNull { g -> lang.isNotEmpty() && (0 until g.length).any { g.getTrackFormat(it).language?.startsWith(lang) == true } }
            ?: groups.first()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(pick.mediaTrackGroup, 0))
            .build()
        watchCaptions()
    }

    /** e.g. "Audio: Arabic, English · Subtitles: 2" */
    private fun trackSummary(): String {
        val audios = vlc?.audioTracks()?.map { it.name }?.distinct()
            ?: exo?.currentTracks?.groups?.filter { it.type == C.TRACK_TYPE_AUDIO }
                ?.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { trackNames.getTrackName(g.getTrackFormat(it)) } }
                ?.distinct()
            ?: return ""
        val subs = countTracks(C.TRACK_TYPE_TEXT)
        val parts = ArrayList<String>()
        if (audios.size > 1) parts += "Audio: " + audios.take(4).joinToString(", ")
        if (subs > 0) parts += "Subtitles: $subs"
        return parts.joinToString("  ·  ")
    }

    // ---- player controls: layout & the remote's highlight ----

    /** Dark text and icons on the white highlight. */
    private val focusInk = 0xFF121110.toInt()

    /** White when the remote is on it, soft white while pressed, [normal] otherwise. */
    private fun focusBackground(oval: Boolean, normal: Int = Color.TRANSPARENT, edge: Int = 0): android.graphics.drawable.StateListDrawable {
        fun shape(color: Int, stroke: Int = 0) = GradientDrawable().apply {
            if (oval) shape = GradientDrawable.OVAL else cornerRadius = dp(100).toFloat()
            setColor(color)
            if (stroke != 0) setStroke(dp(1), stroke)
        }
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(Color.WHITE))
            addState(intArrayOf(android.R.attr.state_pressed), shape(0x4DFFFFFF))
            addState(intArrayOf(), shape(normal, edge))
        }
    }

    /** Text and icon colours: dark on the white highlight, white otherwise. */
    private fun contentColors(): android.content.res.ColorStateList = android.content.res.ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
        intArrayOf(focusInk, Color.WHITE),
    )

    /** A button of the bottom row: icon and label; turns white and grows a little under the remote's focus. */
    private fun pill(label: String, icon: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tvLayout) 15f else 14f)
        typeface = ResourcesCompat.getFont(this@PlayerActivity, R.font.inter_medium)
        setTextColor(contentColors())
        setSingleLine()
        includeFontPadding = false
        gravity = Gravity.CENTER
        val size = dp(if (tvLayout) 20 else 18)
        ResourcesCompat.getDrawable(resources, icon, theme)?.mutate()?.let {
            it.setBounds(0, 0, size, size)
            setCompoundDrawablesRelative(it, null, null, null)
        }
        compoundDrawablePadding = dp(8)
        compoundDrawableTintList = contentColors()
        background = focusBackground(oval = false, normal = 0x33FFFFFF, edge = 0x26FFFFFF)
        setPadding(dp(if (tvLayout) 18 else 14), 0, dp(if (tvLayout) 20 else 16), 0)
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, focused ->
            val s = if (focused) 1.08f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(120).start()
        }
    }

    /**
     * Puts the button row on the bottom line of the player controls; the seek bar, the time and
     * the play buttons move up to make room. Inside the controls, the row appears, hides and stays
     * on screen while the remote is used exactly like the rest of them.
     */
    private fun attachToolbar() {
        val rowHeight = dp(if (tvLayout) 68 else 60)
        val controls = playerView.findViewById<FrameLayout>(androidx.media3.ui.R.id.exo_controller)
        if (controls != null) {
            // No slide-away animation: the row and the bars above it disappear together.
            playerView.setControllerAnimationEnabled(false)
            controls.clipToPadding = false
            controls.setPadding(0, 0, 0, rowHeight)
            controls.addView(
                toolbar,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight, Gravity.BOTTOM)
                    .apply { bottomMargin = -rowHeight },
            )
        } else {
            // Fallback: a separate row at the bottom of the screen that follows the controls.
            toolbar.visibility = View.GONE
            root.addView(toolbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight, Gravity.BOTTOM))
            playerView.setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { visibility ->
                    toolbar.visibility = if (inPip) View.GONE else visibility
                },
            )
        }
    }

    /** Media3's own buttons (play, previous/next, ±10 s, settings): the same white highlight. */
    private fun styleControls(v: View) {
        when (v) {
            is android.widget.ImageButton -> {
                v.background = focusBackground(oval = true)
                v.imageTintList = contentColors()
            }
            // The "10" rewind/forward buttons: the icon is the button's background, in a frame that
            // takes on the button's focus.
            is android.widget.Button -> {
                v.backgroundTintList = contentColors()
                v.setTextColor(contentColors())
                (v.parent as? View)?.background = focusBackground(oval = true)
            }
            is ViewGroup -> for (i in 0 until v.childCount) styleControls(v.getChildAt(i))
        }
    }

    /** Live channels have no real position or length: hide "00:13 · 00:00" and the empty seek bar. */
    private fun updateTimeViews() {
        if (!::playerView.isInitialized) return
        val state = if (canSeek()) View.VISIBLE else View.INVISIBLE
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_time)?.visibility = state
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress)?.visibility = state
    }

    private fun showBanner(ch: Channel) {
        if (inPip) return
        renderBanner(ch)
        bannerView.visibility = View.VISIBLE
        handler.removeCallbacks(hideBanner)
        handler.postDelayed(hideBanner, 4500)
    }

    private fun renderBanner(ch: Channel) {
        bannerFor = ch
        val now = System.currentTimeMillis()
        val programs = repo.epg.value[ch.epgKey]
        val current = Epg.current(programs, now)
        val next = Epg.next(programs, now)

        val text = SpannableStringBuilder()
        if (ch.kind == Kind.LIVE) {
            text.append("${index + 1}   ", ForegroundColorSpan(0xFFFF4F2E.toInt()), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text.append(ch.name, StyleSpan(Typeface.BOLD), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (current != null) {
            text.append("\nNow   ${time(current.start)}–${time(current.stop)}   ${current.title}")
        }
        if (next != null) {
            text.append("\nNext  ${time(next.start)}   ${next.title}")
        }
        if (current == null && next == null) text.append("\n${ch.group}")
        val tracks = trackSummary()
        if (tracks.isNotEmpty()) text.append("\n$tracks")

        bannerView.text = text
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
