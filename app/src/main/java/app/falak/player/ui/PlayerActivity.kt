package app.falak.player.ui

import app.falak.player.FreezeWatch
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import app.falak.player.PrayerAlerts
import app.falak.player.ProgramAlerts
import app.falak.player.data.ProgramAlert
import app.falak.player.player.TrackMatch
import app.falak.player.player.TrackMemory
import app.falak.player.player.LiveCaptions
import app.falak.player.player.SleepTimer
import app.falak.player.player.SpeechModel
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
import app.falak.player.player.FrameRateMatch
import app.falak.player.player.SubtitleStyle
import app.falak.player.player.CaptionLayout
import app.falak.player.player.CaptionText
import app.falak.player.player.Word
import app.falak.player.data.Settings
import app.falak.player.R
import app.falak.player.player.CaptionSniffer
import app.falak.player.player.Captions
import app.falak.player.player.Engine
import app.falak.player.player.EngineAdvisor
import app.falak.player.player.SniffingExtractorsFactory
import app.falak.player.player.SniffingHlsExtractorFactory
import app.falak.player.player.VlcPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import app.falak.player.data.mine
import app.falak.player.data.Habits
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
        private const val LEVEL_BRIGHTNESS = 1
        private const val LEVEL_VOLUME = 2
        private const val ASPECT_FIT = 0
        private const val ASPECT_ZOOM = 2
        /** The Up next card comes up this long before an episode ends. */
        private const val UP_NEXT_MS = 20_000L
        /** …and the next episode starts after this many seconds. */
        private const val COUNTDOWN_S = 10
        /** A forward jump that starts this early in an episode can be the intro being skipped. */
        private const val INTRO_WINDOW_MS = 6 * 60_000L
        /** "S01E02", "S1 E2", "Season 1", "Episode 2", "1x02" and everything after it. */
        private val EPISODE_MARK = Regex("(?i)\\s*[-–|:·]?\\s*(\\bS\\d{1,2}\\s*[ .x-]?\\s*E\\d{1,3}\\b|\\bseason\\s*\\d+|\\bepisode\\s*\\d+|\\b\\d{1,2}x\\d{2,3}\\b).*$")

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
    private lateinit var speedButton: TextView
    private lateinit var sleepButton: TextView
    private lateinit var favButton: TextView
    private lateinit var menuButton: TextView
    private lateinit var browserView: ComposeView
    private lateinit var root: FrameLayout

    /** The Menu (Home, Films, Series, Settings…) is over the picture. */
    private var menuOpen by mutableStateOf(false)

    /** Digits typed on the remote ("12" → channel 12), shown top-right until they are used. */
    private val typed = StringBuilder()
    private lateinit var numberView: TextView
    private val commitTyped = Runnable { useTypedNumber() }

    /** The channel watched before this one (list and position), for the remote's "last channel". */
    private var previous: Pair<List<Channel>, Int>? = null

    /** A channel that wouldn't play, and the copies of it already tried (see [tryBackup]). */
    private var backupOf: String? = null
    private val backupTried = HashSet<String>()

    // In-player channel & group browser
    private var browserOpen by mutableStateOf(false)
    /** The list of groups is showing beside the channels. */
    private var browserShowGroups by mutableStateOf(false)
    private var browserGroups by mutableStateOf<Map<String, List<Channel>>>(emptyMap())
    private var currentChannelId by mutableStateOf<String?>(null)
    /** The key that opened/closed the browser: its release must not reach the newly shown views. */
    private var swallowUpKey = KeyEvent.KEYCODE_UNKNOWN
    private val hideBrowserView = Runnable { if (!browserOpen && !menuOpen && !styleOpen) browserView.visibility = View.GONE }
    private val trackNames by lazy { DefaultTrackNameProvider(resources) }
    private var autoSubtitlesDone = false
    private var bannerFor: Channel? = null
    private var currentItem: MediaItem? = null
    private var currentDataSource: DataSource.Factory? = null
    private var currentExtractors: ExtractorsFactory? = null

    // Two engines behind the same Player interface: ExoPlayer, or VLC through VlcPlayer.
    private var exo: ExoPlayer? = null
    private var vlc: VlcPlayer? = null

    /** Auto frame rate: TVs only (Settings → Picture & playback → Match frame rate). */
    private val frameMatch by lazy { FrameRateMatch(this) }
    private val isTvDevice by lazy { detectTv(this) }
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

    /** Phones: a finger sliding up or down sets [LEVEL_BRIGHTNESS] or [LEVEL_VOLUME] (0: neither). */
    private var levelSwipe = 0
    private var levelValue = 0f
    private var pinching = false
    private var pinchScale = 1f
    private lateinit var levelView: LinearLayout
    private lateinit var levelLabel: TextView
    private lateinit var levelBar: android.widget.ProgressBar
    private val hideLevel = Runnable { levelView.visibility = View.GONE }

    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    )
    private val aspectNames = arrayOf("Fit", "Fill", "Zoom")

    private val handler = Handler(Looper.getMainLooper())
    private val hideBanner = Runnable { bannerView.visibility = View.GONE }
    private val timeFormat by lazy { android.text.format.DateFormat.getTimeFormat(this) }

    // ---- two channels at once ----

    /** The second picture while two-screen mode is on (null: one picture, as usual). */
    private var dual: DualPane? = null

    /** Two-screen mode: the second (right, or lower) picture is the one heard and steered. */
    private var dualSecond = false

    /** The channel list is open to pick the second channel. */
    private var pickingSecond = false

    /** Where each picture is in two-screen mode (left, top, right, bottom), for taps. */
    private var rectMain = IntArray(4)
    private var rectSecond = IntArray(4)
    private lateinit var twoButton: TextView
    private lateinit var dualFrame: View
    private lateinit var dualLabelA: TextView
    private lateinit var dualLabelB: TextView
    private lateinit var dualHint: TextView
    private lateinit var dualBar: LinearLayout
    private lateinit var touchLayer: View
    private val hideDualHint = Runnable { if (::dualHint.isInitialized) dualHint.visibility = View.GONE }

    /** The channel the list opens on when it isn't the main picture's (two-screen mode). */
    private var browserFocusId by mutableStateOf<String?>(null)

    // ---- start over ----

    /** A show already under way on a channel with replay: offered from its start for a few seconds. */
    private var startOverOffer by mutableStateOf<Pair<Channel, app.falak.player.data.Program>?>(null)

    /** The live list and place to go back to from a replay started with "From the start". */
    private var liveBeforeStartOver: Pair<List<Channel>, Int>? = null
    private val hideStartOver = Runnable { startOverOffer = null }
    private lateinit var liveButton: TextView

    // ---- Listen mode (phones): the sound carries on in the background ----

    /** The sound is with ListenService while this player is off screen. */
    private var listening = false

    /** Handing the stream to another app: not a reason to listen on. */
    private var handingOff = false

    // ---- Auto captions: English speech made into captions on this device ----

    /** The speech engine (made when a channel with auto captions plays; let go when the player stops). */
    private var liveCaptions: LiveCaptions? = null

    /** The ExoPlayer was built hearing the sound for auto captions. */
    private var exoCaptions = false

    /** ... and with the head start that shows each word as it's said ("In time with the speech"). */
    private var exoSynced = false

    /** Auto captions are running for what's playing. */
    private var autoCapsOn = false

    /** A channel waiting for the speech model to arrive before its auto captions start. */
    private var autoCapsPending: String? = null
    private lateinit var autoCapsView: androidx.media3.ui.SubtitleView

    /**
     * What auto captions say now, all of it ([renderAutoCaption] shows the words heard by now and
     * fits them to the lines chosen); null: nothing.
     */
    private var autoCapsWords: List<Word>? = null
    private val clearAutoCaps = Runnable {
        autoCapsWords = null
        renderAutoCaption()
    }

    /** The next word is due: show it. */
    private val nextAutoCaption = Runnable { renderAutoCaption() }

    /** How late words come out of the speech engine, smoothed (ms; null: not measured yet). */
    private var autoCapsLateMs: Double? = null

    /** Measures auto captions' lines as they'll be drawn (the size and typeface of the captions). */
    private val capsPaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    /** How wide a line of auto captions may be, in pixels (0: not known yet). */
    private var capsLineWidth = 0f
    private val refitCaptions = Runnable { fitCaptions() }

    /** The stream's own captions have words on screen. */
    private var streamCuesShowing = false

    /** The caption style panel is over the picture. */
    private var styleOpen by mutableStateOf(false)

    /** Auto captions' place and lines are in the panel. */
    private var styleAuto by mutableStateOf(false)

    /** A line under the panel's title (null: none). */
    private var styleNote by mutableStateOf<String?>(null)

    // ---- audio and subtitle choices remembered per channel, film or series ----
    private val trackMemory by lazy { TrackMemory(this) }
    private var audioMemoryDone = false
    private var textMemoryDone = false
    private var alertJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The mini player (and Listen mode) let go of their stream before this one connects.
        MiniPlayerState.close()
        app.falak.player.ListenService.handBack("")
        // A sleep timer that ran out while nothing was playing is over.
        if (SleepTimer.due(android.os.SystemClock.elapsedRealtime())) SleepTimer.cancel()
        queue = repo.playQueue
        if (queue.isEmpty()) {
            finish()
            return
        }
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, queue.lastIndex)
        // Kids screen time used up, or bedtime: nothing plays (the main screen says why).
        if (repo.kidsActive) {
            app.falak.player.data.KidsClock.state(this, repo.state.value.settings).block?.let {
                app.falak.player.data.KidsClock.stop(it)
                finish()
                return
            }
        }
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
        // Auto captions take the very space of the stream's own: the same box as the picture
        // (letterboxed or pillarboxed with it), just above the stream's captions, under the controls.
        autoCapsView = androidx.media3.ui.SubtitleView(this)
        val streamCaptions = playerView.subtitleView
        val pictureBox = streamCaptions?.parent as? ViewGroup
        if (streamCaptions != null && pictureBox != null) {
            pictureBox.addView(
                autoCapsView,
                pictureBox.indexOfChild(streamCaptions) + 1,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        } else {
            (playerView.overlayFrameLayout ?: root).addView(
                autoCapsView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
        // Whenever the picture's box moves or changes size (turning the phone, Fit/Fill/Zoom, the
        // channel list beside it), both kinds of captions are fitted to it again.
        (autoCapsView.parent as? View)?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            handler.removeCallbacks(refitCaptions)
            handler.post(refitCaptions)
        }
        applySubtitleStyle()
        lifecycleScope.launch { SpeechModel.state.collect { onSpeechModel(it) } }

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

        // Channel number being typed on the remote, top right like a TV.
        numberView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setLineSpacing(0f, 1.1f)
            gravity = Gravity.END
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xE6111010.toInt())
                setStroke(dp(1), 0x26FFFFFF)
            }
            typeface = ResourcesCompat.getFont(this@PlayerActivity, R.font.inter_medium)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            visibility = View.GONE
        }
        root.addView(
            numberView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
                .apply { setMargins(dp(24), dp(24), dp(24), dp(24)) },
        )

        // Channels / Audio / Subtitles / Picture size / Player: one tidy row on the bottom line of the
        // screen, under the seek bar, shown and hidden together with the player controls.
        // Leave the player for another part of the app (Films, Series, Settings…).
        menuButton = pill("Menu", R.drawable.ic_pl_menu) { showAppMenu() }
        browseButton = pill("Channels", R.drawable.ic_pl_channels) { openBrowser() }
        // Live TV: a second channel beside this one.
        twoButton = pill("Two screens", R.drawable.ic_pl_two) { startPickingSecond() }.apply { visibility = View.GONE }
        // Watching a show from its start: back to the live channel.
        liveButton = pill("Back to live", R.drawable.ic_nav_live) { backToLive() }.apply { visibility = View.GONE }
        // Add what's playing to Favorites without leaving the player.
        favButton = pill("Favorite", R.drawable.ic_pl_star_outline) { queue.getOrNull(index)?.let { toggleFavorite(it) } }
            .apply { visibility = View.GONE }
        audioButton = pill("Audio", R.drawable.ic_pl_audio) { showTrackDialog(C.TRACK_TYPE_AUDIO) }.apply { visibility = View.GONE }
        ccButton = pill("Subtitles", R.drawable.ic_pl_subtitles) { showTrackDialog(C.TRACK_TYPE_TEXT) }.apply { visibility = View.GONE }
        aspectButton = pill(aspectNames[aspect], R.drawable.ic_pl_fit) { cycleAspect() }
        rotateButton = pill(rotationLabel(), R.drawable.ic_pl_rotate) { cycleRotation() }
            .apply { visibility = if (tvLayout) View.GONE else View.VISIBLE }
        engineButton = pill("Player", R.drawable.ic_pl_player) { showEngineDialog() }
        // Films and episodes only (live TV always plays at normal speed).
        speedButton = pill(speedLabel(speed), R.drawable.ic_pl_speed) { showSpeedDialog() }.apply { visibility = View.GONE }
        // Stop after a while, or at the end of what's on (the sound fades out over the last minute).
        sleepButton = pill(SleepTimer.buttonLabel(android.os.SystemClock.elapsedRealtime()), R.drawable.ic_pl_sleep) { showSleepDialog() }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Side padding leaves room for the focused button to grow without being cut off.
            setPadding(dp(16), 0, dp(16), 0)
            clipToPadding = false
            clipChildren = false
            for (b in listOf(menuButton, liveButton, browseButton, twoButton, favButton, audioButton, ccButton, speedButton, sleepButton, aspectButton, rotateButton, engineButton)) {
                addView(
                    b,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(if (tvLayout) 44 else 40))
                        .apply { setMargins(dp(6), 0, dp(6), 0) },
                )
            }
        }
        // A button shown or hidden (subtitles found, a film's speed): do the labels still fit?
        buttons.addOnLayoutChangeListener { v, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) v.post { fitPills() } }
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
            // Turned or resized: labels back if they fit now.
            addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) post { fitPills() } }
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
        // Phones: the brightness or volume while a finger slides up or down, and the picture's shape after a pinch.
        levelLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER
            typeface = ResourcesCompat.getFont(this@PlayerActivity, R.font.inter_semibold)
        }
        levelBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFFF4F2E.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x40FFFFFF)
        }
        levelView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xD9111010.toInt())
            }
            setPadding(dp(24), dp(12), dp(24), dp(14))
            visibility = View.GONE
            addView(levelLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(levelBar, LinearLayout.LayoutParams(dp(170), dp(6)).apply { topMargin = dp(10) })
        }
        root.addView(
            levelView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        createDualViews()

        // Touch: double-tap the left or right side to jump 10 s (films and episodes); single tap shows the controls.
        val gestures = android.view.GestureDetector(
            this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: android.view.MotionEvent): Boolean {
                    swipeStart = -1L
                    levelSwipe = 0
                    return true
                }

                // Sliding a finger sideways across the picture scrubs precisely: one screen width ≈ 3 minutes.
                // Phones: up or down on the left half sets the brightness, on the right half the volume.
                override fun onScroll(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                    val start = e1 ?: return false
                    if (pinching) return true
                    val dx = e2.x - start.x
                    val dy = e2.y - start.y
                    if (levelSwipe == 0 && swipeStart < 0 && canLevelSwipe(start) &&
                        kotlin.math.abs(dy) > kotlin.math.abs(dx) && kotlin.math.abs(dy) > dp(24)
                    ) {
                        beginLevel(if (start.x < playerView.width / 2f) LEVEL_BRIGHTNESS else LEVEL_VOLUME)
                    }
                    if (levelSwipe != 0) {
                        // Up raises it; most of the screen's height goes from nothing to full.
                        changeLevel(distanceY / (playerView.height.coerceAtLeast(1) * 0.8f))
                        return true
                    }
                    if (!canSeek() || kotlin.math.abs(dx) < kotlin.math.abs(dy) || kotlin.math.abs(dx) < dp(24)) return false
                    val p = player ?: return false
                    if (swipeStart < 0) swipeStart = pendingSeek ?: p.currentPosition
                    val offset = (dx / playerView.width.coerceAtLeast(1) * 180_000f).toLong()
                    showSeekTarget(swipeStart, swipeStart + offset, commitDelayMs = -1)
                    return true
                }

                override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                    if (pinching) return true
                    if (canSeek()) return false // wait: it may be the first tap of a double tap
                    toggleControls()
                    return true
                }

                override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                    if (pinching) return true
                    if (canSeek()) toggleControls()
                    return true
                }

                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    if (pinching || !canSeek()) return false
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
        // Phones: pinch out to fill the screen (the edges are cut), pinch in to see the whole picture.
        val pinch = android.view.ScaleGestureDetector(
            this,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: android.view.ScaleGestureDetector): Boolean {
                    if (isTvDevice || inPip || dual != null || browserOpen) return false
                    pinching = true
                    pinchScale = 1f
                    return true
                }

                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    pinchScale *= detector.scaleFactor
                    return true
                }

                override fun onScaleEnd(detector: android.view.ScaleGestureDetector) {
                    val want = when {
                        pinchScale > 1.12f -> ASPECT_ZOOM
                        pinchScale < 0.9f -> ASPECT_FIT
                        else -> -1
                    }
                    if (want >= 0) setAspect(want, byPinch = true)
                }
            },
        )
        playerView.setOnTouchListener { _, e ->
            // A new touch: whatever the last one was is over.
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) pinching = false
            pinch.onTouchEvent(e)
            gestures.onTouchEvent(e)
            if ((e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) && swipeStart >= 0) {
                swipeStart = -1L
                handler.removeCallbacks(commitSeek)
                handler.post(commitSeek)
            }
            true
        }

        // "Skip intro" and "Up next" in the corner: drawn over the picture, never taking the remote's
        // focus (the player handles OK and Back for them).
        extrasView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setContent {
                val app by repo.state.collectAsState()
                FalakTheme(dark = true, layout = app.settings.layout) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                        SkipIntroButton(skipIntroShown && upNext == null) { skipIntro() }
                        val offer = startOverOffer
                        StartOverButton(
                            visible = offer != null && dual == null,
                            title = offer?.second?.title.orEmpty(),
                            minutes = offer?.let { ((System.currentTimeMillis() - it.second.start) / 60_000L).toInt() } ?: 0,
                        ) { startOver() }
                        UpNextCard(upNext, upNextLabel, upNextSecs, onPlay = { playUpNext() }, onHide = { dismissUpNext() })
                        val prayer by PrayerAlerts.current.collectAsState()
                        PrayerBanner(prayer, paused = prayerPaused) { endPrayerPause(resume = false) }
                        // A programme reminder (not for the channel already on screen).
                        val alert by ProgramAlerts.current.collectAsState()
                        ProgramAlertCard(
                            alert?.takeIf { it.channelId != currentChannelId },
                            keysHint = true,
                            onWatch = { watchAlert(it) },
                            onDismiss = { ProgramAlerts.dismiss() },
                        )
                        // Paused for a while: the ambient screen, over everything.
                        if (ambientOn) AmbientScreen(ambientSlides, paused = queue.getOrNull(index)?.name) { hideAmbient() }
                    }
                }
            }
        }
        root.addView(extrasView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        browserView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            visibility = View.GONE
            setContent {
                val app by repo.state.collectAsState()
                val epg by repo.epg.collectAsState()
                val favorites = remember(app.favorites) {
                    repo.currentPlaylist?.let { app.favorites[app.mine(it.id)] }.orEmpty().toHashSet()
                }
                // Over the picture the channel list is always dark, whatever the time of day.
                FalakTheme(dark = true, layout = app.settings.layout) {
                    ChannelBrowser(
                        open = browserOpen,
                        showGroups = browserShowGroups,
                        groups = browserGroups,
                        currentId = browserFocusId ?: currentChannelId,
                        epg = epg,
                        favorites = favorites,
                        canFavorite = { canFavorite(it) },
                        onFavorite = { toggleFavorite(it) },
                        onShowGroups = { setBrowserGroups(it) },
                        onPick = { list, ch -> pickFromBrowser(list, ch) },
                        onClose = { closeBrowser() },
                    )
                    // Drawn above the channel list (only one of them is open at a time).
                    PlayerMenu(
                        open = menuOpen,
                        nowPlaying = queue.getOrNull(index)?.name,
                        places = menuPlaces(),
                        onPick = { leaveTo(it) },
                        onClose = { closeMenu() },
                    )
                    CaptionStylePanel(
                        open = styleOpen,
                        settings = app.settings,
                        autoCaptions = styleAuto,
                        note = styleNote,
                        onChange = { changeCaptionStyle(it) },
                        onClose = { closeCaptionStyle() },
                    )
                }
            }
        }
        root.addView(browserView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)
        // Back: from a show watched from its start, to live; otherwise out of the player (a live
        // channel carrying on in the mini player).
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (liveBeforeStartOver != null) {
                        backToLive()
                        return
                    }
                    offerMini()
                    finish()
                }
            },
        )
        // A phone turned while the browser is open: fit the picture into the new free space.
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, oldR, ob ->
            if (r - l != oldR - ol || b - t != ob - ot) handler.post { applyVideoArea() }
        }
        hideSystemBars()
        playerView.requestFocus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Back from Listen mode's notification: onStart takes the sound back, nothing new to play.
        if (intent.action == app.falak.player.ListenService.ACTION_BACK) return
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
        handingOff = false
        // Back from Listen mode: the picture again, from where the sound got to.
        if (listening) {
            listening = false
            val pos = app.falak.player.ListenService.handBack(currentUrl)
            if (pos != null && queue.getOrNull(index)?.kind != Kind.LIVE) resumePositionMs = pos
        }
        repo.playerOpen = true
        FreezeWatch.note("player opened")
        if (::playerView.isInitialized) initPlayer()
        handler.removeCallbacks(rateTick)
        handler.postDelayed(rateTick, 1_000)
        prayerJob?.cancel()
        prayerJob = lifecycleScope.launch { app.falak.player.PrayerAlerts.current.collect { onPrayer(it) } }
        // A reminder for the channel already playing needs no card.
        alertJob?.cancel()
        alertJob = lifecycleScope.launch {
            ProgramAlerts.current.collect { a -> if (a != null && a.channelId == currentChannelId) ProgramAlerts.dismiss() }
        }
    }

    override fun onPause() {
        super.onPause()
        // The screen turned off: Listen mode carries the sound on.
        if (!listening && !inPip && !screenOn()) startListening()
    }

    override fun onStop() {
        super.onStop()
        if (!listening && !inPip && !screenOn()) startListening()
        repo.nowPlaying = null
        prayerJob?.cancel()
        alertJob?.cancel()
        // Two-screen mode ends when the player leaves the screen.
        if (dual != null) endDual(keepSecond = false, replay = false)
        handler.removeCallbacks(rateTick)
        if (repo.kidsActive) app.falak.player.data.KidsClock.flush(this)
        // The service report's figures so far, to the file (off the main thread).
        flushWatched()
        val dir = filesDir
        Thread { app.falak.player.data.ServiceLogStore.save(dir) }.start()
        repo.playerOpen = false
        FreezeWatch.note("player closing")
        if (::playerView.isInitialized) releasePlayer()
        // The picture has stopped (it starts again on coming back): downloads needn't wait meanwhile.
        app.falak.player.OfflineCenter.streaming(this, false)
        // The speech engine's memory goes back too (it loads again when captions are next needed).
        liveCaptions?.release()
        liveCaptions = null
        // The screen goes back to its own refresh rate while the player isn't showing.
        if (isTvDevice) frameMatch.start(false)
        // Closing the picture-in-picture window stops the activity: finish so it doesn't linger.
        if (inPip) finish()
    }

    override fun onDestroy() {
        if (listening) {
            listening = false
            app.falak.player.ListenService.handBack(currentUrl)
        }
        // The player closed: the saved copies watched to the end go.
        deleteWatchedCopies()
        // Nothing streams from here any more: downloads go on (Listen mode counts on its own).
        app.falak.player.OfflineCenter.streaming(this, false)
        handler.removeCallbacksAndMessages(null)
        if (isTvDevice) frameMatch.release()
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

    private fun startEngine(e: Engine, captions: Boolean = false) {
        releaseEngine()
        engine = e
        if (e == Engine.VLC) createVlc() else createExo(software = e == Engine.EXO_SOFT, captions = captions)
        setPillLabel(engineButton, e.short)
        applyDualVolume()
    }

    private fun createExo(software: Boolean, captions: Boolean = false) {
        val s = repo.state.value.settings
        val audio = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        // FFmpeg takes over audio the device can't decode (AC-3, E-AC-3, DTS, MP2…),
        // or all audio when "Software audio" is on.
        val level = s.levelVolume
        // Night sound stays in the chain whenever it's not off, so it can come on at sunset mid-film.
        val night = if (s.nightSound != "off") app.falak.player.player.NightSound().also { it.enabled = nightNow() } else null
        nightSound = night
        // Auto captions hear the sound through a tap at the start of the chain.
        val tap = if (captions) captionsEngine().newTap() else null
        // "In time with the speech": the audio output holds 2 seconds, so the captions hear each
        // word that long before you do (the picture follows the sound, so lips stay in sync).
        val synced = captions && s.syncedCaptions
        val renderers = object : DefaultRenderersFactory(this) {
            // Volume levelling and night sound run inside the audio output, for every decoder (hardware or FFmpeg).
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink? {
                val processors = listOfNotNull<androidx.media3.common.audio.AudioProcessor>(
                    tap,
                    if (level) app.falak.player.player.VolumeLeveler() else null,
                    night,
                )
                if (processors.isEmpty()) return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                // With auto captions the sound is always decoded here, never passed on encoded to a
                // sound system (Dolby to a soundbar), so the captions can hear it.
                @Suppress("DEPRECATION")
                val sink = if (tap != null) {
                    androidx.media3.exoplayer.audio.DefaultAudioSink.Builder()
                        .setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                } else {
                    androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                }
                if (synced) {
                    sink.setAudioTrackBufferSizeProvider(
                        androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider.Builder()
                            .setMinPcmBufferDurationUs(LiveCaptions.HEAD_START_US)
                            .setMaxPcmBufferDurationUs(LiveCaptions.HEAD_START_US)
                            .build(),
                    )
                }
                val built = sink
                    .setEnableFloatOutput(false) // all of them work on 16-bit sound
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(processors.toTypedArray())
                    .build()
                // Auto captions also see when each piece of sound is heard.
                return if (tap != null) captionsEngine().watch(built) else built
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
        // Buffered video sits in the app's own memory. ExoPlayer's default limit (~140 MB) fills up
        // with 4K films and, next to a big playlist, ran phones out of memory: size it to this device.
        val bufferBytes = (Runtime.getRuntime().maxMemory() / 6).coerceIn(24L shl 20, 96L shl 20).toInt()
        builder.setLoadControl(
            DefaultLoadControl.Builder()
                .apply {
                    if (s.bigBuffer) setBufferDurationsMs(30_000, 120_000, 4_000, 8_000)
                }
                .setTargetBufferBytes(bufferBytes)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build(),
        )
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
        if (isTvDevice) p.setVideoFrameMetadataListener(frameMatch.frames)
        p.playWhenReady = true
        playerView.videoSurfaceView?.visibility = View.VISIBLE
        playerView.player = ZapPlayer(p)
        streamCuesShowing = false
        applySubtitleStyle()
        exo = p
        exoCaptions = captions
        exoSynced = synced
    }

    private fun createVlc() {
        val caching = if (repo.state.value.settings.bigBuffer) 5_000 else 1_500
        val st = repo.state.value.settings
        nightSound = null
        val v = VlcPlayer(
            this, caching, st.levelVolume,
            nightSound = nightNow(),
            audioLang = st.audioLang,
            subtitleLang = if (st.subtitlesOn) st.subtitleLang.ifBlank { "en" } else "",
            subtitleLook = SubtitleStyle.vlcOptions(st.subSize, st.subColor, st.subEdge),
        )
        val layout = VLCVideoLayout(this)
        // Underneath the controls; ExoPlayer's own picture surface steps aside.
        root.addView(layout, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        playerView.videoSurfaceView?.visibility = View.GONE
        v.attach(layout)
        v.setScale(if (browserOpen) 0 else aspect)
        v.addListener(listener)
        v.onTracksChanged = {
            if (isTvDevice) frameMatch.declared(v.frameRate())
            updateTrackButtons()
            applyRememberedVlc(v)
            pickVlcSubtitle()
            if (bannerView.visibility == View.VISIBLE) bannerFor?.let { renderBanner(it) }
        }
        playerView.player = ZapPlayer(v)
        vlcLayout = layout
        vlc = v
        applyVideoArea()
    }

    /** Releases whichever engine is running (keeps the activity's views). */
    private fun releaseEngine() {
        stopAutoCaptions()
        exoCaptions = false
        exoSynced = false
        playerView.player = null
        exo?.let {
            it.removeListener(listener)
            if (isTvDevice) it.clearVideoFrameMetadataListener(frameMatch.frames)
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
        startEngine(e, captions = e != Engine.VLC && autoCaptionsChosen(ch))
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
        // Downloaded ones watched to the end and left behind: deleted now (Delete when watched).
        deleteWatchedCopies(except = ch.id)
        serviceStarting(ch)
        val wanted = desiredEngine(ch)
        // Auto captions chosen for this channel: the player is built hearing its sound.
        val captions = wanted != Engine.VLC && dual == null && autoCaptionsChosen(ch)
        // "In time with the speech" changed since: the sound's head start is part of the player.
        val resync = captions && repo.state.value.settings.syncedCaptions != exoSynced
        if (player == null || wanted != engine || captions != exoCaptions || resync) startEngine(wanted, captions)
        // A new stream: its data rate is measured afresh.
        rateSamples.clear()
        rateBps = 0
        // A new episode: no card or button yet; a resumed episode gets the rest of its series.
        if (ch.id != currentChannelId) {
            upNextDismissed = null
            introSkippedFor = null
        }
        upNext = null
        upNextSecs = null
        skipIntroShown = false
        ensureSeriesQueue(ch)
        // For the phone remote's "now playing" line.
        repo.nowPlaying = ch.name to if (ch.kind == Kind.LIVE) {
            val now = System.currentTimeMillis()
            repo.epg.value[ch.epgKey]?.firstOrNull { now >= it.start && now < it.stop }?.title.orEmpty()
        } else {
            ch.info.ifBlank { ch.group }
        }
        currentChannelId = ch.id
        // Auto frame rate for this video (or the screen's own rate back).
        if (isTvDevice) {
            frameMatch.start(
                when (settingsFrameRate()) {
                    "all" -> true
                    "vod" -> ch.kind != Kind.LIVE
                    else -> false
                },
            )
        }
        FreezeWatch.note("play ${ch.kind.name.lowercase()} with ${wanted.name.lowercase()}")
        updateFavButton()
        // Remembered so the list can put the selection back on this item after the player closes.
        if (!ch.id.startsWith("cu:")) repo.lastPlayedId = ch.id
        // And the live channel, to start with it when the app is opened fresh next time.
        if (ch.kind == Kind.LIVE && !ch.id.startsWith("cu:")) repo.currentPlaylist?.let { repo.setLastLive(it.id, ch.id) }
        setPillLabel(browseButton, when (ch.kind) {
            Kind.LIVE -> "Channels"
            Kind.MOVIE -> "Films"
            Kind.SERIES -> "Episodes"
        })
        // A replay on its own (such as "From the start"): there's no list to browse.
        browseButton.visibility = if (ch.id.startsWith("cu:") && queue.size <= 1) View.GONE else View.VISIBLE
        val settings = repo.state.value.settings
        // A downloaded film or episode plays from its saved copy (no internet needed).
        val savedCopy = if (overrideUrl == null) app.falak.player.OfflineCenter.fileFor(ch.id) else null
        val url = overrideUrl
            ?: savedCopy?.let { android.net.Uri.fromFile(it).toString() }
            ?: if (settings.preferHls && ch.url.contains("/live/") && ch.url.endsWith(".ts")) {
                ch.url.removeSuffix(".ts") + ".m3u8"
            } else {
                ch.url
            }
        currentUrl = url
        // Streaming from the provider: downloads wait (many providers allow one connection at a time).
        app.falak.player.OfflineCenter.streaming(this, savedCopy == null)
        val ua = ch.userAgent
            .ifBlank { repo.currentPlaylist?.userAgent.orEmpty() }
            .ifBlank { Repository.DEFAULT_UA }

        // Playing again after the sleep timer stopped it; "at the end of this episode" was for another one.
        if (sleptByTimer) {
            sleptByTimer = false
            playerView.keepScreenOn = true
        }
        if (SleepTimer.mode == SleepTimer.Mode.END_OF_ITEM && !SleepTimer.stopsAfter(ch.id)) SleepTimer.cancel()
        updateSleepButton()
        // A fresh caption watcher for every item.
        sniffer = CaptionSniffer { onCaptionsChanged() }
        autoSubtitlesDone = false
        audioMemoryDone = false
        textMemoryDone = false
        wasReady = false
        stalls.clear()
        failedUrl = null
        twoButton.visibility = if (ch.kind == Kind.LIVE && !ch.id.startsWith("cu:") && dual == null) View.VISIBLE else View.GONE
        // A replay of our own "From the start" keeps the way back to live; anything else forgets it.
        if (!ch.id.startsWith("cu:")) liveBeforeStartOver = null
        liveButton.visibility = if (liveBeforeStartOver != null) View.VISIBLE else View.GONE
        offerStartOver(ch)
        if (captions) startAutoCaptions() else stopAutoCaptions()
        // Two minutes on a live channel count towards Home's "For this hour".
        handler.removeCallbacks(noteHabit)
        if (ch.kind == Kind.LIVE && !ch.id.startsWith("cu:")) handler.postDelayed(noteHabit, 120_000)
        if (dual != null) updateDualLabels()
        hideMessage()
        // Resume movies/episodes where they were left (this session first, then saved progress).
        val saved = repo.currentPlaylist?.let { pl -> repo.state.value.progress[progressKey(repo.state.value.mine(pl.id), ch.id)]?.pos }
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
            applySpeed()
            if (showInfo) showBanner(ch)
            return
        }
        val p = exo ?: return
        // Each item starts from Settings' choices; ones picked by hand come back from TrackMemory.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        val httpSource = OkHttpDataSource.Factory(repo.http).setUserAgent(ua).setTransferListener(transferMeter)
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
        applySpeed()
        if (showInfo) showBanner(ch)
    }

    private fun zap(delta: Int) {
        if (queue.size < 2) return
        saveProgress()
        rememberPrevious()
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
            // Same channel listed elsewhere in the playlist (another group or quality)?
            if (tryBackup(ch)) return
            serviceFailed(ch)
            val what = if (ch.kind == Kind.LIVE) "channel" else "video"
            val hint = if (engine != Engine.VLC) "\nTry the ${engine.short} button below → VLC" else ""
            showMessage("Can't play this $what right now\n${error.errorCodeName}$hint")
            explainRefusal()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                retried = false
                hideMessage()
                wasReady = true
                failedUrl = null
                // Carried on after the sleep timer stopped it (the play button).
                if (sleptByTimer) {
                    sleptByTimer = false
                    playerView.keepScreenOn = true
                }
                serviceReady()
            }
            // Stopping to load again and again: a lighter version, if Tidy channel list merged one.
            if (playbackState == Player.STATE_BUFFERING && wasReady) {
                serviceStall()
                onStall()
            }
            if (playbackState == Player.STATE_ENDED) {
                saveProgress()
                // Sleep timer "at the end of this": nothing more plays.
                if (SleepTimer.stopsAfter(queue.getOrNull(index)?.id)) {
                    handler.post { sleepStop() }
                    return
                }
                // Kids' time ran out while this was on: it has finished, so that's all.
                kidsFinishing?.let { b ->
                    handler.post { kidsStop(b) }
                    return
                }
                // The show watched from its start has caught up with live: back to the channel.
                if (liveBeforeStartOver != null) {
                    handler.post { backToLive() }
                    return
                }
                val ch = queue.getOrNull(index)
                // Episodes: on to the next one (unless "Keep watching" was chosen).
                if (ch != null && nextEpisode() != null && upNextDismissed != ch.id) {
                    if (repo.state.value.settings.autoNextEpisode) playUpNext() else seriesTick()
                }
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                lastSeekAt = android.os.SystemClock.elapsedRealtime()
                learnIntro(oldPosition.positionMs, newPosition.positionMs)
            }
        }

        override fun onCues(cueGroup: CueGroup) {
            if (cueGroup.cues.isNotEmpty()) {
                captionsSeen = true
                handler.removeCallbacks(captionWatch)
            }
            // The caption style panel's sample steps aside while the stream's own words show.
            val showing = cueGroup.cues.isNotEmpty()
            if (showing != streamCuesShowing) {
                streamCuesShowing = showing
                if (styleOpen) renderAutoCaption()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateTrackButtons()
            applyRememberedTracks()
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
            // The picture is known: its frame rate too, when the stream declares one.
            if (isTvDevice) {
                exo?.videoFormat?.frameRate?.let { frameMatch.declared(it) }
                vlc?.let { frameMatch.declared(it.frameRate()) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { setPictureInPictureParams(pipParams()) }
            }
            // The quality is known a moment after the picture starts: add it to the banner on screen.
            if (::bannerView.isInitialized && bannerView.visibility == View.VISIBLE) bannerFor?.let { renderBanner(it) }
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

        // Live channels: no seek bar and no ±10 s, so nothing moves the show back or forward.
        private val seekCommands = intArrayOf(
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        )
        private fun liveNow() = queue.getOrNull(index)?.kind == Kind.LIVE

        override fun getAvailableCommands(): Player.Commands {
            var b = super.getAvailableCommands().buildUpon()
            if (queue.size > 1) b = b.addAll(*zapCommands)
            if (liveNow()) b = b.removeAll(*seekCommands)
            return b.build()
        }

        override fun isCommandAvailable(command: Int): Boolean {
            if (liveNow() && command in seekCommands) return false
            return (queue.size > 1 && command in zapCommands) || super.isCommandAvailable(command)
        }

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
            val favs = repo.state.value.favorites[repo.state.value.mine(pl.id)].orEmpty()
                .mapNotNull { idx.byId[it] }
                .filter { it.kind == ch.kind && it.group !in excluded }
            if (favs.isNotEmpty()) out["★ Favorites"] = favs
            for (l in repo.state.value.favLists[repo.state.value.mine(pl.id)].orEmpty()) {
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

    private fun openBrowser(forChannel: Channel? = null) {
        if (inPip) return
        val ch = forChannel ?: queue.getOrNull(index) ?: return
        browserFocusId = forChannel?.id
        val groups = browserGroupsFor(ch)
        if (groups.isEmpty()) return
        browserGroups = groups
        FreezeWatch.note("channel list opened (${groups.size} groups, ${groups.values.sumOf { it.size }} items)")
        handler.removeCallbacks(hideBrowserView)
        // The controls stay away while browsing (they would also pull the remote's focus to themselves).
        playerView.hideController()
        playerView.useController = false
        bannerView.visibility = View.GONE
        browserView.visibility = View.VISIBLE
        browserShowGroups = false
        browserOpen = true
        applyVideoArea()
        browserView.requestFocus()
    }

    private fun closeBrowser() {
        if (!browserOpen) return
        FreezeWatch.note("channel list closed")
        browserOpen = false
        browserShowGroups = false
        pickingSecond = false
        browserFocusId = null
        handler.postDelayed(hideBrowserView, 320)
        playerView.useController = !inPip && dual == null
        applyVideoArea()
        if (dual != null) updateDualLabels()
        playerView.requestFocus()
    }

    private fun setBrowserGroups(show: Boolean) {
        if (!browserOpen || browserShowGroups == show) return
        browserShowGroups = show
        applyVideoArea()
    }

    /**
     * Live channels: the picked channel plays in the picture beside the list and the list stays
     * open; picking the one already playing goes back to full screen. Films and episodes open
     * full screen straight away.
     */
    private fun pickFromBrowser(list: List<Channel>, ch: Channel) {
        if (pickingSecond || dual != null) {
            pickForDual(list, ch)
            return
        }
        val current = queue.getOrNull(index)
        if (ch.id == current?.id) {
            closeBrowser()
            showBanner(ch)
            return
        }
        val at = list.indexOfFirst { it.id == ch.id }
        if (at < 0) return
        if (ch.kind != Kind.LIVE) closeBrowser()
        saveProgress()
        rememberPrevious()
        // Zapping now follows the group the channel was picked from.
        queue = list
        repo.playQueue = list
        index = at
        retried = false
        resumePositionMs = C.TIME_UNSET
        if (ch.kind != Kind.SERIES) repo.currentPlaylist?.let { repo.addRecent(it.id, ch.id) }
        playCurrent(showInfo = !browserOpen)
    }

    /**
     * Where the picture goes. Full screen normally; while the browser is open it fits into the free
     * space beside the list (above it on a phone held upright), always whole (Fit), never hidden.
     */
    private fun applyVideoArea() {
        if (!::root.isInitialized) return
        val w = root.width
        val h = root.height
        dual?.let { d ->
            layoutDual(d, w, h)
            return
        }
        val m = when {
            !browserOpen || w == 0 || h == 0 -> intArrayOf(0, 0, 0, 0)
            h > w -> intArrayOf(0, 0, 0, (h - w * 9 / 16).coerceAtLeast(0))
            else -> {
                val panel = dp(BrowserLayout.panelWidthDp(tvLayout, browserShowGroups))
                val gap = dp(if (tvLayout) 24 else 12)
                intArrayOf((panel + gap).coerceAtMost(w - dp(120)), gap, gap, gap)
            }
        }
        for (v in listOfNotNull<View>(playerView, vlcLayout, messageView)) {
            val lp = v.layoutParams as? FrameLayout.LayoutParams ?: continue
            if (lp.leftMargin != m[0] || lp.topMargin != m[1] || lp.rightMargin != m[2] || lp.bottomMargin != m[3]) {
                lp.setMargins(m[0], m[1], m[2], m[3])
                v.layoutParams = lp
            }
        }
        playerView.resizeMode = if (browserOpen) AspectRatioFrameLayout.RESIZE_MODE_FIT else resizeModes[aspect]
        vlc?.setScale(if (browserOpen) 0 else aspect)
    }

    // ---- remote control keys ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::playerView.isInitialized) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == swallowUpKey) {
            swallowUpKey = KeyEvent.KEYCODE_UNKNOWN
            return true
        }
        // Stopped by the sleep timer: OK carries on.
        if (sleptByTimer && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !menuOpen && !browserOpen && !styleOpen &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
        ) {
            wakeFromSleep()
            swallowUpKey = event.keyCode
            return true
        }
        // The ambient screen: any key just brings the picture back.
        if (ambientOn) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                hideAmbient()
                swallowUpKey = event.keyCode
            }
            return true
        }
        // The Menu takes the remote while it is open; Back closes it.
        if (menuOpen) {
            if (event.action == KeyEvent.ACTION_DOWN &&
                (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE)
            ) {
                closeMenu()
                swallowUpKey = event.keyCode
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        // So does the caption style panel; arrows past its edge stay in it (the remote's focus
        // never wanders off to the hidden controls).
        if (styleOpen) {
            if (event.action == KeyEvent.ACTION_DOWN &&
                (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE)
            ) {
                closeCaptionStyle()
                swallowUpKey = event.keyCode
                return true
            }
            return super.dispatchKeyEvent(event) || event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        }
        if (browserOpen && event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE)
        ) {
            // Back closes the groups first, then the browser.
            if (browserShowGroups) setBrowserGroups(false) else closeBrowser()
            swallowUpKey = event.keyCode
            return true
        }
        // While the browser is open, the remote moves through it instead of zapping.
        if (browserOpen) return super.dispatchKeyEvent(event)
        // A programme reminder on screen: OK switches to it, Back closes it.
        val alert = ProgramAlerts.current.value?.takeIf { it.channelId != currentChannelId }
        if (alert != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            (!playerView.isControllerFullyVisible || dual != null)
        ) {
            val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            val back = event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE
            if (ok || back) {
                if (ok) watchAlert(alert) else ProgramAlerts.dismiss()
                swallowUpKey = event.keyCode
                return true
            }
        }
        if (dual != null) return dualKey(event)
        val live = queue.getOrNull(index)?.kind == Kind.LIVE
        val controlsShown = playerView.isControllerFullyVisible
        // Up next / Skip intro on screen: OK takes it, Back keeps watching.
        if (event.action == KeyEvent.ACTION_DOWN && !controlsShown && event.repeatCount == 0) {
            val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            val back = event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE
            // Paused for a prayer: OK carries on, Back just closes the banner.
            if (app.falak.player.PrayerAlerts.current.value != null && (ok || back)) {
                endPrayerPause(resume = ok)
                swallowUpKey = event.keyCode
                return true
            }
            if (upNext != null && (ok || back)) {
                if (ok) playUpNext() else dismissUpNext()
                swallowUpKey = event.keyCode
                return true
            }
            if (skipIntroShown && ok) {
                skipIntro()
                swallowUpKey = event.keyCode
                return true
            }
            if (startOverOffer != null && ok) {
                startOver()
                swallowUpKey = event.keyCode
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                    if (typed.isNotEmpty()) {
                        // OK after typing a number: go now instead of waiting.
                        handler.removeCallbacks(commitTyped)
                        useTypedNumber()
                        swallowUpKey = event.keyCode
                        return true
                    } else if (!controlsShown && event.repeatCount == 0) {
                        // OK always brings up the controls (subtitles, audio, channels…).
                        playerView.showController()
                        swallowUpKey = event.keyCode
                        return true
                    }
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9, in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 ->
                    if (live && event.repeatCount == 0) {
                        val d = if (event.keyCode >= KeyEvent.KEYCODE_NUMPAD_0) event.keyCode - KeyEvent.KEYCODE_NUMPAD_0 else event.keyCode - KeyEvent.KEYCODE_0
                        typeDigit(d)
                        return true
                    }
                KeyEvent.KEYCODE_LAST_CHANNEL -> { recallPrevious(); return true }
                KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> if (live && !controlsShown) { zap(1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (live && !controlsShown) { zap(-1); return true }
                // Films and episodes: left/right jump 10 s per press, faster while held; the jump
                // happens once you stop pressing, so it is quick and precise.
                // Live channels: Left opens the channel list (Right brings up the controls).
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (live && !controlsShown && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (event.repeatCount == 0) openBrowser()
                    swallowUpKey = event.keyCode
                    return true
                } else if (!controlsShown && canSeek()) {
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
        // Live channels never jump back or forward, even when the stream keeps a time window.
        if (queue.getOrNull(index)?.kind == Kind.LIVE) return false
        val p = playerView.player ?: return false
        return p.duration > 0 && p.isCurrentMediaItemSeekable && !inPip
    }

    /** Screenshot tests: the controls (or the channel list) over a stand-in player, nothing playing. */
    @androidx.annotation.VisibleForTesting
    internal fun previewControls(stand: Player, browser: Boolean = false, groups: Boolean = false, menu: Boolean = false) {
        handler.removeCallbacksAndMessages(null)
        releaseEngine()
        hideMessage()
        bannerView.visibility = View.GONE
        playerView.player = ZapPlayer(stand)
        updateTrackButtons()
        updateTimeViews()
        if (browser) openBrowser() else playerView.showController()
        if (browser && groups) setBrowserGroups(true)
        if (menu) showAppMenu()
    }

    /** Screenshot tests: the Up next card over the picture. */
    @androidx.annotation.VisibleForTesting
    internal fun previewUpNext(next: Channel) {
        playerView.hideController()
        upNext = next
        upNextLabel = "S1 E3"
        upNextSecs = 8
    }

    /** Screenshot tests: the ambient screen over a paused video. */
    @androidx.annotation.VisibleForTesting
    internal fun previewAmbient() {
        val idx = repo.currentIndex
        ambientSlides = ambientSlides(idx.movies, idx.series, emptyMap(), emptySet())
        ambientOn = true
    }

    /**
     * Screenshot tests: "From the start" offered for the show on now; with [replaying], the show
     * already playing from its start, the controls up with "Back to live".
     */
    @androidx.annotation.VisibleForTesting
    internal fun previewStartOver(ch: Channel, replaying: Boolean) {
        val now = System.currentTimeMillis()
        if (replaying) {
            startOverOffer = null
            liveBeforeStartOver = queue to index
            liveButton.visibility = View.VISIBLE
            playerView.showController()
        } else {
            playerView.hideController()
            startOverOffer = ch to app.falak.player.data.Program(now - 38 * 60_000L, now + 22 * 60_000L, "The Big Match", "")
        }
    }

    /** Screenshot tests: paused for a prayer, with its banner. */
    @androidx.annotation.VisibleForTesting
    internal fun previewPrayer() {
        playerView.hideController()
        prayerPaused = true
        app.falak.player.PrayerAlerts.showForTest(app.falak.player.PrayerAlerts.Alert("Maghrib", "5:45 PM", 1L))
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
        // Picture-in-picture shows one channel: the one being heard.
        if (dual != null) endDual(keepSecond = dualSecond)
        // Listen mode (phones): the sound carries on in the background instead of a small window.
        if (startListening()) return
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
        // Captions are too small to read in the little window: they pause meanwhile.
        if (isInPictureInPictureMode) stopAutoCaptions() else resumeAutoCaptions()
        if (isInPictureInPictureMode) {
            if (browserOpen) {
                browserOpen = false
                browserShowGroups = false
                applyVideoArea()
                browserView.visibility = View.GONE
            }
            if (styleOpen) {
                styleOpen = false
                browserView.visibility = View.GONE
                renderAutoCaption()
            }
            bannerView.visibility = View.GONE
            hideMessage()
        } else if (lifecycle.currentState == Lifecycle.State.CREATED) {
            finish()
        }
    }

    // ---- progress & picture size ----

    /** Saves how far a movie/episode got; finished or barely started items are cleared. */
    /** Downloaded films and episodes watched to the end here, by id (with their names), to delete on leaving them. */
    private val watchedCopies = LinkedHashMap<String, String>()

    /** Deletes the saved copies watched to the end, except the one still playing. */
    private fun deleteWatchedCopies(except: String? = null) {
        val gone = watchedCopies.keys.filter { it != except }
        if (gone.isEmpty()) return
        val names = gone.mapNotNull { watchedCopies[it] }
        gone.forEach { watchedCopies.remove(it) }
        app.falak.player.OfflineCenter.deleteMany(gone)
        val text = if (names.size == 1) "Watched: “${names[0]}” deleted from Downloads" else "Watched: ${names.size} deleted from Downloads"
        android.widget.Toast.makeText(applicationContext, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun saveProgress() {
        val p = player ?: return
        val ch = queue.getOrNull(index) ?: return
        if (ch.kind == Kind.LIVE || ch.id.startsWith("cu:")) return
        val pl = repo.currentPlaylist ?: return
        val dur = p.duration
        if (dur <= 0) return
        val pos = p.currentPosition
        val finished = p.playbackState == Player.STATE_ENDED || pos > dur - 120_000
        val show = repo.currentSeries
        val meta = metaFor(ch)
        val key = progressKey(repo.state.value.mine(pl.id), ch.id)
        val kept = if (finished || pos < 30_000) 0 else pos
        repo.saveProgress(key, kept, dur, meta, show?.seriesId.orEmpty())
        // Its saved copy goes once the player leaves it (Downloads → Delete when watched).
        if (finished && repo.state.value.settings.downloadDeleteWatched && app.falak.player.OfflineCenter.fileFor(ch.id) != null) {
            watchedCopies += ch.id to ch.name
        } else {
            watchedCopies.remove(ch.id)
        }
        // Android TV home screen row: never for hidden, locked or adult groups, nor in kids mode.
        val s = repo.state.value.settings
        val group = if (show != null && ch.kind == Kind.SERIES) show.group else ch.group
        val onHome = !repo.kidsActive && group !in repo.excludedGroups &&
            group !in s.lockedGroups[pl.id].orEmpty() && !app.falak.player.data.ADULT_GROUP.containsMatchIn(group)
        app.falak.player.data.WatchNext.update(this, key, meta, kept, dur, onHome)
        if (finished && ch.kind == Kind.SERIES) {
            // Watched to the end: the next episode waits in Continue watching (and on the TV's
            // Play next row), and the show is followed for new episodes.
            nextEpisode()?.let { n ->
                val nextKey = progressKey(repo.state.value.mine(pl.id), n.id)
                if (repo.state.value.progress[nextKey] == null) {
                    val nm = metaFor(n)
                    repo.saveProgress(nextKey, 1, 0, nm, show?.seriesId.orEmpty())
                    app.falak.player.data.WatchNext.update(this, nextKey, nm, 1, 0, onHome, next = true)
                }
            }
            if (show != null && show.seriesId.isNotEmpty() && !repo.kidsActive) {
                repo.follow(pl.id, show, if (queue.size > 1) queue.size else 0)
            }
        }
    }

    /** Episodes are saved with the show's name and artwork so "Continue watching" looks right. */
    private fun metaFor(ch: Channel): Channel {
        val show = repo.currentSeries
        if (show == null || ch.kind != Kind.SERIES) return ch
        val episode = ch.info.split(" · ").take(2).joinToString(" ").ifBlank { ch.name }
        return ch.copy(name = "${show.name} · $episode", logo = show.logo.ifBlank { ch.logo })
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
        setPillLabel(rotateButton, rotationLabel())
        applyRotation()
    }

    private fun cycleAspect() = setAspect((aspect + 1) % resizeModes.size, byPinch = false)

    /** The picture's shape: Fit, Fill (stretched) or Zoom (edges cut). Kept for next time. */
    private fun setAspect(to: Int, byPinch: Boolean) {
        val changed = to != aspect
        aspect = to
        playerView.resizeMode = resizeModes[aspect]
        vlc?.setScale(aspect)
        setPillLabel(aspectButton, aspectNames[aspect])
        if (byPinch) {
            showLevel(if (aspect == ASPECT_ZOOM) "Zoom · fills the screen" else "Fit · the whole picture", null)
        } else {
            Toast.makeText(this, "Picture: ${aspectNames[aspect]}", Toast.LENGTH_SHORT).show()
        }
        if (changed || !byPinch) repo.updateSettings { it.copy(aspect = aspect) }
    }

    // ---- phones: brightness and volume by sliding a finger ----

    private fun canLevelSwipe(e: android.view.MotionEvent): Boolean =
        !isTvDevice && !inPip && dual == null && !browserOpen &&
            // Not where Android's own swipes start (the notification shade, the home bar).
            e.y > dp(48) && e.y < playerView.height - dp(48)

    private fun beginLevel(kind: Int) {
        levelSwipe = kind
        levelValue = if (kind == LEVEL_BRIGHTNESS) {
            window.attributes.screenBrightness.takeIf { it >= 0f } ?: runCatching {
                android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
            }.getOrDefault(0.5f)
        } else {
            val am = getSystemService(android.media.AudioManager::class.java)
            if (am == null) 0f else am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() /
                am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        }.coerceIn(0f, 1f)
    }

    private fun changeLevel(delta: Float) {
        levelValue = (levelValue + delta).coerceIn(0f, 1f)
        if (levelSwipe == LEVEL_BRIGHTNESS) {
            // This screen only: the phone's own brightness comes back when the player closes.
            window.attributes = window.attributes.apply { screenBrightness = levelValue.coerceAtLeast(0.01f) }
            showLevel("Brightness", levelValue)
        } else {
            val am = getSystemService(android.media.AudioManager::class.java) ?: return
            val stream = android.media.AudioManager.STREAM_MUSIC
            val v = kotlin.math.round(levelValue * am.getStreamMaxVolume(stream)).toInt()
            if (v != am.getStreamVolume(stream)) runCatching { am.setStreamVolume(stream, v, 0) }
            showLevel(if (v == 0) "Muted" else "Volume", levelValue)
        }
    }

    /** The level in the middle of the picture for a moment ([fraction] null: just the words). */
    private fun showLevel(text: String, fraction: Float?) {
        levelLabel.text = if (fraction == null) text else "$text  ${(fraction * 100).toInt()}%"
        levelBar.visibility = if (fraction == null) View.GONE else View.VISIBLE
        if (fraction != null) levelBar.progress = (fraction * 100).toInt()
        seekView.visibility = View.GONE
        levelView.visibility = View.VISIBLE
        handler.removeCallbacks(hideLevel)
        handler.postDelayed(hideLevel, 1_000)
    }

    // ---- overlays ----

    // ---- audio & subtitle tracks ----

    /** [memo]: what to remember when it's picked (see TrackMemory). */
    private class TrackOption(val label: String, val selected: Boolean, val memo: String? = null, val select: () -> Unit)

    /** A caption track we added that turned out to carry text, or any track the stream declared. */
    private fun isReal(f: androidx.media3.common.Format): Boolean =
        !Captions.isProbe(f) || (sniffer.found and Captions.bitOf(f)) != 0

    private fun trackOptions(type: Int): List<TrackOption> {
        vlc?.let { return vlcTrackOptions(it, type) }
        val p = exo ?: return emptyList()
        val out = ArrayList<TrackOption>()
        if (type == C.TRACK_TYPE_TEXT) {
            val anyOn = p.currentTracks.groups.any { it.type == type && it.isSelected }
            out += TrackOption("Off", !anyOn && !autoCapsOn, "off") {
                stopAutoCaptions()
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
                val f = g.getTrackFormat(i)
                out += TrackOption(label, g.isTrackSelected(i), TrackMatch.valueFor(f.language, base)) {
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
            // Auto captions: only for live channels without captions of their own.
            if (autoCaptionsOffered() && countTracks(C.TRACK_TYPE_TEXT) == 0) {
                val ready = SpeechModel.isReady(this)
                out += TrackOption(
                    "Auto captions (English · beta)" + if (ready) "" else "  · ${SpeechModel.SIZE_LABEL} download",
                    autoCapsOn,
                    if (ready) "auto" else null,
                ) { chooseAutoCaptions() }
            }
        }
        return out
    }

    private fun vlcTrackOptions(v: VlcPlayer, type: Int): List<TrackOption> {
        val out = ArrayList<TrackOption>()
        if (type == C.TRACK_TYPE_TEXT) {
            out += TrackOption("Off", v.selectedSubtitle < 0, "off") { v.selectSubtitle(-1) }
            for (t in v.subtitleTracks()) {
                out += TrackOption(t.name.ifBlank { "Subtitles ${t.id}" }, t.id == v.selectedSubtitle, "name:${t.name}") { v.selectSubtitle(t.id) }
            }
        } else if (type == C.TRACK_TYPE_AUDIO) {
            for (t in v.audioTracks()) out += TrackOption(t.name.ifBlank { "Audio ${t.id}" }, t.id == v.selectedAudio, "name:${t.name}") { v.selectAudio(t.id) }
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
            handingOff = true
            startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: Exception) {
            handingOff = false
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
        applyRememberedTracks()
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
        // The captions' look (and auto captions' place and lines), changed as you watch.
        if (type == C.TRACK_TYPE_TEXT) {
            options += TrackOption("Aa  Caption style…", false) { showCaptionStyle() }
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
                // Picked by hand: this channel, film or series gets it again next time.
                options[which].memo?.let { rememberTrack(type, it) }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun updateTrackButtons() {
        val audio = countTracks(C.TRACK_TYPE_AUDIO)
        val text = countTracks(C.TRACK_TYPE_TEXT)
        val vod = queue.getOrNull(index)?.kind?.let { it != Kind.LIVE } == true
        audioButton.visibility = if (audio > 1) View.VISIBLE else View.GONE
        setPillLabel(audioButton, "Audio ($audio)")
        ccButton.visibility = if (text > 0 || vod || autoCaptionsOffered()) View.VISIBLE else View.GONE
        // The channel turned out to have captions of its own: never auto captions on top of them.
        if (autoCapsOn && text > 0) providerCaptionsTakeOver()
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
        // The audio languages aren't listed (the Audio button has them); only whether there are subtitles.
        if (vlc == null && exo == null) return ""
        val subs = countTracks(C.TRACK_TYPE_TEXT)
        return if (subs > 0) "Subtitles: $subs" else ""
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
        tag = label
        contentDescription = label
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
        // An icon-only button (Settings) is a neat circle-ish pill with even sides.
        compoundDrawablePadding = if (label.isEmpty()) 0 else dp(8)
        compoundDrawableTintList = contentColors()
        background = focusBackground(oval = false, normal = 0x33FFFFFF, edge = 0x26FFFFFF)
        if (label.isEmpty()) {
            val side = dp(if (tvLayout) 13 else 11)
            setPadding(side, 0, side, 0)
        } else {
            setPadding(dp(if (tvLayout) 18 else 14), 0, dp(if (tvLayout) 20 else 16), 0)
        }
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, focused ->
            val s = if (focused) 1.08f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(120).start()
        }
    }

    // Phones: when the buttons don't all fit across, they show their icons only (names are read out).
    private var compactPills = false

    private fun pills(): List<TextView> =
        listOf(menuButton, liveButton, browseButton, twoButton, favButton, audioButton, ccButton, speedButton, aspectButton, rotateButton, engineButton)

    private fun setPillLabel(b: TextView, label: String) {
        b.tag = label
        b.contentDescription = label
        b.text = if (compactPills) "" else label
        b.post { fitPills() }
    }

    /** Labels when the row fits the screen's width, icons only when it doesn't (never on TV). */
    private fun fitPills() {
        if (tvLayout || !::toolbar.isInitialized) return
        val avail = toolbar.width
        if (avail <= 0) return
        var need = dp(32)
        for (b in pills()) {
            if (b.visibility != View.VISIBLE) continue
            val label = b.tag as? String ?: ""
            need += dp(12) + dp(14) + dp(16) + dp(18) + (if (label.isEmpty()) 0 else dp(8) + b.paint.measureText(label).toInt())
        }
        val compact = need > avail
        if (compact == compactPills) return
        compactPills = compact
        for (b in pills()) {
            val label = b.tag as? String ?: ""
            b.text = if (compact) "" else label
            b.compoundDrawablePadding = if (compact || label.isEmpty()) 0 else dp(8)
            if (compact) b.setPadding(dp(13), 0, dp(13), 0) else b.setPadding(dp(14), 0, dp(16), 0)
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
            tidyMediaControls(controls)
            // Media3 keeps the seek bar (48 dp tall) 52 dp above the bottom of the controls: with this
            // padding it sits just above the row.
            val pad = (rowHeight - dp(48)).coerceAtLeast(0)
            controls.clipToPadding = false
            controls.setPadding(0, 0, 0, pad)
            controls.addView(
                toolbar,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight, Gravity.BOTTOM)
                    .apply { bottomMargin = -pad },
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

    /** Media3's own buttons (play, previous/next, ±10 s): the same white highlight. */
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

    // ---- channel numbers and last channel (remote control) ----

    private fun typeDigit(d: Int) {
        if (typed.length >= 4) typed.setLength(0)
        typed.append(d)
        handler.removeCallbacks(commitTyped)
        val n = typed.toString().toInt()
        val target = queue.getOrNull(n - 1)?.takeIf { n > 0 }
        val text = SpannableStringBuilder()
        text.append(typed.toString(), StyleSpan(Typeface.BOLD), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(android.text.style.RelativeSizeSpan(1.8f), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        when {
            typed.toString() == "0" -> text.append("\nLast channel")
            target != null -> text.append("\n${target.name}")
            else -> text.append("\nNo channel $n here")
        }
        numberView.text = text
        numberView.visibility = View.VISIBLE
        // Like a TV: the number is used once you stop typing (or press OK).
        handler.postDelayed(commitTyped, if (typed.length >= 4) 300 else 1_600)
    }

    private fun useTypedNumber() {
        val s = typed.toString()
        typed.setLength(0)
        numberView.visibility = View.GONE
        if (s.isEmpty()) return
        // A lone 0 flips back to the channel watched before, as on most remotes.
        if (s == "0") {
            recallPrevious()
            return
        }
        val i = s.toInt() - 1
        if (i !in queue.indices || i == index) return
        saveProgress()
        rememberPrevious()
        index = i
        retried = false
        resumePositionMs = C.TIME_UNSET
        playCurrent(showInfo = true)
    }

    private fun rememberPrevious() {
        if (queue.getOrNull(index)?.kind == Kind.LIVE) previous = queue to index
    }

    /** Back to the channel watched before this one (the remote's "last channel"). */
    private fun recallPrevious() {
        val (list, at) = previous ?: return
        if (at !in list.indices) return
        saveProgress()
        val here = queue to index
        queue = list
        repo.playQueue = list
        index = at
        previous = here
        retried = false
        resumePositionMs = C.TIME_UNSET
        playCurrent(showInfo = true)
    }

    // ---- backup streams ----

    /**
     * A live channel that won't play: try the same channel listed elsewhere in the playlist (another
     * group or quality): same quality first, then lower, then higher. Returns false when there is
     * nothing left to try.
     */
    private fun tryBackup(ch: Channel): Boolean {
        if (ch.kind != Kind.LIVE || ch.id.startsWith("cu:")) return false
        if (backupOf != ch.id) {
            backupOf = ch.id
            backupTried.clear()
        }
        if (backupTried.size >= 4) return false
        // Tidy channel list merged other versions of it: those first.
        repo.currentIndex.versions[ch.id]?.firstOrNull { it.url != ch.url && it.url != currentUrl && it.url !in backupTried }?.let { v ->
            backupTried += v.url
            FreezeWatch.note("backup stream for a channel that failed")
            showMessage("${ch.name} isn't playing\nTrying another version of it…")
            retried = false
            handler.post { playCurrent(showInfo = false, overrideUrl = v.url) }
            return true
        }
        val excluded = repo.excludedGroups
        val key = app.falak.player.data.sameChannelKey(ch)
        val rank = app.falak.player.data.qualityRank(ch)
        val alt = repo.currentIndex.live.asSequence()
            .filter { it.id != ch.id && it.url != ch.url && it.url !in backupTried && it.group !in excluded }
            .filter { app.falak.player.data.sameChannelKey(it) == key }
            .sortedBy { c ->
                val r = app.falak.player.data.qualityRank(c)
                when {
                    r == rank -> 0
                    r < rank -> 10 + (rank - r)
                    else -> 20 + (r - rank)
                }
            }
            .firstOrNull() ?: return false
        backupTried += alt.url
        FreezeWatch.note("backup stream for a channel that failed")
        showMessage("${ch.name} isn't playing\nTrying it from ${alt.group}…")
        retried = false
        handler.post { playCurrent(showInfo = false, overrideUrl = alt.url) }
        return true
    }

    // ---- series: up next, skip intro ----

    private lateinit var extrasView: ComposeView
    /** The next episode, while its card is up; null otherwise. */
    private var upNext by mutableStateOf<Channel?>(null)
    private var upNextLabel by mutableStateOf("")
    /** Seconds until it starts by itself (null: no countdown, "Play next" only). */
    private var upNextSecs by mutableStateOf<Int?>(null)
    /** "Keep watching" was chosen for this episode: no card, no automatic next. */
    private var upNextDismissed: String? = null
    private var skipIntroShown by mutableStateOf(false)
    private var introSkippedFor: String? = null
    private var skippingIntro = false

    /** "Show Name" from "Show Name S01E02", "Show Name - S1 E2 - Title", "Show 1x02"… */
    private fun episodeShow(name: String): String =
        name.replace(EPISODE_MARK, "").trim().trimEnd('-', '–', '|', ':', '·').trim()

    /** The episode after this one, if it's the same show (across seasons for Xtream series). */
    private fun nextEpisode(): Channel? {
        val ch = queue.getOrNull(index) ?: return null
        if (ch.kind != Kind.SERIES || ch.id.startsWith("cu:")) return null
        val n = queue.getOrNull(index + 1) ?: return null
        if (n.kind != Kind.SERIES) return null
        // An Xtream series' queue is its own episodes; in other lists, only the same show follows.
        if (repo.currentSeries == null) {
            val a = episodeShow(ch.name)
            if (a.isEmpty() || !a.equals(episodeShow(n.name), ignoreCase = true)) return null
        }
        return n
    }

    /** "S1 E3", from the episode's details or its name. */
    private fun episodeLabel(ch: Channel): String {
        ch.info.split(" · ").take(2).joinToString(" ").takeIf { it.startsWith("S") }?.let { return it }
        val m = Regex("(?i)\\bS(\\d{1,2})\\s*[ .x-]?\\s*E(\\d{1,3})\\b").find(ch.name) ?: return ""
        return "S${m.groupValues[1].toInt()} E${m.groupValues[2].toInt()}"
    }

    /** The show an episode belongs to, for its learned intro: "playlistId|s:<series id>" or "|n:<name>". */
    private fun showKey(ch: Channel): String? {
        if (ch.kind != Kind.SERIES) return null
        val pl = repo.currentPlaylist ?: return null
        val sid = repo.currentSeries?.seriesId?.takeIf { it.isNotEmpty() }
            ?: repo.state.value.progress[progressKey(repo.state.value.mine(pl.id), ch.id)]?.seriesId?.takeIf { it.isNotEmpty() }
        val show = sid?.let { "s:$it" } ?: episodeShow(ch.name).takeIf { it.isNotBlank() }?.let { "n:" + it.lowercase() } ?: return null
        return "${pl.id}|$show"
    }

    /** Once a second while an episode plays: the Skip intro button and the Up next card. */
    private fun seriesTick() {
        val p = player
        val ch = queue.getOrNull(index)
        if (p == null || ch == null || ch.kind != Kind.SERIES || inPip) {
            if (upNext != null) upNext = null
            skipIntroShown = false
            return
        }
        val dur = p.duration
        val pos = p.currentPosition
        val intro = showKey(ch)?.let { repo.state.value.intros[it] }
        skipIntroShown = intro != null && introSkippedFor != ch.id && dur > 0 &&
            pos >= intro.start - 2_000 && pos < intro.end - 6_000
        val next = nextEpisode()
        val ended = p.playbackState == Player.STATE_ENDED
        val credits = dur > 5 * 60_000 && dur - pos <= UP_NEXT_MS
        if (next == null || upNextDismissed == ch.id || SleepTimer.stopsAfter(ch.id) || kidsFinishing != null || !(ended || credits)) {
            if (upNext != null) {
                upNext = null
                upNextSecs = null
            }
            return
        }
        if (upNext?.id != next.id) {
            upNext = next
            upNextLabel = episodeLabel(next)
            upNextSecs = if (repo.state.value.settings.autoNextEpisode) COUNTDOWN_S else null
            return
        }
        val secs = upNextSecs ?: return
        // Counts down while playing (a pause holds it).
        if (p.isPlaying || ended) {
            if (secs <= 1) playUpNext() else upNextSecs = secs - 1
        }
    }

    private fun playUpNext() {
        upNext = null
        upNextSecs = null
        if (nextEpisode() == null) return
        FreezeWatch.note("up next")
        zap(1)
    }

    private fun dismissUpNext() {
        upNextDismissed = queue.getOrNull(index)?.id
        upNext = null
        upNextSecs = null
    }

    private fun skipIntro() {
        val ch = queue.getOrNull(index) ?: return
        val intro = showKey(ch)?.let { repo.state.value.intros[it] } ?: return
        introSkippedFor = ch.id
        skipIntroShown = false
        skippingIntro = true
        player?.seekTo(intro.end)
    }

    // Learning a show's intro: a jump forward early in an episode (one drag, or several presses
    // in a row), 20 s to 4 min long, is taken as "skip the intro" for the show's next episodes.
    private var introFrom = -1L
    private var introTo = -1L
    private var introAt = 0L
    private val saveIntro = Runnable {
        val ch = queue.getOrNull(index)
        val key = ch?.let { showKey(it) }
        val from = introFrom
        val to = introTo
        introFrom = -1L
        if (key != null && from in 0..INTRO_WINDOW_MS && to - from in 20_000L..240_000L) repo.setIntro(key, from, to)
    }

    private fun learnIntro(from: Long, to: Long) {
        val ch = queue.getOrNull(index) ?: return
        if (ch.kind != Kind.SERIES) return
        if (skippingIntro) {
            skippingIntro = false // our own Skip intro: nothing new to learn
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (introFrom >= 0 && now - introAt < 10_000) {
            // More presses, or a small step back after going too far: still the same skip.
            if (to > introFrom) introTo = to else introFrom = -1L
        } else {
            if (to <= from) return
            introFrom = from
            introTo = to
        }
        introAt = now
        handler.removeCallbacks(saveIntro)
        if (introFrom >= 0) handler.postDelayed(saveIntro, 4_000)
    }

    /** An episode resumed on its own (Continue watching): fetch the rest of its series for Up next. */
    private fun ensureSeriesQueue(ch: Channel) {
        if (ch.kind != Kind.SERIES || queue.size > 1) return
        val pl = repo.currentPlaylist ?: return
        val sid = repo.currentSeries?.seriesId?.takeIf { it.isNotEmpty() }
            ?: repo.state.value.progress[progressKey(repo.state.value.mine(pl.id), ch.id)]?.seriesId?.takeIf { it.isNotEmpty() }
            ?: return
        val show = repo.currentSeries?.takeIf { it.seriesId == sid } ?: repo.currentIndex.series.firstOrNull { it.seriesId == sid }
        lifecycleScope.launch {
            val info = try {
                repo.loadSeries(pl, sid)
            } catch (e: Exception) {
                return@launch
            }
            val all = info.seasons.flatMap { it.episodes }
            val at = all.indexOfFirst { it.id == ch.id }
            if (at < 0 || queue.size != 1 || queue[0].id != ch.id) return@launch
            queue = all
            index = at
            repo.playQueue = all
            if (show != null) repo.currentSeries = show
        }
    }

    // ---- ambient screen ----

    private var ambientOn by mutableStateOf(false)
    private var ambientSlides: List<AmbientSlide> = emptyList()
    private var pausedSecs = 0

    /** Once a second: paused long enough (and nothing else on screen)? */
    private fun ambientTick() {
        val p = player
        val resting = p != null && !p.playWhenReady && p.playbackState != Player.STATE_IDLE && p.playbackState != Player.STATE_ENDED &&
            !browserOpen && !menuOpen && !styleOpen && upNext == null && !inPip
        pausedSecs = if (resting) pausedSecs + 1 else 0
        if (!ambientOn && pausedSecs >= AMBIENT_PAUSED_S && repo.state.value.settings.ambient) {
            val idx = repo.currentIndex
            ambientSlides = ambientSlides(idx.movies, idx.series, emptyMap(), repo.excludedGroups)
            ambientOn = true
            FreezeWatch.note("ambient screen")
        }
    }

    private fun hideAmbient() {
        ambientOn = false
        pausedSecs = 0
        playerView.showController()
    }

    // ---- prayer times ----

    /** Paused by a prayer reminder (Settings → Prayer times → Reminder and pause). */
    private var prayerPaused by mutableStateOf(false)
    private var prayerSeen = 0L

    /** A new reminder: pause what's playing, if that's what was chosen. */
    private fun onPrayer(alert: app.falak.player.PrayerAlerts.Alert?) {
        if (alert == null) {
            prayerPaused = false
            return
        }
        if (alert.at == prayerSeen) return
        prayerSeen = alert.at
        if (repo.state.value.settings.prayerMode == "pause" && dual != null) endDual(keepSecond = false, replay = false)
        val p = player ?: return
        if (repo.state.value.settings.prayerMode == "pause" && p.isPlaying) {
            p.pause()
            prayerPaused = true
            FreezeWatch.note("paused for prayer")
        }
    }

    private fun endPrayerPause(resume: Boolean) {
        val wasPaused = prayerPaused
        prayerPaused = false
        app.falak.player.PrayerAlerts.dismiss()
        if (resume && wasPaused) {
            val p = player ?: return
            // Live TV picks up where the channel is now, not where it stopped.
            if (queue.getOrNull(index)?.kind == Kind.LIVE) p.seekToDefaultPosition()
            p.play()
        }
    }

    // ---- subtitles look, frame rate ----

    private fun settingsFrameRate(): String = repo.state.value.settings.frameRate

    /** ExoPlayer's subtitles and auto captions in the look chosen (unchanged when nothing was chosen). */
    private fun applySubtitleStyle() {
        val s = repo.state.value.settings
        playerView.subtitleView?.let { SubtitleStyle.applyTo(it, s.subSize, s.subColor, s.subEdge) }
        // Auto captions look like the stream's own.
        if (::autoCapsView.isInitialized) SubtitleStyle.applyTo(autoCapsView, s.subSize, s.subColor, s.subEdge)
        fitCaptions()
    }

    /**
     * Both kinds of captions fitted to the picture's box (see [CaptionLayout]): kept on the part
     * that's on screen and given one size, and auto captions' lines measured at that size.
     */
    private fun fitCaptions() {
        if (!::autoCapsView.isInitialized) return
        val box = autoCapsView.parent as? View ?: return
        val outer = box.parent as? View ?: return
        if (box.width <= 0 || box.height <= 0 || outer.width <= 0 || outer.height <= 0) return
        val pad = CaptionLayout.padding(box.left, box.top, box.right, box.bottom, outer.width, outer.height)
        val w = box.width - pad[0] - pad[2]
        val h = box.height - pad[1] - pad[3]
        if (w <= 0 || h <= 0) return
        val s = repo.state.value.settings
        val asItComes = SubtitleStyle.isDefault(s.subSize, s.subColor, s.subEdge)
        // As it comes, the size and typeface are the ones set in the system's caption settings.
        val system = getSystemService(android.view.accessibility.CaptioningManager::class.java)?.takeIf { it.isEnabled }
        val scale = if (asItComes) system?.fontScale ?: 1f else SubtitleStyle.scale(s.subSize)
        val px = CaptionLayout.textPx(w, h, scale)
        for (v in listOfNotNull(playerView.subtitleView?.takeIf { it.parent === box }, autoCapsView)) {
            if (v.paddingLeft != pad[0] || v.paddingTop != pad[1] || v.paddingRight != pad[2] || v.paddingBottom != pad[3]) {
                v.setPadding(pad[0], pad[1], pad[2], pad[3])
            }
            v.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        }
        val typeface = if (asItComes) {
            system?.let { runCatching { androidx.media3.ui.CaptionStyleCompat.createFromCaptionStyle(it.userStyle).typeface }.getOrNull() }
        } else {
            Typeface.DEFAULT
        }
        capsPaint.typeface = typeface ?: Typeface.DEFAULT
        capsPaint.textSize = px
        capsLineWidth = CaptionLayout.lineWidth(w, px)
        renderAutoCaption()
    }

    // ---- night sound ----

    /** ExoPlayer's night-sound stage (null with VLC or when it's set to Off). */
    private var nightSound: app.falak.player.player.NightSound? = null
    private var nightChecks = 0
    private var prayerJob: kotlinx.coroutines.Job? = null

    /** Night sound wanted right now: always, never, or from sunset to sunrise where the user is. */
    private fun nightNow(): Boolean = when (repo.state.value.settings.nightSound) {
        "on" -> true
        "off" -> false
        else -> app.falak.player.data.SunTimes.isDark(this)
    }

    // ---- data rate ----

    /** Bytes ExoPlayer has downloaded (VLC keeps its own count). */
    private val bytesIn = java.util.concurrent.atomic.AtomicLong()
    private val transferMeter = object : androidx.media3.datasource.TransferListener {
        override fun onTransferInitializing(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {}
        override fun onTransferStart(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {}
        override fun onTransferEnd(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {}
        override fun onBytesTransferred(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (isNetwork) bytesIn.addAndGet(bytesTransferred.toLong())
        }
    }
    private val rateSamples = ArrayDeque<Pair<Long, Long>>()
    private var rateBps = 0L

    /** Once a second: how fast the stream is coming in, over the last few seconds. */
    private val rateTick = object : Runnable {
        override fun run() {
            val total = vlc?.readBytes() ?: bytesIn.get()
            val now = android.os.SystemClock.elapsedRealtime()
            rateSamples.addLast(now to total)
            while (rateSamples.size > 5) rateSamples.removeFirst()
            val (t0, b0) = rateSamples.first()
            rateBps = if (now > t0 && total >= b0) (total - b0) * 8_000 / (now - t0) else 0
            // The banner on screen shows it as it settles.
            val shown = bannerFor
            if (shown != null && ::bannerView.isInitialized && bannerView.visibility == View.VISIBLE) renderBanner(shown)
            seriesTick()
            sleepTick()
            serviceTick()
            kidsTick()
            ambientTick()
            // Once a minute: night sound follows sunset and sunrise while something plays.
            if (++nightChecks % 60 == 0) nightSound?.enabled = nightNow()
            handler.postDelayed(this, 1_000)
        }
    }

    /** "5.2 Mbps", "850 kbps"; null until there's something to show. */
    private fun rateLabel(): String? {
        if (rateSamples.size < 2 || rateBps <= 0) return null
        return if (rateBps >= 1_000_000) String.format(java.util.Locale.US, "%.1f Mbps", rateBps / 1_000_000.0) else "${rateBps / 1_000} kbps"
    }

    // ---- picture quality ----

    /** "4K", "1080p", "720p"… from the size of the video being played; null until it is known. */
    private fun qualityLabel(): String? {
        val vs = player?.videoSize ?: return null
        val w = vs.width
        val h = vs.height
        if (w <= 0 || h <= 0) return null
        val lines = minOf(w, h) // a portrait video is measured across
        val long = maxOf(w, h)
        return when {
            lines >= 2000 || long >= 3600 -> "4K"
            lines >= 1400 || long >= 2500 -> "1440p"
            lines >= 1000 || long >= 1800 -> "1080p"
            lines >= 700 || long >= 1200 -> "720p"
            lines >= 560 -> "576p"
            lines >= 470 -> "480p"
            else -> "${lines}p"
        }
    }

    // ---- leaving for other parts of the app ----

    /** The places the Menu offers, for this playlist. */
    private fun menuPlaces(): List<MenuPlace> {
        val idx = repo.currentIndex
        return buildList {
            add(MenuPlace("HOME", "Home", Section.HOME))
            if (idx.live.isNotEmpty()) add(MenuPlace("LIVE", "Live TV", Section.LIVE))
            if (idx.movies.isNotEmpty()) add(MenuPlace("MOVIES", "Films", Section.MOVIES))
            if (idx.series.isNotEmpty()) add(MenuPlace("SERIES", "Series", Section.SERIES))
            add(MenuPlace("FAVORITES", "Favorites", Section.FAVORITES))
            if (idx.live.isNotEmpty()) add(MenuPlace("GUIDE", "TV guide"))
            // Kids mode: no way out to grown-up pages from the player.
            if (!repo.kidsActive) {
                add(MenuPlace("NOTICES", "What's new"))
                add(MenuPlace("SETTINGS", "Settings"))
            }
        }
    }

    /** Films, Series, the guide, Settings… from inside the player, in the app's own side menu. */
    private fun showAppMenu() {
        if (inPip || menuOpen) return
        if (browserOpen) closeBrowser()
        handler.removeCallbacks(hideBrowserView)
        playerView.hideController()
        playerView.useController = false
        bannerView.visibility = View.GONE
        browserView.visibility = View.VISIBLE
        menuOpen = true
        browserView.requestFocus()
    }

    private fun closeMenu() {
        if (!menuOpen) return
        menuOpen = false
        handler.postDelayed(hideBrowserView, 300)
        playerView.useController = !inPip
        playerView.requestFocus()
    }

    private fun leaveTo(where: String) {
        FreezeWatch.note("player → $where")
        repo.pendingNav = where
        // Settings, the guide and What's new are a detour: Back there brings this back.
        repo.pendingReturnIndex = if (where in setOf("SETTINGS", "GUIDE", "NOTICES")) index else null
        finish()
    }

    // ---- favorites ----

    /** Channels, films and series of the open playlist can be favorites (not replays or single episodes). */
    private fun canFavorite(ch: Channel): Boolean =
        repo.currentPlaylist != null && !ch.id.startsWith("cu:") && repo.currentIndex.byId.containsKey(ch.id)

    private fun isFavorite(ch: Channel): Boolean {
        val pl = repo.currentPlaylist ?: return false
        return ch.id in repo.state.value.favorites[repo.state.value.mine(pl.id)].orEmpty()
    }

    private fun toggleFavorite(ch: Channel) {
        val pl = repo.currentPlaylist ?: return
        if (!canFavorite(ch)) return
        val adding = !isFavorite(ch)
        repo.toggleFavorite(pl.id, ch.id)
        Toast.makeText(this, if (adding) "Added to Favorites" else "Removed from Favorites", Toast.LENGTH_SHORT).show()
        updateFavButton()
        // The browser's "★ Favorites" group follows at once.
        if (browserOpen) queue.getOrNull(index)?.let { cur -> browserGroups = browserGroupsFor(cur) }
    }

    private fun updateFavButton() {
        if (!::favButton.isInitialized) return
        val ch = queue.getOrNull(index)
        if (ch == null || !canFavorite(ch)) {
            favButton.visibility = View.GONE
            return
        }
        val fav = isFavorite(ch)
        favButton.visibility = View.VISIBLE
        setPillLabel(favButton, if (fav) "Favorited" else "Favorite")
        val size = dp(if (tvLayout) 20 else 18)
        ResourcesCompat.getDrawable(resources, if (fav) R.drawable.ic_pl_star else R.drawable.ic_pl_star_outline, theme)?.mutate()?.let {
            it.setBounds(0, 0, size, size)
            favButton.setCompoundDrawablesRelative(it, null, null, null)
        }
    }

    /** Live channels have no real position or length: hide "00:13 · 00:00" and the empty seek bar. */
    private fun updateTimeViews() {
        if (!::playerView.isInitialized) return
        val seekable = canSeek()
        val state = if (seekable) View.VISIBLE else View.INVISIBLE
        for (id in intArrayOf(androidx.media3.ui.R.id.exo_position, androidx.media3.ui.R.id.exo_duration, androidx.media3.ui.R.id.exo_progress)) {
            playerView.findViewById<View>(id)?.visibility = state
        }
        if (::speedButton.isInitialized) speedButton.visibility = if (seekable) View.VISIBLE else View.GONE
        // Live channels: no ±10 s buttons (nothing moves a channel back or forward).
        val live = queue.getOrNull(index)?.kind == Kind.LIVE
        playerView.setShowRewindButton(!live)
        playerView.setShowFastForwardButton(!live)
    }

    /**
     * Media3's bottom bar goes: its settings gear only held speed and audio, which are buttons in our
     * row now, and its time readout moves to either end of the seek bar. One line for the seek bar,
     * one for the buttons.
     */
    private fun tidyMediaControls(controls: FrameLayout) {
        val position = controls.findViewById<TextView>(androidx.media3.ui.R.id.exo_position)
        val duration = controls.findViewById<TextView>(androidx.media3.ui.R.id.exo_duration)
        controls.findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar)?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val side = dp(if (tvLayout) 96 else 72)
        controls.findViewById<View>(androidx.media3.ui.R.id.exo_progress)?.let { bar ->
            (bar.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.leftMargin = side
                lp.rightMargin = side
                bar.layoutParams = lp
            }
        }
        for ((label, edge) in listOf(position to Gravity.START, duration to Gravity.END)) {
            if (label == null) continue
            (label.parent as? ViewGroup)?.removeView(label)
            label.setTextColor(0xE6FFFFFF.toInt())
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tvLayout) 14f else 13f)
            label.typeface = ResourcesCompat.getFont(this, R.font.inter_medium)
            label.fontFeatureSettings = "tnum"
            label.gravity = Gravity.CENTER
            label.setPadding(0, 0, 0, 0)
            // Same height and distance from the bottom as the seek bar, so the numbers sit level with it.
            controls.addView(label, FrameLayout.LayoutParams(side, dp(48), Gravity.BOTTOM or edge).apply { bottomMargin = dp(52) })
        }
    }

    // ---- playback speed (films and episodes) ----

    private var speed = 1f
    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    private fun speedLabel(s: Float): String = if (s == 1f) "Speed" else formatSpeed(s)

    private fun formatSpeed(s: Float): String =
        (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString().trimEnd('0')) + "×"

    private fun showSpeedDialog() {
        val labels = speeds.map { if (it == 1f) "Normal (1×)" else formatSpeed(it) }.toTypedArray()
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Playback speed")
            .setSingleChoiceItems(labels, speeds.indexOfFirst { it == speed }) { d, which ->
                d.dismiss()
                speed = speeds[which]
                setPillLabel(speedButton, speedLabel(speed))
                applySpeed()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** The chosen speed for films and episodes; live channels always play at normal speed. */
    private fun applySpeed() {
        val p = playerView.player ?: return
        if (!p.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return
        val live = queue.getOrNull(index)?.kind == Kind.LIVE
        p.setPlaybackSpeed(if (live) 1f else speed)
    }

    private fun showBanner(ch: Channel) {
        if (inPip || browserOpen) return
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
        // The picture's real quality, from the video being played (not from the channel's name), and
        // how fast the stream is coming in.
        listOfNotNull(qualityLabel(), rateLabel()).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { q ->
            text.append("   ")
            val start = text.length
            text.append(q)
            text.setSpan(ForegroundColorSpan(0xFFF2B33D.toInt()), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(android.text.style.RelativeSizeSpan(0.85f), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
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

    // ---- two channels at once ----

    /** The frame, names, hint and (touch) buttons of two-screen mode, hidden until it's on. */
    private fun createDualViews() {
        dualFrame = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(3), Color.WHITE)
            }
            visibility = View.GONE
            isFocusable = false
        }
        root.addView(dualFrame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fun label() = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tvLayout) 15f else 13f)
            typeface = ResourcesCompat.getFont(this@PlayerActivity, R.font.inter_medium)
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.END
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0xCC111010.toInt())
            }
            setPadding(dp(12), dp(7), dp(12), dp(7))
            visibility = View.GONE
        }
        dualLabelA = label()
        dualLabelB = label()
        for (v in listOf(dualLabelA, dualLabelB)) {
            root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        }
        dualHint = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tvLayout) 16f else 13f)
            typeface = ResourcesCompat.getFont(this@PlayerActivity, R.font.inter_medium)
            gravity = Gravity.CENTER
            text = if (tvLayout) {
                "◀ ▶  choose the sound        ▲ ▼  change channel        OK  channel list        BACK  one screen"
            } else {
                "Tap a picture for its sound · double-tap for full screen"
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xE6111010.toInt())
                setStroke(dp(1), 0x26FFFFFF)
            }
            setPadding(dp(22), dp(11), dp(22), dp(11))
            visibility = View.GONE
        }
        root.addView(
            dualHint,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply {
                    bottomMargin = dp(if (tvLayout) 28 else 76)
                    leftMargin = dp(16)
                    rightMargin = dp(16)
                },
        )
        // Touch: which picture is heard, and double-tap for full screen.
        val taps = android.view.GestureDetector(
            this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: android.view.MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                    val second = inRect(rectSecond, e)
                    if (second || inRect(rectMain, e)) {
                        if (second != dualSecond) {
                            dualSecond = second
                            dualChanged()
                        }
                    }
                    showDualHint()
                    return true
                }

                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    when {
                        inRect(rectSecond, e) -> endDual(keepSecond = true)
                        inRect(rectMain, e) -> endDual(keepSecond = false)
                    }
                    return true
                }
            },
        )
        touchLayer = View(this).apply {
            visibility = View.GONE
            isFocusable = false
            setOnTouchListener { _, e -> taps.onTouchEvent(e) }
        }
        root.addView(touchLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Phones and tablets: a channel list for the picture being heard, and a clear way out.
        dualBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            val channels = pill("Channels", R.drawable.ic_pl_channels) { openBrowserForDual() }
            val exit = pill("One screen", R.drawable.ic_pl_close) { endDual() }
            for (b in listOf(channels, exit)) {
                addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply { setMargins(dp(6), 0, dp(6), 0) })
            }
        }
        root.addView(
            dualBar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = dp(20) },
        )
    }

    private fun inRect(r: IntArray, e: android.view.MotionEvent): Boolean =
        e.x >= r[0] && e.x < r[2] && e.y >= r[1] && e.y < r[3]

    /** "Two screens": the channel list opens to pick the second channel. */
    private fun startPickingSecond() {
        val ch = queue.getOrNull(index) ?: return
        if (ch.kind != Kind.LIVE || inPip || dual != null) return
        playerView.hideController()
        openBrowser()
        if (!browserOpen) return
        pickingSecond = true
        Toast.makeText(this, "Pick a channel to watch beside “${ch.name}”", Toast.LENGTH_LONG).show()
    }

    /** A channel picked in the list: the second picture (starting two-screen mode), or the one being steered. */
    private fun pickForDual(list: List<Channel>, ch: Channel) {
        val at = list.indexOfFirst { it.id == ch.id }
        if (at < 0) return
        val d = dual
        if (d == null) {
            if (ch.id == queue.getOrNull(index)?.id) {
                Toast.makeText(this, "That one is already playing: pick another channel", Toast.LENGTH_SHORT).show()
                return
            }
            closeBrowser()
            startDual(list, at)
            return
        }
        closeBrowser()
        if (dualSecond) {
            d.play(list, at)
        } else {
            saveProgress()
            queue = list
            repo.playQueue = list
            index = at
            retried = false
            resumePositionMs = C.TIME_UNSET
            playCurrent(showInfo = false)
        }
        updateDualLabels()
        showDualHint()
    }

    private fun startDual(list: List<Channel>, at: Int) {
        if (dual != null) return
        // One picture's captions over two pictures would only confuse: off in two-screen mode.
        stopAutoCaptions()
        val d = DualPane(
            this,
            repo.http,
            headersFor = { c -> headersFor(c) },
            preferHls = repo.state.value.settings.preferHls,
            onChange = { updateDualLabels() },
        )
        root.addView(
            d.container,
            root.indexOfChild(playerView) + 1,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        dual = d
        dualSecond = false
        playerView.hideController()
        playerView.useController = false
        bannerView.visibility = View.GONE
        twoButton.visibility = View.GONE
        d.play(list, at)
        dualChanged()
        showDualHint()
        FreezeWatch.note("two screens on")
    }

    /**
     * Back to one screen. [keepSecond]: the second channel goes full screen (else the main one
     * stays); [replay] = false only tidies up (the player is closing).
     */
    private fun endDual(keepSecond: Boolean = dualSecond, replay: Boolean = true) {
        val d = dual ?: return
        val list = d.list
        val at = d.index
        val second = d.channel
        d.release()
        root.removeView(d.container)
        dual = null
        dualSecond = false
        handler.removeCallbacks(hideDualHint)
        for (v in listOf(dualFrame, dualLabelA, dualLabelB, dualHint, touchLayer, dualBar)) v.visibility = View.GONE
        playerView.useController = !inPip
        applyDualVolume()
        applyVideoArea()
        FreezeWatch.note("two screens off")
        if (!replay) return
        if (keepSecond && second != null) {
            saveProgress()
            rememberPrevious()
            queue = list
            repo.playQueue = list
            index = at
            retried = false
            resumePositionMs = C.TIME_UNSET
            playCurrent(showInfo = true)
        } else {
            queue.getOrNull(index)?.let {
                twoButton.visibility = if (it.kind == Kind.LIVE && !it.id.startsWith("cu:")) View.VISIBLE else View.GONE
                showBanner(it)
            }
        }
        playerView.requestFocus()
    }

    /** The picture being heard changed (or two-screen mode started). */
    private fun dualChanged() {
        applyDualVolume()
        applyVideoArea()
        updateDualLabels()
    }

    /** Only the picture being heard makes a sound. */
    private fun applyDualVolume() {
        val mainHeard = dual == null || !dualSecond
        exo?.volume = if (mainHeard) 1f else 0f
        vlc?.setMuted(!mainHeard)
        dual?.setHeard(!mainHeard)
    }

    /** The two pictures side by side (one above the other on an upright phone), the heard one framed. */
    private fun layoutDual(d: DualPane, w: Int, h: Int) {
        if (w == 0 || h == 0) return
        val gap = dp(if (tvLayout) 16 else 6)
        val none = intArrayOf(0, 0, 0, 0)
        val a: IntArray
        val b: IntArray
        if (browserOpen) {
            // The list beside (or below) the picture being steered; the other one waits out of sight.
            val area = if (h > w) {
                intArrayOf(0, 0, w, w * 9 / 16)
            } else {
                val panel = dp(BrowserLayout.panelWidthDp(tvLayout, browserShowGroups))
                fit16x9((panel + gap).coerceAtMost(w - dp(120)), gap, w - gap, h - gap)
            }
            a = if (dualSecond) none else area
            b = if (dualSecond) area else none
        } else if (h > w) {
            val ph = w * 9 / 16
            val top = ((h - 2 * ph - gap) / 2).coerceAtLeast(0)
            a = intArrayOf(0, top, w, top + ph)
            b = intArrayOf(0, top + ph + gap, w, top + 2 * ph + gap)
        } else {
            val pw = (w - 3 * gap) / 2
            val ph = pw * 9 / 16
            val top = ((h - ph) / 2).coerceAtLeast(0)
            a = intArrayOf(gap, top, gap + pw, top + ph)
            b = intArrayOf(2 * gap + pw, top, 2 * gap + 2 * pw, top + ph)
        }
        rectMain = a
        rectSecond = b
        for (v in listOfNotNull<View>(playerView, vlcLayout, messageView)) placeIn(v, a, w, h)
        placeIn(d.container, b, w, h)
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        vlc?.setScale(0)
        val heard = if (dualSecond) b else a
        val showing = !browserOpen
        // The frame sits just outside the heard picture.
        val pad = dp(4)
        placeIn(dualFrame, intArrayOf(heard[0] - pad, heard[1] - pad, heard[2] + pad, heard[3] + pad), w, h)
        dualFrame.visibility = if (showing) View.VISIBLE else View.GONE
        for ((label, r) in listOf(dualLabelA to a, dualLabelB to b)) {
            val lp = label.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = r[0] + dp(10)
            lp.bottomMargin = (h - r[3]) + dp(10)
            label.layoutParams = lp
            label.maxWidth = (r[2] - r[0] - dp(20)).coerceAtLeast(dp(80))
            label.visibility = if (showing && r[2] > r[0]) View.VISIBLE else View.GONE
        }
        touchLayer.visibility = if (showing && !tvLayout) View.VISIBLE else View.GONE
        dualBar.visibility = if (showing && !tvLayout) View.VISIBLE else View.GONE
    }

    /** A view filling [r] (left, top, right, bottom) of a [w]×[h] screen; an empty [r] hides it. */
    private fun placeIn(v: View, r: IntArray, w: Int, h: Int) {
        val lp = v.layoutParams as? FrameLayout.LayoutParams ?: return
        val m = if (r[2] <= r[0] || r[3] <= r[1]) intArrayOf(w, h, 0, 0) else intArrayOf(r[0], r[1], w - r[2], h - r[3])
        if (lp.leftMargin != m[0] || lp.topMargin != m[1] || lp.rightMargin != m[2] || lp.bottomMargin != m[3]) {
            lp.setMargins(m[0], m[1], m[2], m[3])
            v.layoutParams = lp
        }
    }

    /** The largest 16:9 area centred in the given box. */
    private fun fit16x9(l: Int, t: Int, r: Int, b: Int): IntArray {
        val bw = (r - l).coerceAtLeast(1)
        val bh = (b - t).coerceAtLeast(1)
        return if (bw * 9 <= bh * 16) {
            val hh = bw * 9 / 16
            intArrayOf(l, t + (bh - hh) / 2, r, t + (bh - hh) / 2 + hh)
        } else {
            val ww = bh * 16 / 9
            intArrayOf(l + (bw - ww) / 2, t, l + (bw - ww) / 2 + ww, b)
        }
    }

    /**
     * The remote in two-screen mode: Left/Right choose the picture heard, Up/Down (and CH±) change
     * its channel, OK opens the channel list for it, Back goes back to one screen (the one heard).
     */
    private fun dualKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (event.repeatCount == 0) endDual()
                    swallowUpKey = event.keyCode
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val second = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    if (second != dualSecond) {
                        dualSecond = second
                        dualChanged()
                    }
                    showDualHint()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (event.repeatCount == 0) dualZap(1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (event.repeatCount == 0) dualZap(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU -> {
                    if (event.repeatCount == 0) openBrowserForDual()
                    swallowUpKey = event.keyCode
                    return true
                }
                KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> {
                    showDualHint()
                    return true
                }
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE,
                -> return true
            }
        }
        // Volume and the rest: as usual.
        return super.dispatchKeyEvent(event)
    }

    /** The next or previous channel in the picture being steered. */
    private fun dualZap(delta: Int) {
        val d = dual ?: return
        if (dualSecond) {
            d.zap(delta)
        } else {
            if (queue.size < 2) return
            saveProgress()
            rememberPrevious()
            index = (index + delta).mod(queue.size)
            retried = false
            resumePositionMs = C.TIME_UNSET
            playCurrent(showInfo = false)
        }
        updateDualLabels()
        showDualHint()
    }

    /** The channel list, for the picture being steered. */
    private fun openBrowserForDual() {
        val d = dual ?: return
        openBrowser(if (dualSecond) d.channel else null)
    }

    /** Each picture's channel and what's on, the heard one marked. */
    private fun updateDualLabels() {
        val d = dual ?: return
        if (!::dualLabelA.isInitialized) return
        val now = System.currentTimeMillis()
        val epg = repo.epg.value
        fun line(ch: Channel?, heard: Boolean): String {
            if (ch == null) return ""
            val on = epg[ch.epgKey]?.firstOrNull { now >= it.start && now < it.stop }?.title
            return (if (heard) "🔊  " else "") + ch.name + (on?.let { "  ·  $it" } ?: "")
        }
        dualLabelA.text = line(queue.getOrNull(index), !dualSecond)
        dualLabelB.text = line(d.channel, dualSecond)
        dualLabelA.alpha = if (dualSecond) 0.75f else 1f
        dualLabelB.alpha = if (dualSecond) 1f else 0.75f
    }

    /** How to use two-screen mode, for a few seconds. */
    private fun showDualHint() {
        if (dual == null || browserOpen) return
        dualHint.visibility = View.VISIBLE
        handler.removeCallbacks(hideDualHint)
        handler.postDelayed(hideDualHint, 5_000)
    }

    /** User agent and referrer for a channel (its own, the playlist's, or the app's). */
    private fun headersFor(c: Channel): Pair<String, String> =
        c.userAgent.ifBlank { repo.currentPlaylist?.userAgent.orEmpty() }.ifBlank { Repository.DEFAULT_UA } to c.referrer

    /** Screenshot tests: two-screen mode with stand-in players. */
    internal fun previewDual(main: Player, second: Player, secondChannel: Channel, heardSecond: Boolean) {
        playerView.player = main
        val d = DualPane(this, repo.http, { c -> headersFor(c) }, false) { updateDualLabels() }
        root.addView(
            d.container,
            root.indexOfChild(playerView) + 1,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        dual = d
        dualSecond = heardSecond
        playerView.hideController()
        playerView.useController = false
        bannerView.visibility = View.GONE
        d.previewWith(second, listOf(secondChannel), 0)
        dualChanged()
        dualHint.visibility = View.VISIBLE
        handler.removeCallbacks(hideDualHint)
    }

    // ---- a lighter version when the stream keeps stalling (merged duplicates) ----

    private var wasReady = false
    private val stalls = ArrayDeque<Long>()

    private fun onStall() {
        val ch = queue.getOrNull(index) ?: return
        if (ch.kind != Kind.LIVE || dual != null) return
        val versions = repo.currentIndex.versions[ch.id] ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        stalls.addLast(now)
        while (stalls.isNotEmpty() && now - stalls.first() > 60_000L) stalls.removeFirst()
        if (stalls.size < 3) return
        fun base(u: String) = u.removeSuffix(".m3u8").removeSuffix(".ts")
        val playing = versions.indexOfFirst { base(it.url) == base(currentUrl) }.coerceAtLeast(0)
        val rank = app.falak.player.data.qualityRank(versions[playing])
        val next = versions.drop(playing + 1).firstOrNull { app.falak.player.data.qualityRank(it) < rank } ?: return
        stalls.clear()
        wasReady = false
        FreezeWatch.note("lighter version after stalls")
        val label = next.tidyQuality ?: app.falak.player.data.Tidy.qualityLabel(next.name)
        showMessage("Switching to a lighter version" + (label?.let { " ($it)" } ?: "") + " for smoother playback…")
        retried = false
        handler.post { playCurrent(showInfo = false, overrideUrl = next.url) }
    }

    // ---- service report (Settings → Playlists & TV guide) ----

    /** When the live channel being started was chosen (elapsedRealtime); 0 once it plays or fails. */
    private var serviceStartAt = 0L
    private var serviceStartFor: String? = null
    /** Live TV watched since it was last written down. */
    private var serviceWatchedMs = 0L
    private var serviceTicks = 0
    private var lastSeekAt = 0L

    /** Live channels only: films and episodes start and stop to load differently (seeking, big files). */
    private fun countsForService(ch: Channel?): Boolean = ch != null && ch.kind == Kind.LIVE && !ch.id.startsWith("cu:") && dual == null

    private fun serviceLog(change: (app.falak.player.data.ServiceLog) -> app.falak.player.data.ServiceLog) {
        val id = repo.currentPlaylist?.id ?: return
        app.falak.player.data.ServiceLogStore.update(filesDir, id, change)
    }

    private fun serviceStarting(ch: Channel) {
        if (!countsForService(ch)) {
            serviceStartAt = 0L
            return
        }
        // Tried again in another format or from a backup copy: still the same start.
        if (ch.id != serviceStartFor || serviceStartAt == 0L) {
            serviceStartFor = ch.id
            serviceStartAt = android.os.SystemClock.elapsedRealtime()
        }
    }

    private fun serviceReady() {
        val at = serviceStartAt
        if (at == 0L) return
        serviceStartAt = 0L
        val took = android.os.SystemClock.elapsedRealtime() - at
        if (took in 0L..60_000L) serviceLog { app.falak.player.data.ServiceReport.add(it, System.currentTimeMillis(), startMs = took) }
    }

    private fun serviceStall() {
        if (!countsForService(queue.getOrNull(index))) return
        if (android.os.SystemClock.elapsedRealtime() - lastSeekAt < 3_000L) return
        serviceLog { app.falak.player.data.ServiceReport.add(it, System.currentTimeMillis(), stalls = 1) }
    }

    private fun serviceFailed(ch: Channel) {
        serviceStartAt = 0L
        if (!countsForService(ch)) return
        serviceLog { app.falak.player.data.ServiceReport.add(it, System.currentTimeMillis(), failed = ch.name) }
    }

    /** Once a second: live TV watched, written down every minute (and to the file every 5). */
    private fun serviceTick() {
        if (player?.isPlaying == true && countsForService(queue.getOrNull(index))) serviceWatchedMs += 1_000L
        serviceTicks++
        if (serviceTicks % 60 == 0) flushWatched()
        if (serviceTicks % 300 == 0) {
            val dir = filesDir
            Thread { app.falak.player.data.ServiceLogStore.save(dir) }.start()
        }
    }

    private fun flushWatched() {
        val ms = serviceWatchedMs
        if (ms <= 0L) return
        serviceWatchedMs = 0L
        serviceLog { app.falak.player.data.ServiceReport.add(it, System.currentTimeMillis(), watchedMs = ms) }
    }

    // ---- kids screen time ----

    /** Kids' time ran out but what's on ends soon: it finishes, then this stops playing. */
    private var kidsFinishing: app.falak.player.data.KidsTime.Block? = null
    /** For a live programme: when it ends (epoch ms); 0 = when the film or episode ends. */
    private var kidsFinishAt = 0L
    private var kidsWarned = false

    /** Once a second in the Kids profile: time watched, "10 minutes left", and time up or bedtime. */
    private fun kidsTick() {
        if (!repo.kidsActive) return
        val p = player
        if (p?.isPlaying == true) app.falak.player.data.KidsClock.watched(this, 1_000L)
        val st = app.falak.player.data.KidsClock.state(this, repo.state.value.settings)
        val left = st.minutesLeft
        if (left != null && left in 1..10 && !kidsWarned && st.block == null) {
            kidsWarned = true
            android.widget.Toast.makeText(this, if (left == 1) "1 minute of watching left today" else "$left minutes of watching left today", android.widget.Toast.LENGTH_LONG).show()
        }
        val block = st.block ?: return
        val waiting = kidsFinishing
        if (waiting != null) {
            if (kidsFinishAt > 0L && System.currentTimeMillis() >= kidsFinishAt) kidsStop(waiting)
            return
        }
        // What's on ends soon: it finishes first.
        val ch = queue.getOrNull(index)
        val wall = System.currentTimeMillis()
        if (ch != null && p != null) {
            if (ch.kind != Kind.LIVE || ch.id.startsWith("cu:")) {
                val rest = p.duration - p.currentPosition
                if (p.duration > 0 && rest in 0L..app.falak.player.data.KidsTime.GRACE_MS) {
                    kidsFinishing = block
                    kidsFinishAt = 0L
                    android.widget.Toast.makeText(this, "That's all after this one", android.widget.Toast.LENGTH_LONG).show()
                    return
                }
            } else {
                val prog = repo.epg.value[ch.epgKey]?.firstOrNull { wall >= it.start && wall < it.stop }
                if (prog != null && prog.stop - wall in 0L..app.falak.player.data.KidsTime.GRACE_MS) {
                    kidsFinishing = block
                    kidsFinishAt = prog.stop
                    android.widget.Toast.makeText(this, "That's all after “${prog.title}”", android.widget.Toast.LENGTH_LONG).show()
                    return
                }
            }
        }
        kidsStop(block)
    }

    private fun kidsStop(block: app.falak.player.data.KidsTime.Block) {
        kidsFinishing = null
        FreezeWatch.note("kids screen time: stopped")
        app.falak.player.data.KidsClock.flush(this)
        saveProgress()
        app.falak.player.data.KidsClock.stop(block)
        finish()
    }

    // ---- sleep timer ----

    /** Stopped by the sleep timer: the message is up and OK carries on. */
    private var sleptByTimer = false

    /** The fade's volume as last set (1 = full). */
    private var sleepVolume = 1f

    private fun updateSleepButton() {
        if (!::sleepButton.isInitialized) return
        val label = SleepTimer.buttonLabel(android.os.SystemClock.elapsedRealtime())
        if (sleepButton.text?.toString() != label) setPillLabel(sleepButton, label)
    }

    private fun showSleepDialog() {
        val ch = queue.getOrNull(index) ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        val choices = ArrayList<Pair<String, () -> Unit>>()
        if (SleepTimer.isOn) choices += "Off" to { SleepTimer.cancel() }
        val live = ch.kind == Kind.LIVE && !ch.id.startsWith("cu:")
        if (!live) {
            val what = when (ch.kind) {
                Kind.SERIES -> "episode"
                Kind.MOVIE -> "film"
                else -> "programme"
            }
            choices += "At the end of this $what" to { SleepTimer.endOf(ch.id, "End of this $what") }
        } else {
            // The programme on now, from the guide: stop when it ends.
            val wall = System.currentTimeMillis()
            val prog = repo.epg.value[ch.epgKey]?.firstOrNull { wall >= it.start && wall < it.stop }
            if (prog != null && prog.stop - wall in 2 * 60_000L..6 * 3_600_000L) {
                choices += "At the end of “${prog.title}” (${time(prog.stop)})" to {
                    SleepTimer.at(android.os.SystemClock.elapsedRealtime() + (prog.stop - System.currentTimeMillis()), "End of the programme")
                }
            }
        }
        for (m in listOf(15, 30, 45, 60, 90, 120)) {
            val label = when (m) {
                60 -> "In 1 hour"
                90 -> "In 1½ hours"
                120 -> "In 2 hours"
                else -> "In $m minutes"
            }
            choices += label to { SleepTimer.after(m, android.os.SystemClock.elapsedRealtime()) }
        }
        val title = when (SleepTimer.mode) {
            SleepTimer.Mode.OFF -> "Sleep timer"
            SleepTimer.Mode.END_OF_ITEM -> "Sleep timer · ${SleepTimer.label}"
            SleepTimer.Mode.AT_TIME -> "Sleep timer · stops at ${time(System.currentTimeMillis() + (SleepTimer.remaining(now) ?: 0L))}"
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setItems(choices.map { it.first }.toTypedArray()) { d, which ->
                d.dismiss()
                choices[which].second()
                if (dual == null && !SleepTimer.isOn) setSleepVolume(1f)
                updateSleepButton()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Once a second: the button's time left, the fade over the last minute, and the stop. */
    private fun sleepTick() {
        updateSleepButton()
        val now = android.os.SystemClock.elapsedRealtime()
        if (!SleepTimer.isOn) {
            if (dual == null) setSleepVolume(1f)
            return
        }
        // Set every second while it runs: a new stream (or player) starts at full volume.
        if (dual == null) setSleepVolume(SleepTimer.volume(now), always = true)
        if (SleepTimer.due(now)) sleepStop()
    }

    private fun setSleepVolume(v: Float, always: Boolean = false) {
        if (v == sleepVolume && !always) return
        sleepVolume = v
        exo?.volume = v
        vlc?.setLevel(v)
    }

    /** Time's up: what plays stops (a live channel lets go of its connection) and the screen may sleep. */
    private fun sleepStop() {
        SleepTimer.cancel()
        FreezeWatch.note("sleep timer stopped playback")
        saveProgress()
        val ch = queue.getOrNull(index)
        val p = player
        if (ch != null && ch.kind == Kind.LIVE && !ch.id.startsWith("cu:")) p?.stop() else p?.pause()
        stopAutoCaptions()
        if (dual == null) setSleepVolume(1f)
        sleptByTimer = true
        playerView.keepScreenOn = false
        playerView.hideController()
        showMessage("Stopped by the sleep timer\n${if (tvLayout) "OK" else "Play"} carries on")
        updateSleepButton()
    }

    private fun wakeFromSleep() {
        sleptByTimer = false
        playerView.keepScreenOn = true
        hideMessage()
        val ch = queue.getOrNull(index) ?: return
        if (ch.kind == Kind.LIVE && !ch.id.startsWith("cu:")) {
            playCurrent(showInfo = true)
        } else {
            player?.play()
            resumeAutoCaptions()
        }
    }

    // ---- start over ----

    /** Live, with replay, and the show started 3+ minutes ago: offer it from the start for 12 seconds. */
    private fun offerStartOver(ch: Channel) {
        handler.removeCallbacks(hideStartOver)
        startOverOffer = null
        if (ch.kind != Kind.LIVE || !ch.hasCatchup || ch.id.startsWith("cu:") || inPip) return
        val now = System.currentTimeMillis()
        val prog = repo.epg.value[ch.epgKey]?.firstOrNull { now >= it.start && now < it.stop } ?: return
        if (now - prog.start < 3 * 60_000L || !app.falak.player.data.Catchup.canReplay(ch, prog, now)) return
        startOverOffer = ch to prog
        handler.postDelayed(hideStartOver, 12_000)
    }

    /** The show on now, from its start (a replay); Back or "Back to live" returns to the channel. */
    private fun startOver() {
        val (ch, prog) = startOverOffer ?: return
        startOverOffer = null
        handler.removeCallbacks(hideStartOver)
        val now = System.currentTimeMillis()
        val url = try {
            app.falak.player.data.Catchup.url(ch, prog, repo.openLibrary?.serverTz.orEmpty(), now)
        } catch (e: Exception) {
            null
        }
        if (url == null) {
            toast("This programme can't be replayed")
            return
        }
        val item = app.falak.player.data.Catchup.asItem(ch, prog, url)
        saveProgress()
        liveBeforeStartOver = queue to index
        queue = listOf(item)
        repo.playQueue = queue
        index = 0
        retried = false
        resumePositionMs = C.TIME_UNSET
        FreezeWatch.note("start over")
        playCurrent(showInfo = true)
    }

    /** From a show watched from its start, back to the live channel and its list. */
    private fun backToLive() {
        val (list, at) = liveBeforeStartOver ?: return
        liveBeforeStartOver = null
        queue = list
        repo.playQueue = list
        index = at.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        retried = false
        resumePositionMs = C.TIME_UNSET
        playCurrent(showInfo = true)
    }

    // ---- Subscription watch: why a channel was refused ----

    /** The stream that failed for good, while its explanation is being looked up. */
    private var failedUrl: String? = null

    /**
     * Xtream logins: asks the provider about the account, and when it explains the refusal (every
     * connection in use, the subscription ended or suspended) says so instead of a bare error.
     */
    private fun explainRefusal() {
        // A combined playlist: the account of the playlist this channel came from.
        val pl = repo.currentPlaylist?.let { cur -> queue.getOrNull(index)?.let { repo.sourceOf(cur, it.id) } ?: cur } ?: return
        val api = repo.xtreamFor(pl) ?: return
        val url = currentUrl
        failedUrl = url
        lifecycleScope.launch {
            val read = withContext(Dispatchers.IO) { runCatching { api.account() }.getOrNull() } ?: return@launch
            val a = read.copy(who = app.falak.player.data.Accounts.owner(pl))
            app.falak.player.data.AccountStore.put(applicationContext, pl.id, a)
            val format = android.text.format.DateFormat.getMediumDateFormat(this@PlayerActivity)
            val why = app.falak.player.data.Accounts.refusal(a, System.currentTimeMillis()) { format.format(java.util.Date(it)) }
                ?: return@launch
            if (failedUrl == url && !isFinishing) showMessage(why)
        }
    }

    // ---- Home's "For this hour" ----

    /** The live channel still playing two minutes on: noted on this device (never sent anywhere). */
    private val noteHabit = Runnable {
        val ch = queue.getOrNull(index)
        val pl = repo.currentPlaylist
        if (ch != null && pl != null && ch.kind == Kind.LIVE && !ch.id.startsWith("cu:") && player?.isPlaying == true) {
            val key = repo.state.value.mine(pl.id)
            val dir = filesDir
            Thread { Habits.record(dir, key, ch.id) }.start()
        }
    }

    // ---- Auto captions ----

    /** Auto captions can be offered for what's playing: a live channel, in ExoPlayer, one picture. */
    private fun autoCaptionsOffered(): Boolean {
        val ch = queue.getOrNull(index) ?: return false
        return ch.kind == Kind.LIVE && !ch.id.startsWith("cu:") && exo != null && dual == null
    }

    /** Auto captions were chosen for [ch] (and the speech model is here). */
    private fun autoCaptionsChosen(ch: Channel): Boolean {
        if (ch.kind != Kind.LIVE || ch.id.startsWith("cu:")) return false
        val key = trackKey() ?: return false
        return trackMemory.get(key)?.text == "auto" && SpeechModel.isReady(this)
    }

    /** The speech engine, made when first needed. */
    private fun captionsEngine(): LiveCaptions =
        liveCaptions ?: LiveCaptions(SpeechModel.dir(this), { showAutoCaption(it) }, { onAutoCaptionsProblem(it) })
            .also { liveCaptions = it }

    private fun startAutoCaptions() {
        if (dual != null || inPip || !exoCaptions) return
        // Captions of the channel's own already found: those, never auto captions.
        if (countTracks(C.TRACK_TYPE_TEXT) > 0) {
            providerCaptionsTakeOver()
            return
        }
        captionsEngine().start()
        autoCapsOn = true
        autoCapsLateMs = null
    }

    private fun stopAutoCaptions() {
        autoCapsOn = false
        liveCaptions?.stop()
        handler.removeCallbacks(clearAutoCaps)
        autoCapsWords = null
        renderAutoCaption()
    }

    /** Back from the little window: captions again, if this channel has them on. */
    private fun resumeAutoCaptions() {
        val ch = queue.getOrNull(index) ?: return
        if (!autoCapsOn && exoCaptions && autoCaptionsChosen(ch)) startAutoCaptions()
    }

    /** Subtitles → Auto captions: on for this channel (after the one-time download). */
    private fun chooseAutoCaptions() {
        val key = trackKey() ?: return
        if (!SpeechModel.isReady(this)) {
            askForSpeechModel(key)
            return
        }
        trackMemory.setText(key, "auto")
        // English only: say so when the stream says its sound is in another language.
        val lang = exo?.currentTracks?.groups
            ?.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).language } }
            ?.lowercase()
        if (lang != null && lang != "und" && !lang.startsWith("en")) {
            toast("Auto captions understand English only")
        }
        retried = false
        playCurrent(showInfo = false)
    }

    private fun askForSpeechModel(key: String) {
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Auto captions (English)")
            .setMessage(
                "Captions for what's said on English channels, made on this device as the channel plays " +
                    "(about a second behind the speech). Nothing you watch or hear is sent anywhere.\n\n" +
                    "The first time, the speech model needs a one-time download (${SpeechModel.SIZE_LABEL}). " +
                    "Channels with captions of their own keep those.",
            )
            .setPositiveButton("Download") { _, _ ->
                autoCapsPending = key
                SpeechModel.download(this, repo.http)
                toast("Downloading the speech model. Keep watching: captions start when it's ready")
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    /** The speech model arrived (or couldn't): start the captions that were waiting for it. */
    private fun onSpeechModel(state: SpeechModel.State) {
        val key = autoCapsPending ?: return
        when (state) {
            is SpeechModel.State.Ready -> {
                autoCapsPending = null
                trackMemory.setText(key, "auto")
                if (key == trackKey() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !isFinishing) {
                    retried = false
                    playCurrent(showInfo = false)
                    toast("Auto captions are on for this channel")
                }
            }
            is SpeechModel.State.Failed -> {
                autoCapsPending = null
                toast("Couldn't download the speech model: ${state.message}")
            }
            else -> Unit
        }
    }

    /** The channel has captions of its own: those are shown, and auto captions stop. */
    private fun providerCaptionsTakeOver() {
        stopAutoCaptions()
        val p = exo
        val group = p?.currentTracks?.groups?.firstOrNull { g ->
            g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { g.isTrackSupported(it) && isReal(g.getTrackFormat(it)) }
        }
        if (p != null && group != null) {
            val i = (0 until group.length).first { group.isTrackSupported(it) && isReal(group.getTrackFormat(it)) }
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                .build()
            val f = group.getTrackFormat(i)
            rememberTrack(C.TRACK_TYPE_TEXT, TrackMatch.valueFor(f.language, trackLabel(f)))
            watchCaptions()
            toast("This channel has its own captions, so those are shown instead of auto captions")
        } else {
            // Teletext only (VLC draws it): no auto captions here from now on either.
            rememberTrack(C.TRACK_TYPE_TEXT, "off")
            toast("This channel has its own subtitles: pick them with the Subtitles button")
        }
    }

    /**
     * Words from the speech engine (null: clear), each with when it's heard. They fade 6 seconds
     * after the last one shows.
     */
    private fun showAutoCaption(words: List<Word>?) {
        if (!::autoCapsView.isInitialized) return
        handler.removeCallbacks(clearAutoCaps)
        val before = autoCapsWords
        autoCapsWords = words?.takeIf { autoCapsOn && it.isNotEmpty() }
        val nowUs = liveCaptions?.heardNowUs(exo?.isPlaying == true)
        // How late the newest word came out of the engine, against when it's heard (smoothed;
        // early counts as on time). The caption style panel shows it.
        val newest = autoCapsWords?.lastOrNull { it.atUs != Word.NOW }?.atUs
        if (nowUs != null && newest != null && newest != before?.lastOrNull { it.atUs != Word.NOW }?.atUs) {
            val lateMs = ((nowUs - newest) / 1000.0).coerceAtLeast(0.0)
            autoCapsLateMs = autoCapsLateMs?.let { it * 0.8 + lateMs * 0.2 } ?: lateMs
        }
        renderAutoCaption()
        val shown = autoCapsWords ?: return
        // In time with the speech, the last word may still be on its way to being heard.
        val lastUs = shown.last().atUs
        val waitMs = if (exoSynced && nowUs != null && lastUs != Word.NOW) ((lastUs - nowUs) / 1000).coerceIn(0L, 3_000L) else 0L
        handler.postDelayed(clearAutoCaps, 6_000 + waitMs)
    }

    /**
     * Auto captions on screen as chosen: in time with the speech, the words heard by now (the next
     * one shows when it's heard); as many lines as fit the picture's width (never more than the
     * lines chosen), in their place. While the caption style panel is open and nothing is being
     * said, its sample shows instead.
     */
    private fun renderAutoCaption() {
        if (!::autoCapsView.isInitialized) return
        handler.removeCallbacks(nextAutoCaption)
        if (styleOpen) {
            styleAuto = autoCaptionsInPanel()
            styleNote = captionNote()
        }
        val s = repo.state.value.settings
        val words = autoCapsWords?.takeIf { autoCapsOn }
        val nowUs = if (words != null && exoSynced) liveCaptions?.heardNowUs(exo?.isPlaying == true) else null
        val (heard, nextUs) = if (words != null) CaptionText.showing(words, nowUs) else (null to null)
        if (nextUs != null && nowUs != null) handler.postDelayed(nextAutoCaption, ((nextUs - nowUs) / 1000).coerceIn(15L, 500L))
        val live = heard?.takeIf { it.isNotEmpty() }?.let { CaptionText.join(it) }
        val sample = if (live == null && styleOpen) captionSample() else null
        val text = live ?: sample?.first
        val auto = live != null || sample?.second == true
        val width = capsLineWidth * 0.97f
        val shown = text?.let {
            CaptionText.rollUp(it, if (auto) CaptionLayout.lines(s.autoCapsLines) else 1) { line ->
                line.length <= CaptionLayout.MAX_LINE_CHARS && (width <= 0f || capsPaint.measureText(line) <= width)
            }
        }
        if (shown == null) {
            autoCapsView.setCues(emptyList())
            return
        }
        val cue = androidx.media3.common.text.Cue.Builder().setText(shown)
        if (auto) {
            CaptionLayout.line(CaptionLayout.position(s.autoCapsPosition))?.let { (line, anchor) ->
                cue.setLine(line, androidx.media3.common.text.Cue.LINE_TYPE_FRACTION).setLineAnchor(anchor)
            }
        }
        autoCapsView.setCues(listOf(cue.build()))
    }

    /** The caption style panel shows auto captions' place and lines: they're on, or could be for this channel. */
    private fun autoCaptionsInPanel(): Boolean =
        autoCapsOn || (autoCaptionsOffered() && countTracks(C.TRACK_TYPE_TEXT) == 0)

    /** The line under the caption style panel's title: VLC's look waits; how auto captions keep time. */
    private fun captionNote(): String? {
        if (vlc != null) return "This stream plays in VLC: its subtitles take the new look from the next video."
        if (!autoCapsOn) return null
        val late = autoCapsLateMs ?: return null
        if (late < 150) return "Auto captions: in time with the speech"
        val behind = String.format(java.util.Locale.US, "%.1f", late / 1000)
        return "Auto captions: about $behind s behind the speech" +
            if (exoSynced) "" else " (Settings → Subtitle style: In time with the speech)"
    }

    /** The panel's sample while nothing is said: its words, and whether it's auto captions'; null: none. */
    private fun captionSample(): Pair<String, Boolean>? = when {
        autoCaptionsInPanel() -> CaptionLayout.SAMPLE to true
        // VLC draws the stream's captions itself, and the stream's own words are the best sample.
        vlc != null || streamCuesShowing -> null
        else -> CaptionLayout.STREAM_SAMPLE to false
    }

    // ---- Caption style: the look of captions (and auto captions' place and lines), as you watch ----

    private fun showCaptionStyle() {
        if (inPip || styleOpen || menuOpen || browserOpen) return
        handler.removeCallbacks(hideBrowserView)
        playerView.hideController()
        playerView.useController = false
        bannerView.visibility = View.GONE
        styleAuto = autoCaptionsInPanel()
        styleNote = captionNote()
        browserView.visibility = View.VISIBLE
        styleOpen = true
        browserView.requestFocus()
        renderAutoCaption()
    }

    private fun closeCaptionStyle() {
        if (!styleOpen) return
        styleOpen = false
        handler.postDelayed(hideBrowserView, 300)
        playerView.useController = !inPip
        playerView.requestFocus()
        renderAutoCaption()
    }

    /** A change in the panel: saved, and on screen at once. */
    private fun changeCaptionStyle(change: (Settings) -> Settings) {
        repo.updateSettings(change)
        applySubtitleStyle()
    }

    private fun onAutoCaptionsProblem(problem: LiveCaptions.Problem) {
        stopAutoCaptions()
        toast(
            when (problem) {
                LiveCaptions.Problem.UNAVAILABLE -> "Auto captions can't run on this device"
                LiveCaptions.Problem.TOO_SLOW -> "This device can't keep up with auto captions on this channel, so they're off for now"
            },
        )
    }

    /** Screenshot tests: auto captions over the picture. */
    @androidx.annotation.VisibleForTesting
    internal fun previewAutoCaptions(text: String) {
        playerView.hideController()
        autoCapsOn = true
        showAutoCaption(Word.of(text))
    }

    /** Screenshot tests: the caption style panel. */
    @androidx.annotation.VisibleForTesting
    internal fun previewCaptionStyle() = showCaptionStyle()

    /** Screenshot tests: a pick in the caption style panel. */
    @androidx.annotation.VisibleForTesting
    internal fun previewCaptionChange(change: (Settings) -> Settings) = changeCaptionStyle(change)

    /** Screenshot tests: a picture of this shape ([ratio]: width / height), in the Fit/Fill/Zoom mode [aspectMode]. */
    @androidx.annotation.VisibleForTesting
    internal fun previewPicture(ratio: Float, aspectMode: Int = 0) {
        aspect = aspectMode
        playerView.resizeMode = resizeModes[aspect]
        playerView.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)?.setAspectRatio(ratio)
    }

    /** Screenshot tests: the stream's own captions saying [text]. */
    @androidx.annotation.VisibleForTesting
    internal fun previewStreamCaptions(text: String) {
        playerView.hideController()
        streamCuesShowing = text.isNotEmpty()
        playerView.subtitleView?.setCues(
            if (text.isEmpty()) emptyList() else listOf(androidx.media3.common.text.Cue.Builder().setText(text).build()),
        )
    }

    /** Tests: the two caption views (the stream's, auto captions'). */
    @androidx.annotation.VisibleForTesting
    internal fun captionViews(): Pair<androidx.media3.ui.SubtitleView?, androidx.media3.ui.SubtitleView> = playerView.subtitleView to autoCapsView

    // ---- Listen mode ----

    private fun screenOn(): Boolean =
        getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: true

    /**
     * Phones with Listen mode on, leaving the player (Home, another app) or turning the screen off
     * while something plays: the sound carries on in ListenService, and this player lets go of the
     * stream. Coming back (onStart) takes it back. False when it doesn't apply.
     */
    private fun startListening(): Boolean {
        if (listening) return true
        if (isFinishing) return false
        val ch = queue.getOrNull(index) ?: return false
        val p = player ?: return false
        if (!repo.state.value.settings.listenMode || isTvDevice || inPip || dual != null || handingOff || repo.kidsActive) return false
        if (p.playerError != null || !p.playWhenReady || currentUrl.isBlank()) return false
        val live = ch.kind == Kind.LIVE
        val now = System.currentTimeMillis()
        val onNow = if (live) repo.epg.value[ch.epgKey]?.firstOrNull { now >= it.start && now < it.stop }?.title.orEmpty() else ""
        val language = exo?.currentTracks?.groups
            ?.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).language } }
        val (ua, referrer) = headersFor(ch)
        val item = app.falak.player.ListenService.Item(
            url = currentUrl,
            title = ch.name,
            subtitle = onNow,
            userAgent = ua,
            referrer = referrer,
            position = if (live) C.TIME_UNSET else p.currentPosition,
            live = live,
            audioLanguage = language,
            index = index,
        )
        if (!app.falak.player.ListenService.start(this, item)) return false
        listening = true
        FreezeWatch.note("listen mode")
        // The service connects once this player has let go (one connection at a time).
        releasePlayer()
        return true
    }

    // ---- mini player ----

    /**
     * Back from a live channel that is playing: it carries on small over the lists (Settings →
     * Mini player). This player lets go of the stream first, as many providers allow one
     * connection at a time.
     */
    private fun offerMini() {
        val ch = queue.getOrNull(index) ?: return
        val p = player ?: return
        if (!repo.state.value.settings.miniPlayer || inPip || dual != null) return
        if (ch.kind != Kind.LIVE || ch.id.startsWith("cu:") || p.playerError != null || !p.playWhenReady) return
        val list = queue
        val at = index
        val ua = headersFor(ch).first
        releasePlayer()
        MiniPlayerState.show(list, at, ua, repo.currentPlaylist?.id)
    }

    // ---- programme reminders ----

    /** "Watch now" on a reminder: its channel, with its group to zap through. */
    private fun watchAlert(a: ProgramAlert) {
        ProgramAlerts.dismiss()
        val idx = repo.currentIndex
        val ch = idx.byId[a.channelId]
        if (ch == null || repo.currentPlaylist?.id != a.playlistId || ch.group in repo.excludedGroups) {
            Toast.makeText(this, "“${a.channelName}” isn't in this playlist", Toast.LENGTH_SHORT).show()
            return
        }
        if (dual != null) endDual(keepSecond = false, replay = false)
        if (browserOpen) closeBrowser()
        val list = idx.groupsOf(Kind.LIVE)[ch.group] ?: listOf(ch)
        saveProgress()
        rememberPrevious()
        queue = list
        repo.playQueue = list
        index = list.indexOfFirst { it.id == ch.id }.coerceAtLeast(0)
        retried = false
        resumePositionMs = C.TIME_UNSET
        repo.currentPlaylist?.let { repo.addRecent(it.id, ch.id) }
        playCurrent(showInfo = true)
    }

    // ---- audio and subtitles picked by hand, remembered ----

    /** Where this item's choices are kept: the series for an episode, else the channel or film. */
    private fun trackKey(): String? {
        val ch = queue.getOrNull(index) ?: return null
        if (ch.id.startsWith("cu:")) return null
        return showKey(ch) ?: ((repo.currentPlaylist?.id ?: "") + "|" + ch.id)
    }

    private fun rememberTrack(type: Int, value: String) {
        val key = trackKey() ?: return
        if (type == C.TRACK_TYPE_AUDIO) trackMemory.setAudio(key, value) else if (type == C.TRACK_TYPE_TEXT) trackMemory.setText(key, value)
    }

    /** ExoPlayer: the audio and subtitles picked last time for this item, once its tracks are known. */
    private fun applyRememberedTracks() {
        val p = exo ?: return
        if (audioMemoryDone && textMemoryDone) return
        val saved = trackKey()?.let { trackMemory.get(it) }
        if (saved == null) {
            audioMemoryDone = true
            textMemoryDone = true
            return
        }
        val groups = p.currentTracks.groups
        if (!audioMemoryDone) {
            val want = saved.audio
            if (want == null) {
                audioMemoryDone = true
            } else {
                val cands = ArrayList<Pair<androidx.media3.common.Tracks.Group, Int>>()
                for (g in groups) if (g.type == C.TRACK_TYPE_AUDIO) for (i in 0 until g.length) if (g.isTrackSupported(i)) cands += g to i
                if (cands.isNotEmpty()) {
                    audioMemoryDone = true
                    val at = TrackMatch.pick(want, cands.map { (g, i) -> g.getTrackFormat(i).let { it.language to trackLabel(it) } })
                    if (at >= 0) {
                        val (g, i) = cands[at]
                        if (!g.isTrackSelected(i)) {
                            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                                .build()
                        }
                    }
                }
            }
        }
        if (!textMemoryDone) {
            val want = saved.text
            when {
                want == null -> textMemoryDone = true
                want == "off" -> {
                    textMemoryDone = true
                    autoSubtitlesDone = true
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                }
                else -> {
                    val cands = ArrayList<Pair<androidx.media3.common.Tracks.Group, Int>>()
                    for (g in groups) {
                        if (g.type != C.TRACK_TYPE_TEXT) continue
                        for (i in 0 until g.length) if (g.isTrackSupported(i) && isReal(g.getTrackFormat(i))) cands += g to i
                    }
                    if (cands.isNotEmpty()) {
                        val at = TrackMatch.pick(want, cands.map { (g, i) -> g.getTrackFormat(i).let { it.language to trackLabel(it) } })
                        if (at >= 0) {
                            textMemoryDone = true
                            autoSubtitlesDone = true
                            val (g, i) = cands[at]
                            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                                .build()
                            watchCaptions()
                        }
                    }
                }
            }
        }
    }

    /** VLC: the same, by track name (VLC lists names like "Arabic - [ara]"). */
    private fun applyRememberedVlc(v: VlcPlayer) {
        if (audioMemoryDone && textMemoryDone) return
        val saved = trackKey()?.let { trackMemory.get(it) }
        if (saved == null) {
            audioMemoryDone = true
            textMemoryDone = true
            return
        }
        if (!audioMemoryDone) {
            val want = saved.audio
            val tracks = v.audioTracks()
            if (want == null) {
                audioMemoryDone = true
            } else if (tracks.isNotEmpty()) {
                audioMemoryDone = true
                val at = TrackMatch.pick(want, tracks.map { null to it.name })
                if (at >= 0 && tracks[at].id != v.selectedAudio) v.selectAudio(tracks[at].id)
            }
        }
        if (!textMemoryDone) {
            val want = saved.text
            val tracks = v.subtitleTracks()
            when {
                want == null -> textMemoryDone = true
                want == "off" -> {
                    textMemoryDone = true
                    autoSubtitlesDone = true
                    if (v.selectedSubtitle >= 0 && !wantVlcSubtitles) v.selectSubtitle(-1)
                }
                tracks.isNotEmpty() -> {
                    val at = TrackMatch.pick(want, tracks.map { null to it.name })
                    if (at >= 0) {
                        textMemoryDone = true
                        autoSubtitlesDone = true
                        if (tracks[at].id != v.selectedSubtitle) v.selectSubtitle(tracks[at].id)
                    }
                }
            }
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
