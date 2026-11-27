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
import app.falak.player.data.done
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
import app.falak.player.player.NoPicture
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
import app.falak.player.data.PlayerInfo
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
import app.falak.player.autoSync

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_INDEX = "index"
        /** The picture-in-picture window's buttons (8.50). */
        private const val PIP_ACTION = "app.falak.player.PIP"
        private const val PIP_TOGGLE = 1
        private const val PIP_NEXT = 2
        private const val PIP_PREVIOUS = 3
        /** The half-time hop's button (8.42). */
        private const val HOP_LABEL = "Back in…"
        /** "Still watching?": seconds to answer before it stops. */
        private const val STILL_ANSWER_S = 60
        private const val SEEK_STEP_MS = 10_000L
        /** Pictures at once, the main one included (8.33). */
        private const val MAX_SCREENS = 4
        private const val LEVEL_BRIGHTNESS = 1
        private const val LEVEL_VOLUME = 2
        private const val ASPECT_FIT = 0
        private const val ASPECT_ZOOM = 2
        /** The Up next card comes up this long before an episode ends. */
        private const val UP_NEXT_MS = 20_000L

        /** Added to a show's key (see showKey) for where its credits start, kept with the intros. */
        private const val OUTRO_KEY = "|outro"
        /** …and the next episode starts after this many seconds. */
        private const val COUNTDOWN_S = 10
        /** A forward jump that starts this early in an episode can be the intro being skipped. */
        private const val INTRO_WINDOW_MS = 6 * 60_000L
        /** "S01E02", "S1 E2", "Season 1", "Episode 2", "1x02" and everything after it. */
        private val EPISODE_MARK = Regex("(?i)\\s*[-–|:·]?\\s*(\\bS\\d{1,2}\\s*[ .x-]?\\s*E\\d{1,3}\\b|\\bseason\\s*\\d+|\\bepisode\\s*\\d+|\\b\\d{1,2}x\\d{2,3}\\b).*$")

        /** Automatic mode: items ExoPlayer couldn't open this session; they go straight to VLC. */
        private val needsVlc = HashSet<String>()
        /** Sound for this long with a picture never drawn = no picture (8.52: was 6 s). */
        private const val NO_PICTURE_MS = 4_000L

        /** Player picked in the player for this session (overrides Settings until the app restarts). */
        private var sessionEngine: Engine? = null
    }

    private lateinit var playerView: PlayerView
    private lateinit var bannerView: TextView
    private lateinit var messageView: TextView
    private lateinit var seekView: TextView
    private lateinit var aspectButton: TextView

    /** Audio and subtitles in one button (one panel with both lists). */
    private lateinit var tracksButton: TextView
    private lateinit var toolbar: FrameLayout
    private lateinit var browseButton: TextView
    private lateinit var engineButton: TextView
    private lateinit var speedButton: TextView
    private lateinit var sleepButton: TextView

    /** The star before the name at the top of the controls: Favorites. */
    private lateinit var favButton: TextView
    private lateinit var homeButton: TextView
    private lateinit var searchButton: TextView
    /** Half-time hop (8.42): "Back in 15", then the time left; live channels only. */
    private lateinit var hopButton: TextView
    /** Hand-off (8.42): "On TV" on a phone, "On phone" on a TV, when a linked device is near. */
    private lateinit var handoffButton: TextView
    // 8.28: the screen lock (phones), Chromecast (phones).
    private lateinit var lockButton: TextView
    private lateinit var castButton: TextView
    private var locked = false
    private var lockShield: FrameLayout? = null
    private var unlockPill: TextView? = null
    private val hideUnlockPill = object : Runnable {
        override fun run() {
            unlockPill?.visibility = View.GONE
        }
    }
    private var cast: com.google.android.gms.cast.framework.CastContext? = null
    /** The item this phone itself last started (casting can move on without it). */
    private var playingHere = -1
    /** The Chromecast playing what this player had ("Living room TV"), or null. */
    private var castingTo: String? = null
    private val castListener = object : com.google.android.gms.cast.framework.SessionManagerListener<com.google.android.gms.cast.framework.CastSession> {
        override fun onSessionStarting(session: com.google.android.gms.cast.framework.CastSession) {}
        override fun onSessionStarted(session: com.google.android.gms.cast.framework.CastSession, sessionId: String) {
            castNow()
        }
        override fun onSessionStartFailed(session: com.google.android.gms.cast.framework.CastSession, error: Int) = toast("Couldn't connect to the Chromecast")
        override fun onSessionEnding(session: com.google.android.gms.cast.framework.CastSession) {}
        override fun onSessionEnded(session: com.google.android.gms.cast.framework.CastSession, error: Int) = castEnded()
        override fun onSessionResuming(session: com.google.android.gms.cast.framework.CastSession, sessionId: String) {}
        override fun onSessionResumed(session: com.google.android.gms.cast.framework.CastSession, wasSuspended: Boolean) = markCasting(session)
        override fun onSessionResumeFailed(session: com.google.android.gms.cast.framework.CastSession, error: Int) {}
        override fun onSessionSuspended(session: com.google.android.gms.cast.framework.CastSession, reason: Int) {}
    }
    /** Frames the picture dropped since this item started (Stream info). */
    private var droppedFrames = 0
    private val statsListener = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onDroppedVideoFrames(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            this@PlayerActivity.droppedFrames += droppedFrames
        }
    }

    /** The top of the controls: ☆, what's playing (and what's on now); phones also the setting icons. */
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
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

    /**
     * Lets the rest of the phone or TV control the player: earbud and headset buttons, Bluetooth in
     * the car, Assistant, and the play/pause and next/previous buttons of the picture-in-picture window.
     */
    private var session: androidx.media3.session.MediaSession? = null

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

    // While a channel or film is opening (8.22; the owner: "something that the channel is loading,
    // either channel logo or something else, instead of black"): its logo and name over the dark
    // picture, with three dots taking turns, until the picture is there. See buildLoadingView.
    private var loadingView: LinearLayout? = null
    private lateinit var loadingLogo: android.widget.ImageView
    private lateinit var loadingName: TextView
    private var loadingDots: List<View> = emptyList()
    private var loadingStep = 0
    private var loadingLogoFor: String? = null
    private var loadingLogoJob: coil.request.Disposable? = null
    private val loadingTick = object : Runnable {
        override fun run() {
            val lit = loadingStep++ % (loadingDots.size + 1)
            loadingDots.forEachIndexed { i, dot -> dot.alpha = if (i == lit) 1f else 0.28f }
            handler.postDelayed(this, 320)
        }
    }
    private val hideBanner = Runnable { bannerView.visibility = View.GONE }
    private val timeFormat by lazy { android.text.format.DateFormat.getTimeFormat(this) }

    // ---- two channels at once ----

    /**
     * The other pictures while several screens are on (8.33: up to four; empty: one picture, as
     * usual). The main player is picture 0; these are 1, 2, 3.
     */
    private val panes = ArrayList<DualPane>()

    /** The second picture: set while two or more screens are on (the many `dual != null` checks). */
    private val dual: DualPane? get() = panes.firstOrNull()

    /** The picture heard and steered: 0 = the main one, 1..3 = [panes]. */
    private var heard = 0

    private val heardPane: DualPane? get() = if (heard > 0) panes.getOrNull(heard - 1) else null

    /** The channel list is open to pick a channel for a new screen. */
    private var pickingSecond = false

    /** Where each picture is (left, top, right, bottom), main first, for taps. */
    private val rects = Array(MAX_SCREENS) { IntArray(4) }
    private lateinit var twoButton: TextView
    private lateinit var dualFrame: View
    private val dualLabels = ArrayList<TextView>()
    private lateinit var dualHint: TextView
    private var addScreenButton: View? = null
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

    /** Pause & rewind live TV: the buffer as a bar where the seek bar is for films. */
    private lateinit var liveBar: LiveTimeline
    /** Live TV: record this channel (or stop recording it). */
    private lateinit var recordButton: TextView

    /** Pause & rewind live TV: the buffer the live channel plays from (null: it plays directly). */
    private var shift: app.falak.player.record.Timeshift? = null

    /** Live channels that wouldn't play through the buffer this time: they play directly. */
    private val noShift = HashSet<String>()

    /** "A recording uses a connection" said once. */
    private var recordHintShown = false
    private val shiftTick = object : Runnable {
        override fun run() {
            // The provider refused the buffer's connection: the channel plays directly, at once.
            val t = shift
            val ch = queue.getOrNull(index)
            if (t != null && t.failed && ch != null) {
                FreezeWatch.note("buffer couldn't start: " + t.capture.problem)
                noShift += ch.id
                releaseShift()
                playCurrent(showInfo = false)
                return
            }
            // (8.50) Its own subtitles or languages would be lost through the buffer (the owner: "this
            // channel has subtitles"): it plays directly, with all of them; pausing live isn't offered.
            if (t != null && t.capture.extras && ch != null) {
                FreezeWatch.note("buffer left out: the channel has its own subtitles or languages")
                noShift += ch.id
                releaseShift()
                playCurrent(showInfo = false)
                return
            }
            updateShiftViews()
            if (shift != null) handler.postDelayed(this, 500)
        }
    }

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
        NoPicture.init(this)
        // The mini player (and Listen mode) let go of their stream before this one connects.
        MiniPlayerState.close()
        app.falak.player.ListenService.handBack("")
        // A sleep timer that ran out while nothing was playing is over.
        if (SleepTimer.due(android.os.SystemClock.elapsedRealtime())) SleepTimer.cancel()
        queue = repo.playQueue
        // Android closed the app while the player was in the background: back to what was playing.
        val restored = if (queue.isEmpty()) PlayerRestore.read(savedInstanceState) else null
        if (restored != null) {
            queue = restored.queue
            repo.playQueue = restored.queue
            repo.currentPlaylist = restored.playlistId?.let { id -> repo.state.value.playlists.firstOrNull { it.id == id } }
            repo.currentSeries = restored.series
            if (restored.positionMs > 0) resumePositionMs = restored.positionMs
        }
        if (queue.isEmpty()) {
            finish()
            return
        }
        index = restored?.index ?: intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, queue.lastIndex)
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
        buildLoadingView()
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
        lifecycleScope.launch { app.falak.player.record.RecordCenter.book.collect { if (::recordButton.isInitialized) updateRecordButton() } }

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

        // The buttons used while watching, with their names: one tidy row on the bottom line of the
        // screen, under the seek bar, shown and hidden together with the player controls. The ones
        // set once (Picture size, Sleep, Player) are icons: phones at the top right, TV at the end of
        // the row (the one the remote is on shows its name). Every button is on screen, none under
        // a More. (8.10: Menu went, Back does the same; Rotation went, the phone's own setting does it.)
        browseButton = pill("Channels", R.drawable.ic_pl_channels) { openBrowser() }
        // Live TV: a second channel beside this one.
        twoButton = pill("Two screens", R.drawable.ic_pl_two) { startPickingSecond() }.apply { visibility = View.GONE }
        // Watching a show from its start: back to the live channel.
        // (Or, paused or rewound on a live channel: back to what's on now.)
        liveButton = pill("Back to live", R.drawable.ic_pl_live) {
            if (liveBeforeStartOver != null) backToLive() else shiftToLive()
        }.apply { visibility = View.GONE }
        recordButton = pill("Record", R.drawable.ic_pl_record) { showRecordDialog() }.apply { visibility = View.GONE }
        // Add what's playing to Favorites without leaving the player: the star before its name.
        favButton = pill("", R.drawable.ic_pl_star_outline) { queue.getOrNull(index)?.let { toggleFavorite(it) } }
            .apply { visibility = View.GONE }
        // Straight to the app's Home from anything playing (8.27, the owner asked): phones only (8.28:
        // the owner had it taken off the TV, where Back does it).
        homeButton = pill("", R.drawable.ic_pl_home) { goHome() }.apply {
            tag = "Home"
            contentDescription = "Home"
        }
        // The search of everything from the player (8.49, the owner asked; phones and TV): like Home, a
        // live channel carries on in the mini player and a film or episode keeps its place.
        searchButton = pill("", R.drawable.ic_nav_search) { goHome("SEARCH") }.apply {
            tag = "Search"
            contentDescription = "Search"
        }
        // Phones (8.28): lock the screen while watching (a pocket, a child's hands), and Chromecast.
        lockButton = pill("", R.drawable.ic_pl_lock) { lockScreen() }.apply {
            tag = "Lock the screen"
            contentDescription = "Lock the screen"
        }
        if (!tvLayout) cast = app.falak.player.cast.Cast.context(this)
        castButton = pill("", R.drawable.ic_pl_cast) { castClicked() }.apply {
            tag = "Cast"
            contentDescription = "Cast to a Chromecast"
            visibility = if (cast != null) View.VISIBLE else View.GONE
        }
        tracksButton = pill("Audio and subtitles", R.drawable.ic_pl_subtitles) { showTracksPanel() }.apply { visibility = View.GONE }
        // 8.42: zap away and be brought back (a break in a match), and play this on the other device.
        hopButton = pill(HOP_LABEL, R.drawable.ic_pl_hop) { hopClicked() }.apply { visibility = View.GONE }
        handoffButton = pill(if (tvLayout) "On phone" else "On TV", if (tvLayout) R.drawable.ic_pl_phone else R.drawable.ic_pl_tv) { handoffClicked() }
            .apply { visibility = View.GONE }
        aspectButton = pill("Picture size  ·  " + aspectNames[aspect], R.drawable.ic_pl_fit) { cycleAspect() }
        engineButton = pill("Player", R.drawable.ic_pl_player) { showEngineDialog() }
        // Films and episodes only (live TV always plays at normal speed).
        speedButton = pill(speedLabel(speed), R.drawable.ic_pl_speed) { showSpeedDialog() }.apply { visibility = View.GONE }
        // Stop after a while, or at the end of what's on (the sound fades out over the last minute).
        sleepButton = pill(SleepTimer.buttonLabel(android.os.SystemClock.elapsedRealtime()), R.drawable.ic_pl_sleep) { showSleepDialog() }
        applyIconLabels()
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Side padding leaves room for the focused button to grow without being cut off.
            setPadding(dp(16), 0, dp(16), 0)
            clipToPadding = false
            clipChildren = false
            for (b in pills()) addView(b, pillParams())
            if (tvLayout) {
                // A thin line, then the setting icons.
                addView(
                    View(this@PlayerActivity).apply { setBackgroundColor(0x40FFFFFF) },
                    LinearLayout.LayoutParams(dp(1), dp(24)).apply { setMargins(dp(8), 0, dp(8), 0) },
                )
                for (b in settingIcons()) addView(b, pillParams())
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
        topBar = buildTopBar()
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
            progressTintList = android.content.res.ColorStateList.valueOf(accentInt)
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
                FalakTheme(dark = true, layout = app.settings.layout, accent = app.settings.accent) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                        SkipIntroButton(skipIntroShown && upNext == null, skipLabel) { skipIntro() }
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
                FalakTheme(dark = true, layout = app.settings.layout, accent = app.settings.accent) {
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
                    // Locked: Back does nothing but say how to unlock.
                    if (locked) {
                        showUnlockPill()
                        return
                    }
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
        cast?.let { c ->
            runCatching { c.sessionManager.addSessionManagerListener(castListener, com.google.android.gms.cast.framework.CastSession::class.java) }
            // Already casting (from before): this player stays quiet.
            c.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let { markCasting(it) }
        }
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
        handler.removeCallbacks(freezeTick)
        if (!FREEZE_OFF) handler.postDelayed(freezeTick, 2_000)
        prayerJob?.cancel()
        prayerJob = lifecycleScope.launch { app.falak.player.PrayerAlerts.current.collect { onPrayer(it) } }
        // A reminder for the channel already playing needs no card.
        alertJob?.cancel()
        alertJob = lifecycleScope.launch {
            ProgramAlerts.current.collect { a -> if (a != null && a.channelId == currentChannelId) ProgramAlerts.dismiss() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // (After onStop the player is let go: a film's place is in resumePositionMs then.)
        val ch = queue.getOrNull(index)
        val position = when {
            ch == null || ch.kind == Kind.LIVE -> -1L
            player != null -> player?.currentPosition ?: -1L
            else -> resumePositionMs
        }
        runCatching { PlayerRestore.save(outState, queue, index, repo.currentPlaylist?.id, repo.currentSeries, position) }
    }

    override fun onPause() {
        super.onPause()
        // The screen turned off: Listen mode carries the sound on.
        if (!listening && !inPip && !screenOn()) startListening()
    }

    override fun onStop() {
        super.onStop()
        repo.tvRow?.let { w -> runCatching { w() } }
        cast?.let { c -> runCatching { c.sessionManager.removeSessionManagerListener(castListener, com.google.android.gms.cast.framework.CastSession::class.java) } }
        if (!listening && !inPip && !screenOn()) startListening()
        repo.nowPlaying = null
        repo.handNow = null
        // A hop and "Still watching?" end with the player.
        hop = null
        stillDialog?.let { d -> runCatching { d.dismiss() } }
        stillDialog = null
        prayerJob?.cancel()
        alertJob?.cancel()
        // Two-screen mode ends when the player leaves the screen.
        if (dual != null) endDual(keep = 0, replay = false)
        handler.removeCallbacks(rateTick)
        handler.removeCallbacks(freezeTick)
        if (repo.kidsActive) app.falak.player.data.KidsClock.flush(this)
        // The service report's figures so far, to the file (off the main thread).
        flushWatched()
        val dir = filesDir
        Thread { app.falak.player.data.ServiceLogStore.save(dir) }.start()
        repo.playerOpen = false
        FreezeWatch.note("player closing")
        // (The loading page's dots stop with the player; coming back starts the channel, and them, again.)
        handler.removeCallbacks(loadingTick)
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
        if (pipReceiverOn) {
            runCatching { unregisterReceiver(pipReceiver) }
            pipReceiverOn = false
        }
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
        // A title that came up black here (NoPicture): VLC from the start, decoding the picture itself.
        if (NoPicture.has(ch.id)) return Engine.VLC
        // Smooth interlaced channels (8.35): VLC deinterlaces live channels; ExoPlayer can't.
        if (repo.state.value.settings.deinterlace && ch.kind == Kind.LIVE && !ch.id.startsWith("cu:")) return Engine.VLC
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
        setPillLabel(engineButton, "Player  ·  " + e.short)
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
            // Subtitles that reach the player still in their file's own format (SRT, ASS, WebVTT,
            // PGS…) are read here too, instead of the subtitles failing.
            @Suppress("DEPRECATION")
            override fun buildTextRenderers(
                context: android.content.Context,
                output: androidx.media3.exoplayer.text.TextOutput,
                outputLooper: android.os.Looper,
                extensionRendererMode: Int,
                out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>,
            ) {
                val start = out.size
                super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
                for (i in start until out.size) {
                    (out[i] as? androidx.media3.exoplayer.text.TextRenderer)?.experimentalSetLegacyDecodingEnabled(true)
                }
            }

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
                if (processors.isEmpty()) {
                    val plain = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams) ?: return null
                    return app.falak.player.player.SoundTiming.wrap(plain, soundTiming)
                }
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
                val heard = if (tap != null) captionsEngine().watch(built) else built
                // Sound timing (8.29): the picture follows the sound's clock, moved by the setting.
                return app.falak.player.player.SoundTiming.wrap(heard, soundTiming)
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
        p.addAnalyticsListener(statsListener)
        soundTiming.us = repo.state.value.settings.audioDelayMs * 1000L
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
        startSession()
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
            deinterlace = st.deinterlace,
        )
        val layout = VLCVideoLayout(this)
        // Underneath the controls; ExoPlayer's own picture surface steps aside.
        root.addView(layout, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        playerView.videoSurfaceView?.visibility = View.GONE
        v.attach(layout)
        v.setAudioDelay(st.audioDelayMs)
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
        startSession()
        vlcLayout = layout
        vlc = v
        applyVideoArea()
    }

    /** Releases whichever engine is running (keeps the activity's views). */
    private fun releaseEngine() {
        // The buffer goes with the player (Listen mode, the mini player and the next start play directly or anew).
        releaseShift()
        stopAutoCaptions()
        exoCaptions = false
        exoSynced = false
        releaseSession()
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

    /** The media session for the engine now playing (Listen mode has its own, "listen"). */
    private fun startSession() {
        releaseSession()
        val zp = playerView.player ?: return
        session = runCatching {
            androidx.media3.session.MediaSession.Builder(this, zp).setId("player").build()
        }.getOrNull()
        updatePipParams()
    }

    private fun releaseSession() {
        session?.let { runCatching { it.release() } }
        session = null
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
            // What is playing, in figures (8.28).
            .setNeutralButton("Stream info") { _, _ -> showStreamInfo() }
            // Sound out of step with the picture (a soundbar, a slow stream): moved here (8.29).
            .setPositiveButton("Sound timing") { _, _ -> showSoundTiming() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun playCurrent(showInfo: Boolean, overrideUrl: String? = null) {
        val ch = queue.getOrNull(index) ?: return
        frameShown = false
        soundSinceMs = 0L
        vlcSoundSinceMs = 0L
        // Another item: the buffer of the one before goes (a recording of it carries on).
        releaseShift()
        // Downloaded ones watched to the end and left behind: deleted now (Delete when watched).
        deleteWatchedCopies(except = ch.id)
        droppedFrames = 0
        // Casting: the next channel or episode goes to the Chromecast, not here.
        if (castingTo != null && !castBypass && castNow()) return
        castBypass = false
        playingHere = index
        showLoading(ch)
        loadSkips(ch)
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
        if (!ch.id.startsWith("cu:") && !ch.id.startsWith("addon:")) repo.lastPlayedId = ch.id
        // And the live channel, to start with it when the app is opened fresh next time.
        if (ch.kind == Kind.LIVE && !ch.id.startsWith("cu:")) repo.currentPlaylist?.let { repo.setLastLive(it.id, ch.id) }
        setPillLabel(browseButton, when (ch.kind) {
            Kind.LIVE -> "Channels"
            Kind.MOVIE -> "Films"
            Kind.SERIES -> "Episodes"
        })
        // A replay on its own (such as "From the start"): there's no list to browse.
        browseButton.visibility = if ((ch.id.startsWith("cu:") || ch.id.startsWith("addon:")) && queue.size <= 1) View.GONE else View.VISIBLE
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
        // Pause & rewind live TV: the channel plays from the buffer kept on this device (one
        // connection to the provider, shared with a recording of the same channel).
        val playUrl = openShift(ch, url, ua, savedCopy != null) ?: url
        updateRecordButton()
        hintRecording(ch, url)

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
        updateHopButton()
        updateHandoffButton()
        // A replay of our own "From the start" keeps the way back to live; anything else forgets it.
        if (!ch.id.startsWith("cu:")) liveBeforeStartOver = null
        updateShiftViews()
        offerStartOver(ch)
        if (captions) startAutoCaptions() else stopAutoCaptions()
        // Two minutes on a live channel count towards Home's "For this hour".
        handler.removeCallbacks(noteHabit)
        if (ch.kind == Kind.LIVE && !ch.id.startsWith("cu:")) handler.postDelayed(noteHabit, 120_000)
        if (dual != null) updateDualLabels()
        hideMessage()
        // Resume movies/episodes where they were left (this session first, then saved progress).
        val saved = if (ch.id.startsWith("addon:")) {
            val video = ch.id.removePrefix("addon:")
            repo.state.value.settings.addonWatch.firstOrNull { it.videoId == video }?.pos?.takeIf { it > 0 }
        } else {
            repo.currentPlaylist?.let { pl -> repo.state.value.progress[progressKey(repo.state.value.mine(pl.id), ch.id)]?.takeUnless { it.done }?.pos }
        }
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
            // (A title that came up black here: VLC decodes the picture itself, 8.52.)
            v.load(playUrl, ch.name, ua, ch.referrer, if (overrideUrl == null && resume > 0) resume else 0L, hardware = !NoPicture.has(ch.id))
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
            .setUri(playUrl)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(ch.name).build())
        when {
            playUrl.contains("m3u8", ignoreCase = true) -> itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            playUrl.contains(".mpd", ignoreCase = true) -> itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        val item = itemBuilder.build()
        currentItem = item
        val source = if (playUrl.contains("m3u8", ignoreCase = true)) {
            // Expose closed captions (CEA-608) carried inside HLS video even when the playlist doesn't declare them.
            @Suppress("DEPRECATION")
            HlsMediaSource.Factory(dataSource)
                .setExtractorFactory(SniffingHlsExtractorFactory(sniffer))
                // Subtitles (WebVTT and others) read as they arrive, as DefaultMediaSourceFactory
                // does: this factory is made by hand and otherwise leaves them in a form Media3 1.5
                // no longer shows.
                .experimentalParseSubtitlesDuringExtraction(true)
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
        if (delta == 1) learnOutro()
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
            // Through the buffer it didn't work: the channel plays directly, as before.
            if (shift != null) {
                FreezeWatch.note("buffer didn't play: " + (shift?.capture?.problem ?: error.errorCodeName))
                noShift += ch.id
                releaseShift()
                handler.post { playCurrent(showInfo = false) }
                return
            }
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
            // Nothing more is tried: the message says so, without the dots going on behind it.
            hideLoading()
            // A channel that can't be opened while the internet is there: hidden from the lists once
            // another channel plays (see DeadChannels).
            if (ch.kind == Kind.LIVE && !ch.id.startsWith("cu:") && online()) repo.noteLiveFailed(ch)
            val what = if (ch.kind == Kind.LIVE) "channel" else "video"
            val hint = if (engine != Engine.VLC) "\nTry the ${engine.short} button below → VLC" else ""
            showMessage("Can't play this $what right now\n${error.errorCodeName}$hint")
            explainRefusal()
        }

        override fun onRenderedFirstFrame() {
            hideLoading()
            frameShown = true
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                retried = false
                hideLoading()
                queue.getOrNull(index)?.takeIf { it.kind == Kind.LIVE && !it.id.startsWith("cu:") }?.let { repo.noteLivePlayed(it) }
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
                // An add-on's film or episode has no queue behind it (its links are picked one at a
                // time): back to its page, where the next episode is one tap (8.24).
                if (ch != null && ch.id.startsWith("addon:") && queue.size == 1) {
                    handler.post { finish() }
                    return
                }
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
            // A picture ExoPlayer can't read at all on this device (a codec it has no decoder for):
            // VLC at once, rather than sound over black.
            val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            if (video.isNotEmpty() && video.none { g -> (0 until g.length).any { g.isTrackSupported(it, true) } }) {
                queue.getOrNull(index)?.let { ch -> handler.post { noPictureSwitch(ch, "no decoder", video.first().getTrackFormat(0)) } }
                return
            }
            // A kind of picture that already came up black on this device (8.52): VLC at once, no wait.
            val picture = (video.firstOrNull { it.isSelected } ?: video.firstOrNull())?.getTrackFormat(0)
            if (engine != Engine.VLC && NoPicture.knownKind(picture)) {
                queue.getOrNull(index)?.let { ch -> handler.post { noPictureSwitch(ch, "known kind " + NoPicture.kindOf(picture), picture) } }
                return
            }
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
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                updatePipParams()
                updatePipButtons()
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

        /** A live channel playing from the buffer: the rewind and forward buttons move in it. */
        private fun shifting() = shift != null && liveNow()

        override fun getAvailableCommands(): Player.Commands {
            var b = super.getAvailableCommands().buildUpon()
            if (queue.size > 1) b = b.addAll(*zapCommands)
            if (liveNow()) b = b.removeAll(*seekCommands)
            if (shifting()) b = b.addAll(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD)
            return b.build()
        }

        override fun isCommandAvailable(command: Int): Boolean {
            if (shifting() && (command == Player.COMMAND_SEEK_BACK || command == Player.COMMAND_SEEK_FORWARD)) return true
            if (liveNow() && command in seekCommands) return false
            return (queue.size > 1 && command in zapCommands) || super.isCommandAvailable(command)
        }

        override fun seekBack() = if (shifting()) shiftBy(-SEEK_STEP_MS) else super.seekBack()
        override fun seekForward() = if (shifting()) shiftBy(SEEK_STEP_MS) else super.seekForward()
        override fun getSeekBackIncrement(): Long = if (shifting()) SEEK_STEP_MS else super.getSeekBackIncrement()
        override fun getSeekForwardIncrement(): Long = if (shifting()) SEEK_STEP_MS else super.getSeekForwardIncrement()

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
        if (dual != null) {
            layoutDual(w, h)
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
        // A key held after it did its job (e.g. OK held to add a screen): its repeats go nowhere.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && event.keyCode == swallowUpKey) return true
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
        // Remote buttons given a job (Settings → Remote buttons), and the remote's Record key.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val job = repo.state.value.settings.keyActions[event.keyCode.toString()]
                ?: if (event.keyCode == KeyEvent.KEYCODE_MEDIA_RECORD) KeyActions.RECORD else null
            if (job != null && runKeyAction(job)) {
                swallowUpKey = event.keyCode
                return true
            }
        }
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
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (canSeek()) { seekBy(30_000L); return true } else if (live && shift != null) { shiftBy(30_000L); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> if (canSeek()) { seekBy(-30_000L); return true } else if (live && shift != null) { shiftBy(-30_000L); return true }
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

    /** Screenshot tests: the live bar of a channel paused [behind] ms behind live, with [held] ms in the buffer. */
    @androidx.annotation.VisibleForTesting
    internal fun previewLiveBar(held: Long, behind: Long) {
        if (!::liveBar.isInitialized) return
        liveBar.visibility = View.VISIBLE
        liveBar.set(repo.state.value.settings.liveBufferMinutes.coerceAtLeast(1) * 60_000L, held, behind)
        setPillLabel(liveButton, "Live  ·  −" + clock(behind))
        liveButton.visibility = View.VISIBLE
        playerView.showController()
    }

    /** Screenshot tests: the Up next card over the picture. */
    @androidx.annotation.VisibleForTesting
    internal fun previewUpNext(next: Channel) {
        playerView.hideController()
        upNext = next
        upNextLabel = "S1 E3"
        upNextSecs = 8
    }

    /**
     * Tests: an add-on's film or episode [ch], where [stand] is in it, with the parts introdb.app
     * gave for it. Runs the check the player runs every second and returns what the skip button
     * says and where it would jump (null: no button).
     */
    @androidx.annotation.VisibleForTesting
    internal fun previewSkip(stand: Player, ch: Channel, parts: List<app.falak.player.data.SkipSegments.Part>): Pair<String, Long>? {
        playerView.hideController()
        queue = listOf(ch)
        index = 0
        skips = parts
        skipsFor = ch.id
        addonSkipTick(stand, ch)
        return if (skipIntroShown) skipLabel to skipTarget else null
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
        if (dual != null) endDual(keep = heard)
        // Listen mode (phones): the sound carries on in the background instead of a small window.
        if (startListening()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            player?.isPlaying == true &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            runCatching { enterPictureInPictureMode(pipParams()) }
        }
    }

    /**
     * Android 12+: the small window opens as the Home gesture starts (smooth), when it would open
     * anyway: something playing, one screen, and Listen mode not taking over the sound. Otherwise
     * onUserLeaveHint decides, as before.
     */
    private fun pipAuto(): Boolean {
        val p = player ?: return false
        if (!p.isPlaying || dual != null || listening || handingOff || isFinishing) return false
        val listenWanted = repo.state.value.settings.listenMode && !isTvDevice && !repo.kidsActive
        return !listenWanted
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT < 31 || !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching { setPictureInPictureParams(pipParams()) }
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
            .setActions(pipActions())
            .apply { if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(pipAuto()) }
            .build()
    }

    /**
     * The small window's buttons (8.50; it had none, so nothing could be paused or changed there):
     * previous, play / pause, next — channels for live TV, episodes or the list's next item otherwise.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipActions(): List<android.app.RemoteAction> {
        fun action(code: Int, icon: Int, title: String): android.app.RemoteAction {
            val intent = Intent(PIP_ACTION).setPackage(packageName).putExtra("do", code)
            val pending = android.app.PendingIntent.getBroadcast(
                this, code, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            return android.app.RemoteAction(android.graphics.drawable.Icon.createWithResource(this, icon), title, title, pending)
        }
        val out = ArrayList<android.app.RemoteAction>()
        val several = queue.size > 1
        if (several) out += action(PIP_PREVIOUS, R.drawable.exo_styled_controls_previous, "Previous")
        out += if (player?.isPlaying == true) {
            action(PIP_TOGGLE, R.drawable.exo_styled_controls_pause, "Pause")
        } else {
            action(PIP_TOGGLE, R.drawable.exo_styled_controls_play, "Play")
        }
        if (several) out += action(PIP_NEXT, R.drawable.exo_styled_controls_next, "Next")
        return out
    }

    private var pipReceiverOn = false

    private val pipReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            when (intent?.getIntExtra("do", 0)) {
                PIP_TOGGLE -> player?.let { p -> if (p.isPlaying) p.pause() else p.play() }
                PIP_NEXT -> zap(1)
                PIP_PREVIOUS -> zap(-1)
            }
            updatePipButtons()
        }
    }

    /** The window's play / pause button follows the player (any Android with picture-in-picture). */
    private fun updatePipButtons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !inPip) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        // The window's buttons are heard only while it's open.
        if (isInPictureInPictureMode && !pipReceiverOn) {
            androidx.core.content.ContextCompat.registerReceiver(
                this, pipReceiver, android.content.IntentFilter(PIP_ACTION), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            pipReceiverOn = true
            updatePipButtons()
        } else if (!isInPictureInPictureMode && pipReceiverOn) {
            runCatching { unregisterReceiver(pipReceiver) }
            pipReceiverOn = false
        }
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
        // An add-on's film or episode (8.23): where it was left, for the portal's Continue watching.
        // (Not with the playlist's: its link can change, so carrying on starts from its page.)
        if (ch.id.startsWith("addon:")) {
            val length = p.duration
            if (length > 0) {
                val at = p.currentPosition
                repo.noteAddonProgress(ch.id.removePrefix("addon:"), at, length, p.playbackState == Player.STATE_ENDED || at > length - 120_000)
            }
            return
        }
        if (ch.kind == Kind.LIVE || ch.id.startsWith("cu:")) return
        val pl = repo.currentPlaylist ?: return
        val dur = p.duration
        if (dur <= 0) return
        val pos = p.currentPosition
        val finished = p.playbackState == Player.STATE_ENDED || pos > dur - 120_000
        val show = repo.currentSeries
        val meta = metaFor(ch)
        val key = progressKey(repo.state.value.mine(pl.id), ch.id)
        // Finished: kept as watched (8.31, see Watch.done); barely started: forgotten.
        val kept = when {
            finished -> dur
            pos < 30_000 -> 0
            else -> pos
        }
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
        app.falak.player.data.WatchNext.update(this, key, meta, if (finished) 0 else kept, dur, onHome && !ch.id.startsWith("rec:"))
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

    /**
     * The player turns with the phone the way the phone's own rotate setting says (Auto-rotate in
     * quick settings; with it off, Android offers a small rotate button when the phone is turned).
     * 8.10: the player's own Rotation button went (an old choice of it is no longer used).
     */
    private fun applyRotation() {
        if (tvLayout) return
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    }

    private fun cycleAspect() = setAspect((aspect + 1) % resizeModes.size, byPinch = false)

    /** The picture's shape: Fit, Fill (stretched) or Zoom (edges cut). Kept for next time. */
    private fun setAspect(to: Int, byPinch: Boolean) {
        val changed = to != aspect
        aspect = to
        playerView.resizeMode = resizeModes[aspect]
        vlc?.setScale(aspect)
        setPillLabel(aspectButton, "Picture size  ·  " + aspectNames[aspect])
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
                if (!g.isTrackSupported(i)) {
                    // Subtitles this player can't show: offered with VLC rather than left out.
                    val f = g.getTrackFormat(i)
                    if (type == C.TRACK_TYPE_TEXT && !Captions.isProbe(f)) {
                        out += TrackOption("${trackLabel(f)}  (plays with VLC)", false) {
                            wantVlcSubtitles = true
                            switchEngine(Engine.VLC)
                        }
                    }
                    continue
                }
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

    /** Films and episodes: where it was when subtitles were turned on (their first line can be minutes in). */
    private var captionWatchFrom = 0L

    private val captionWatch = object : Runnable {
        override fun run() {
            val p = exo ?: return
            val textOn = p.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
            if (captionsSeen || !textOn) return
            val ch = queue.getOrNull(index)
            if (ch != null && ch.kind != Kind.LIVE) {
                // Only after three minutes of the film with not a single line.
                if (p.currentPosition - captionWatchFrom < 180_000L) {
                    handler.postDelayed(this, 30_000)
                    return
                }
                toast("No subtitles have come from this track in 3 minutes. Try another one, or Subtitles → “Open with VLC”.")
            } else {
                toast("This channel isn't sending captions right now. Try another track, or switch the player to VLC.")
            }
        }
    }

    /** Many streams advertise captions but send none; tell the user instead of showing nothing. */
    private fun watchCaptions() {
        captionsSeen = false
        captionWatchFrom = exo?.currentPosition ?: 0L
        handler.removeCallbacks(captionWatch)
        handler.postDelayed(captionWatch, 25_000)
    }

    /** Hands the stream to VLC or another player (for teletext subtitles, rare codecs, …). */
    private fun openExternally() {
        val ch = queue.getOrNull(index) ?: return
        val url = currentUrl.ifBlank { ch.url }
        val intent = app.falak.player.player.ExternalPlayer.intent(
            Uri.parse(url), ch.name, ch.userAgent.ifBlank { repo.currentPlaylist?.userAgent.orEmpty() }, ch.referrer,
        )
        try {
            handingOff = true
            // No small window on the way to the other player.
            updatePipParams()
            startActivity(intent)
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

    /** The remote's Audio or Subtitles key (and the button when only one of them has a choice). */
    private fun showTrackDialog(type: Int) {
        val options = trackChoices(type)
        if (options == null) {
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

    /**
     * The Audio and subtitles button: both lists in one panel, the ones in use ticked. When only
     * one of them has anything to choose, just that list.
     */
    private fun showTracksPanel() {
        if (inPip) return
        val audio = trackChoices(C.TRACK_TYPE_AUDIO)
        val text = trackChoices(C.TRACK_TYPE_TEXT)
        if (audio == null || text == null) {
            if (audio == null && text == null) {
                Toast.makeText(this, "No other audio or subtitles in this stream", Toast.LENGTH_SHORT).show()
            } else {
                showTrackDialog(if (audio != null) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT)
            }
            return
        }
        // A heading (option null) or a choice.
        class Row(val label: String, val type: Int, val option: TrackOption?)
        val rows = ArrayList<Row>()
        rows += Row("Audio", C.TRACK_TYPE_AUDIO, null)
        audio.forEach { rows += Row(it.label, C.TRACK_TYPE_AUDIO, it) }
        rows += Row("Subtitles", C.TRACK_TYPE_TEXT, null)
        text.forEach { rows += Row(it.label, C.TRACK_TYPE_TEXT, it) }
        val builder = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        val themed = builder.context
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int): Any = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun areAllItemsEnabled() = false
            override fun isEnabled(position: Int) = rows[position].option != null
            override fun getViewTypeCount() = 2
            override fun getItemViewType(position: Int) = if (rows[position].option == null) 0 else 1
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = rows[position]
                val tv = convertView as? TextView
                    ?: (android.view.LayoutInflater.from(themed).inflate(android.R.layout.simple_list_item_1, parent, false) as TextView)
                val o = row.option
                if (o == null) {
                    tv.text = row.label
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    tv.setTypeface(null, Typeface.BOLD)
                    tv.alpha = 0.7f
                    tv.minHeight = dp(36)
                    tv.setPadding(dp(24), dp(if (position == 0) 4 else 16), dp(24), dp(4))
                } else {
                    // The one in use: ticked and bold.
                    tv.text = if (o.selected) "✓  " + o.label else o.label
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    tv.setTypeface(null, if (o.selected) Typeface.BOLD else Typeface.NORMAL)
                    tv.alpha = 1f
                    tv.minHeight = dp(48)
                    tv.setPadding(dp(if (o.selected) 24 else 46), 0, dp(24), 0)
                }
                return tv
            }
        }
        builder.setTitle("Audio and subtitles")
            .setAdapter(adapter) { d, which ->
                val row = rows.getOrNull(which) ?: return@setAdapter
                val o = row.option ?: return@setAdapter
                d.dismiss()
                o.select()
                // Picked by hand: this channel, film or series gets it again next time.
                o.memo?.let { rememberTrack(row.type, it) }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** What the Audio or Subtitles list offers, or null when there's nothing to choose. */
    private fun trackChoices(type: Int): List<TrackOption>? {
        val options = ArrayList(trackOptions(type))
        val real = if (type == C.TRACK_TYPE_TEXT) options.size - 1 else options.size
        // Movies and episodes can also fetch subtitles online.
        val vod = queue.getOrNull(index)?.kind?.let { it != Kind.LIVE } == true
        if (type == C.TRACK_TYPE_TEXT && vod) {
            // An add-on's film or episode: the subtitles the add-ons have for it (8.24).
            val fromAddons = addonSubtitles()
            if (fromAddons.isNotEmpty()) {
                options += TrackOption("🧩  Subtitles from your add-ons (${fromAddons.size})…", false) { pickAddonSubtitle(fromAddons) }
            }
            options += TrackOption("🔎  Find subtitles online…", false) { searchOnlineSubtitles() }
            // Some films carry subtitles only VLC can read: VLC takes over from here and turns them on.
            if (exo != null) {
                options += TrackOption("▶  Open with VLC (finds more subtitles)", false) {
                    wantVlcSubtitles = true
                    switchEngine(Engine.VLC)
                }
            }
        }
        // The captions' look (and auto captions' place and lines), changed as you watch.
        if (type == C.TRACK_TYPE_TEXT) {
            options += TrackOption("Aa  Caption style…", false) { showCaptionStyle() }
        }
        if ((real <= 0 && !(type == C.TRACK_TYPE_TEXT && vod)) || (type == C.TRACK_TYPE_AUDIO && real == 1)) return null
        return options
    }

    private fun updateTrackButtons() {
        val audio = countTracks(C.TRACK_TYPE_AUDIO)
        val text = countTracks(C.TRACK_TYPE_TEXT)
        val vod = queue.getOrNull(index)?.kind?.let { it != Kind.LIVE } == true
        val audioChoice = audio > 1
        val textChoice = text > 0 || vod || autoCaptionsOffered()
        tracksButton.visibility = if (audioChoice || textChoice) View.VISIBLE else View.GONE
        // Named for what it holds.
        setPillLabel(
            tracksButton,
            when {
                audioChoice && textChoice -> "Audio and subtitles"
                audioChoice -> "Audio ($audio)"
                else -> "Subtitles"
            },
        )
        setPillIcon(tracksButton, if (audioChoice && !textChoice) R.drawable.ic_pl_audio else R.drawable.ic_pl_subtitles)
        // The channel turned out to have captions of its own: never auto captions on top of them.
        if (autoCapsOn && text > 0) providerCaptionsTakeOver()
    }

    // ---- online subtitles (OpenSubtitles.com) ----

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun searchOnlineSubtitles() {
        val s = repo.state.value.settings
        val ch = queue.getOrNull(index) ?: return
        if (s.osApiKey.isBlank()) {
            toast("Add your free OpenSubtitles API key in Settings → Sound & subtitles")
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

    /** The subtitles the add-ons offer for what is playing, your language first. */
    private fun addonSubtitles(): List<app.falak.player.data.AddonSub> {
        val ch = queue.getOrNull(index) ?: return emptyList()
        if (!ch.id.startsWith("addon:")) return emptyList()
        val known = repo.addonSubs?.takeIf { it.first == ch.id.removePrefix("addon:") }?.second ?: return emptyList()
        return app.falak.player.data.Addons.sortSubs(known, repo.state.value.settings.subtitleLang.ifEmpty { "en" })
    }

    private fun pickAddonSubtitle(subs: List<app.falak.player.data.AddonSub>) {
        val shown = subs.take(60)
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Subtitles from your add-ons")
            .setItems(shown.map { "${app.falak.player.data.Addons.langName(it.lang)}  ·  ${it.addonName}" }.toTypedArray()) { _, which ->
                downloadAddonSubtitle(shown[which])
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Fetches the file, makes it UTF-8 (old Arabic, Russian… files aren't), and shows it. */
    private fun downloadAddonSubtitle(sub: app.falak.player.data.AddonSub) {
        toast("Getting the subtitles…")
        lifecycleScope.launch {
            val lang = app.falak.player.data.Addons.langCode(sub.lang).ifEmpty { "und" }
            val got = try {
                withContext(Dispatchers.IO) {
                    val request = okhttp3.Request.Builder().url(sub.url).header("User-Agent", app.falak.player.data.Repository.DEFAULT_UA).build()
                    val bytes = repo.http.newCall(request).execute().use { r ->
                        if (!r.isSuccessful) throw java.io.IOException("The add-on answered ${r.code}")
                        val body = r.body ?: throw java.io.IOException("Empty answer")
                        if (body.contentLength() > 4_000_000) throw java.io.IOException("Too big to be subtitles")
                        body.bytes()
                    }
                    val text = app.falak.player.data.Addons.subtitleText(bytes, sub.lang)
                    val vtt = text.trimStart().startsWith("WEBVTT")
                    val dir = java.io.File(cacheDir, "subs").apply { mkdirs() }
                    val file = java.io.File(dir, "addon-${sub.url.hashCode().toUInt()}.${if (vtt) "vtt" else "srt"}")
                    file.writeText(text, Charsets.UTF_8)
                    file to vtt
                }
            } catch (e: Exception) {
                toast(e.message ?: "Couldn't get the subtitles")
                return@launch
            }
            attachSubtitle(got.first, lang, if (got.second) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP, "Add-on (${lang.uppercase()})")
        }
    }

    /** Re-prepares the current video with the downloaded subtitle, keeping the position. */
    private fun attachSubtitle(file: java.io.File, lang: String, mime: String = MimeTypes.APPLICATION_SUBRIP, label: String = "Online (${lang.uppercase()})") {
        vlc?.let {
            it.addSubtitleFile(file)
            toast("Subtitles added")
            return
        }
        val p = exo ?: return
        val item = currentItem ?: return
        val dataSource = currentDataSource ?: return
        val sub = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
            .setMimeType(mime)
            .setLanguage(lang)
            .setLabel(label)
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
        // Only subtitles this player can show (and captions that really carry text).
        val cands = ArrayList<Pair<androidx.media3.common.Tracks.Group, Int>>()
        for (g in p.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until g.length) if (g.isTrackSupported(i) && isReal(g.getTrackFormat(i))) cands += g to i
        }
        if (cands.isEmpty()) return
        autoSubtitlesDone = true
        if (cands.any { (g, i) -> g.isTrackSelected(i) }) return
        val lang = repo.state.value.settings.subtitleLang
        fun fmt(c: Pair<androidx.media3.common.Tracks.Group, Int>) = c.first.getTrackFormat(c.second)
        fun inLang(c: Pair<androidx.media3.common.Tracks.Group, Int>) = lang.isNotEmpty() && fmt(c).language?.startsWith(lang) == true
        // "Forced" subtitles only translate the odd foreign line: a full track comes first.
        fun full(c: Pair<androidx.media3.common.Tracks.Group, Int>) = fmt(c).selectionFlags and C.SELECTION_FLAG_FORCED == 0
        val pick = cands.firstOrNull { inLang(it) && full(it) }
            ?: cands.firstOrNull { inLang(it) }
            ?: cands.firstOrNull { full(it) }
            ?: cands.first()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(pick.first.mediaTrackGroup, pick.second))
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

    /** The accent colour picked in Settings (8.45), for the player's own views. */
    private val accentInt: Int get() = Accents.of(repo.state.value.settings.accent).darkArgb.toInt()

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
        // Icons only (8.42); a TV shows the name under the remote (applyIconLabels).
        text = ""
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tvLayout) 15f else 13f)
        typeface = ResourcesCompat.getFont(this@PlayerActivity, R.font.inter_medium)
        setTextColor(contentColors())
        setSingleLine()
        includeFontPadding = false
        gravity = Gravity.CENTER
        val size = dp(if (tvLayout) 20 else 17)
        ResourcesCompat.getDrawable(resources, icon, theme)?.mutate()?.let {
            it.setBounds(0, 0, size, size)
            setCompoundDrawablesRelative(it, null, null, null)
        }
        // An icon-only button (Settings) is a neat circle-ish pill with even sides.
        compoundDrawablePadding = 0
        compoundDrawableTintList = contentColors()
        background = focusBackground(oval = false, normal = 0x33FFFFFF, edge = 0x26FFFFFF)
        // As wide as it is tall (44 dp on TV, 40 on phones).
        val side = dp(if (tvLayout) 12 else 11)
        setPadding(side, 0, side, 0)
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, focused ->
            val s = if (focused) 1.08f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(120).start()
            // TV: the button the remote is on shows its name.
            if (tvLayout) applyIconLabels()
        }
    }

    /** The buttons used while watching, with their names (each shows only when it applies). */
    private fun pills(): List<TextView> =
        listOf(liveButton, browseButton, tracksButton, recordButton, twoButton, hopButton, speedButton, handoffButton)

    /** The ones set once: icons (phones at the top right, TV at the end of the row). */
    private fun settingIcons(): List<TextView> = listOf(aspectButton, sleepButton, engineButton)

    private fun pillParams() =
        // Phones (8.42): 40 dp tall, tighter, so the row reads as one bar (still a finger's size across).
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(if (tvLayout) 44 else 40))
            .apply { setMargins(dp(if (tvLayout) 6 else 4), 0, dp(if (tvLayout) 6 else 4), 0) }

    private fun setPillLabel(b: TextView, label: String) {
        b.tag = label
        b.contentDescription = label
        applyIconLabels()
    }

    /**
     * 8.42 / 8.49 (the owner): every button is its icon only, also under the remote on a TV; Sleep and
     * "Back in…" show their time left while they run ("20 min"). Names are read out.
     */
    private fun applyIconLabels() {
        if (!::sleepButton.isInitialized || !::engineButton.isInitialized || !::handoffButton.isInitialized) return
        for (b in pills() + settingIcons()) {
            val full = b.tag as? String ?: ""
            // (8.49, the owner: no names when the remote is on a button, only the icons.)
            val text = when {
                // "Sleep 20 min" → "20 min", "Sleep · end" → "end", "Sleep" → "".
                b === sleepButton -> full.removePrefix("Sleep").trim().removePrefix("·").trim()
                // "Back in 12 min" → "12 min" (nothing while no hop runs).
                b === hopButton && hop != null -> full.removePrefix("Back in").trim()
                else -> ""
            }
            if (b.text?.toString() == text) continue
            b.text = text
            b.compoundDrawablePadding = if (text.isEmpty()) 0 else dp(if (tvLayout) 8 else 6)
            // Icon only: as wide as it is tall (44 dp on TV, 40 on phones).
            val side = if (text.isEmpty()) dp(if (tvLayout) 12 else 11) else dp(if (tvLayout) 16 else 12)
            b.setPadding(side, 0, if (text.isEmpty()) side else dp(if (tvLayout) 18 else 14), 0)
        }
    }

    /** (Every button is an icon since 8.42: nothing to fit; kept for the places that ask.) */
    private fun fitPills() = applyIconLabels()

    private fun fitTvPills() = applyIconLabels()

    /**
     * The top of the controls: the star (Favorites), then what's playing and, on a live channel,
     * what's on now. Phones also have the setting icons on the right (TV has them in the row).
     */
    private fun buildTopBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipToPadding = false
        clipChildren = false
        if (!tvLayout) addView(homeButton, pillParams())
        // TV: the star first (the remote starts on the left), then Search; phones: after the name, as a plain star.
        if (tvLayout) {
            addView(favButton, pillParams())
            addView(searchButton, pillParams())
        } else {
            addView(searchButton, pillParams())
        }
        titleView = TextView(this@PlayerActivity).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tvLayout) 18f else 15f)
            typeface = ResourcesCompat.getFont(this@PlayerActivity, R.font.inter_medium)
            // The name, and under it what's on now (two lines at most).
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.05f)
            setShadowLayer(dp(4).toFloat(), 0f, 0f, 0x99000000.toInt())
            setPadding(dp(if (tvLayout) 6 else 10), 0, dp(6), 0)
        }
        addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!tvLayout) {
            favButton.background = focusBackground(oval = true)
            addView(favButton, pillParams())
            // Phones (8.42): the tools set once (Cast, Picture size, Sleep, Lock, Player) in one
            // rounded group at the top right, not loose buttons around the screen.
            val group = LinearLayout(this@PlayerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(100).toFloat()
                    setColor(0x33FFFFFF)
                    setStroke(dp(1), 0x1FFFFFFF)
                }
                setPadding(dp(3), 0, dp(3), 0)
                for (b in listOf(castButton) + settingIcons().take(2) + listOf(lockButton) + settingIcons().drop(2)) {
                    b.background = focusBackground(oval = true)
                    addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
                }
            }
            addView(group, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginStart = dp(8) })
        }
    }

    private fun updateTopBar() {
        if (!::titleView.isInitialized) return
        val ch = queue.getOrNull(index) ?: return
        val info = repo.state.value.settings.playerInfo
        val nowMs = System.currentTimeMillis()
        val text = SpannableStringBuilder()
        text.append(ch.name, StyleSpan(Typeface.BOLD), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (PlayerInfo.CLOCK in info) appendClock(text, nowMs)
        if (ch.kind == Kind.LIVE) {
            Epg.current(repo.epg.value[ch.epgKey], nowMs)?.let { now ->
                // 8.42: on its own line under the name, smaller, with the time left (8.45: each can be off).
                val left = ((now.stop - nowMs) / 60_000).coerceAtLeast(0)
                val line = listOfNotNull(
                    now.title.takeIf { PlayerInfo.NOW in info },
                    "$left min left".takeIf { PlayerInfo.LEFT in info && left > 0 },
                ).joinToString("  ·  ")
                if (line.isNotEmpty()) {
                    text.append("\n")
                    val start = text.length
                    text.append(line)
                    text.setSpan(ForegroundColorSpan(0xB3FFFFFF.toInt()), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text.setSpan(android.text.style.RelativeSizeSpan(0.82f), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        titleView.text = text
        clockShownAt = if (PlayerInfo.CLOCK in info) nowMs / 60_000 else -1
    }

    /** The minute the top bar's clock shows (-1: no clock); the tick redraws it when the minute turns. */
    private var clockShownAt = -1L

    /** "   21:42", quieter than the name it follows. */
    private fun appendClock(text: SpannableStringBuilder, now: Long) {
        text.append("   ")
        val start = text.length
        text.append(time(now))
        text.setSpan(ForegroundColorSpan(0xB3FFFFFF.toInt()), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(android.text.style.RelativeSizeSpan(0.85f), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Controls shown: the top says what's playing, so the zap banner (same place) goes. */
    private fun onControlsShown() {
        bannerView.visibility = View.GONE
        updateTopBar()
        // Clear of a phone's camera cut-out (the picture goes under it).
        val cut = androidx.core.view.ViewCompat.getRootWindowInsets(root)
            ?.getInsets(WindowInsetsCompat.Type.displayCutout())
        val side = dp(if (tvLayout) 48 else 12)
        topBar.setPadding(side + (cut?.left ?: 0), dp(if (tvLayout) 28 else 10) + (cut?.top ?: 0), side + (cut?.right ?: 0), 0)
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
            controls.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            // (Phones: the lock is in the top group since 8.42; it was alone at the left edge.)
            playerView.setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { visibility -> if (visibility == View.VISIBLE) onControlsShown() },
            )
        } else {
            // Fallback: a separate row at the bottom of the screen (and the top bar) that follow the controls.
            toolbar.visibility = View.GONE
            topBar.visibility = View.GONE
            root.addView(toolbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight, Gravity.BOTTOM))
            root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            playerView.setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { visibility ->
                    toolbar.visibility = if (inPip) View.GONE else visibility
                    topBar.visibility = if (inPip) View.GONE else visibility
                    if (visibility == View.VISIBLE) onControlsShown()
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

    // What the skip button says and where it jumps (8.25): a part from introdb.app (an add-on's
    // film or episode, see SkipSegments) or the show's learned intro.
    private var skipLabel by mutableStateOf("Skip intro")
    private var skipTarget = -1L
    /** "<channel id>|<kind>" of the looked-up part on offer; null for a learned intro. */
    private var skipPart: String? = null
    /** Parts skipped, or passed over with Back, in this session: not offered again. */
    private val skipsDone = HashSet<String>()
    private var skips: List<app.falak.player.data.SkipSegments.Part> = emptyList()
    private var skipsFor: String? = null
    private var skipsJob: kotlinx.coroutines.Job? = null

    /**
     * A film or episode starts: where its intro, recap and credits are, if introdb.app knows. An
     * add-on's has its IMDb number in its id; a provider's is found by its name (8.27, see
     * TitleMatch), only when it surely is that title.
     */
    private fun loadSkips(ch: Channel) {
        if (skipsFor == ch.id) return
        skipsJob?.cancel()
        skips = emptyList()
        skipsFor = ch.id
        val lookup = repo.state.value.settings.skipLookup
        if (!lookup || ch.id.startsWith("cu:")) return
        val addon = ch.id.startsWith("addon:")
        if (!addon && ch.kind != Kind.MOVIE && ch.kind != Kind.SERIES) return
        val show = if (!addon && ch.kind == Kind.SERIES) (repo.currentSeries?.name ?: episodeShow(ch.name)) else null
        val label = if (!addon && ch.kind == Kind.SERIES) episodeLabel(ch) else ""
        skipsJob = lifecycleScope.launch {
            val video = if (addon) ch.id.removePrefix("addon:") else withContext(Dispatchers.IO) { providerVideoId(ch, show, label) }
            val url = video?.let { app.falak.player.data.SkipSegments.url(it) } ?: return@launch
            val found = try {
                withContext(Dispatchers.IO) {
                    val request = okhttp3.Request.Builder().url(url).header("User-Agent", "ThePlayer/${app.falak.player.BuildConfig.VERSION_NAME}").build()
                    repo.http.newCall(request).execute().use { r ->
                        if (r.isSuccessful) app.falak.player.data.SkipSegments.parse(r.body?.string().orEmpty()) else emptyList()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (skipsFor == ch.id) skips = found
        }
    }

    /** "tt0133093" for a provider's film, "tt0903747:1:2" for its episode; null when the title isn't surely known. */
    private fun providerVideoId(ch: Channel, show: String?, label: String): String? {
        // (The year in the name, else the one the provider gave on its page.)
        if (ch.kind == Kind.MOVIE) {
            val (name, year) = app.falak.player.data.TitleMatch.clean(ch.name)
            return repo.imdbFor("movie", name, year ?: repo.providerYears["m" + ch.id.removePrefix("m")])
        }
        val se = Regex("S(\\d+) E(\\d+)").find(label) ?: return null
        val (name, year) = app.falak.player.data.TitleMatch.clean(show ?: return null)
        val sid = repo.currentSeries?.seriesId.orEmpty()
        val imdb = repo.imdbFor("series", name, year ?: repo.providerYears["s$sid"]) ?: return null
        return "$imdb:${se.groupValues[1].toInt()}:${se.groupValues[2].toInt()}"
    }

    /** A film or episode with no Up next card: the looked-up part playing now, else its show's learned intro. */
    private fun addonSkipTick(p: Player, ch: Channel) {
        // (Not over the loading page, or a picture that has stopped to load more.)
        if (p.playbackState != Player.STATE_READY) {
            skipIntroShown = false
            return
        }
        val dur = p.duration.coerceAtLeast(0)
        val pos = p.currentPosition
        val known = if (skipsFor == ch.id) skips else emptyList()
        val part = app.falak.player.data.SkipSegments.offer(known, pos, dur)?.takeIf { "${ch.id}|${it.kind}" !in skipsDone }
        if (part != null) {
            skipLabel = part.kind.label
            skipTarget = app.falak.player.data.SkipSegments.target(part, dur)
            skipPart = "${ch.id}|${part.kind}"
            skipIntroShown = true
            return
        }
        // (The list has no intro for it: the one learned from your own skip, as for the playlist's series.)
        val intro = if (known.any { it.kind == app.falak.player.data.SkipSegments.Kind.INTRO }) null else showKey(ch)?.let { repo.state.value.intros[it] }
        val show = intro != null && introSkippedFor != ch.id && dur > 0 && pos >= intro.start - 2_000 && pos < intro.end - 6_000
        if (show && intro != null) {
            skipLabel = "Skip intro"
            skipTarget = intro.end
            skipPart = null
        }
        skipIntroShown = show
    }

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
        // An add-on's episode ("addon:tt…:1:2"): its series, whichever add-on's link plays.
        if (ch.id.startsWith("addon:")) {
            val parts = ch.id.removePrefix("addon:").split(':')
            return if (parts.size >= 3) "addon|${parts[0]}" else null
        }
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
        if (p != null && ch != null && !inPip && (ch.id.startsWith("addon:") || (ch.kind == Kind.MOVIE && !ch.id.startsWith("cu:")))) {
            if (upNext != null) upNext = null
            addonSkipTick(p, ch)
            return
        }
        if (p == null || ch == null || ch.kind != Kind.SERIES || inPip) {
            if (upNext != null) upNext = null
            skipIntroShown = false
            return
        }
        val dur = p.duration
        val pos = p.currentPosition
        val next = nextEpisode()
        // Looked up (8.27): the intro and a recap get the button; the credits bring the Up next card
        // (or, after the last episode, "Skip credits").
        val listed = if (skipsFor == ch.id && p.playbackState == Player.STATE_READY) skips else emptyList()
        val part = app.falak.player.data.SkipSegments.offer(listed.filter { it.kind != app.falak.player.data.SkipSegments.Kind.OUTRO || next == null }, pos, dur.coerceAtLeast(0))
            ?.takeIf { "${ch.id}|${it.kind}" !in skipsDone }
        val intro = if (listed.any { it.kind == app.falak.player.data.SkipSegments.Kind.INTRO }) null else showKey(ch)?.let { repo.state.value.intros[it] }
        if (part != null) {
            skipLabel = part.kind.label
            skipTarget = app.falak.player.data.SkipSegments.target(part, dur.coerceAtLeast(0))
            skipPart = "${ch.id}|${part.kind}"
            skipIntroShown = true
        } else {
            skipIntroShown = intro != null && introSkippedFor != ch.id && dur > 0 &&
                pos >= intro.start - 2_000 && pos < intro.end - 6_000
            if (skipIntroShown && intro != null) {
                skipLabel = "Skip intro"
                skipTarget = intro.end
                skipPart = null
            }
        }
        val ended = p.playbackState == Player.STATE_ENDED
        // The credits: the last twenty seconds, or from where you went on to the next episode
        // yourself in this show before (learned, see learnOutro).
        val auto = repo.state.value.settings.autoNextEpisode
        val learnedOutro = showKey(ch)?.let { repo.state.value.intros[it + OUTRO_KEY] }?.start ?: 0L
        val listedOutro = listed.firstOrNull { it.kind == app.falak.player.data.SkipSegments.Kind.OUTRO && dur > 0 && it.start > dur / 2 }?.let { dur - it.start } ?: 0L
        val early = maxOf(learnedOutro, listedOutro)
        val lastStretch = ended || dur - pos <= UP_NEXT_MS
        val credits = dur > 5 * 60_000 && dur - pos <= maxOf(UP_NEXT_MS, early)
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
            // (Shown early because you skipped the credits before: it only offers. The countdown is
            // for the last stretch, as it was: left alone, the credits play on.)
            upNextSecs = if (auto && lastStretch) COUNTDOWN_S else null
            return
        }
        if (upNextSecs == null && auto && lastStretch) {
            upNextSecs = COUNTDOWN_S
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
        // (From the card: nothing new to learn about where the credits start.)
        outroOffered = true
        zap(1)
    }

    private fun dismissUpNext() {
        val ch = queue.getOrNull(index)
        // "Keep watching" on a card that came early: the credits of this show don't start there after all.
        val p = player
        if (ch != null && p != null && p.duration - p.currentPosition > UP_NEXT_MS + 2_000) {
            showKey(ch)?.let { key -> if (repo.state.value.intros[key + OUTRO_KEY] != null) repo.setIntro(key + OUTRO_KEY, 0, 0) }
        }
        upNextDismissed = ch?.id
        upNext = null
        upNextSecs = null
    }

    /** Set while the next episode is being started from the Up next card. */
    private var outroOffered = false

    /**
     * Learning where a show's credits start (8.25), as its intro is learned: going on to the next
     * episode yourself late in an episode, before the Up next card is there, is kept (as the time
     * left) and the card comes at that point in the show's next episodes. Kept with the intros,
     * under the show's key + [OUTRO_KEY] (its "start" is the time left).
     */
    private fun learnOutro() {
        if (outroOffered) {
            outroOffered = false
            return
        }
        val ch = queue.getOrNull(index) ?: return
        val p = player ?: return
        if (ch.kind != Kind.SERIES || ch.id.startsWith("addon:") || upNext != null || nextEpisode() == null) return
        val dur = p.duration
        val pos = p.currentPosition
        val left = dur - pos
        if (dur > 5 * 60_000 && pos > dur * 6 / 10 && left in (UP_NEXT_MS + 5_000)..(10 * 60_000L)) {
            showKey(ch)?.let { repo.setIntro(it + OUTRO_KEY, left, 0) }
        }
    }

    private fun skipIntro() {
        val ch = queue.getOrNull(index) ?: return
        if (skipTarget < 0) return
        // (A looked-up part isn't offered again; a learned intro, not again in this episode.)
        skipPart?.let { skipsDone += it } ?: run { introSkippedFor = ch.id }
        skipIntroShown = false
        skippingIntro = true
        player?.seekTo(skipTarget)
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
        // (The playlist's series, and an add-on's series when the list has no intro for it.)
        if (ch.kind != Kind.SERIES && showKey(ch) == null) return
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
        if (repo.state.value.settings.prayerMode == "pause" && dual != null) endDual(keep = 0, replay = false)
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
            hopTick()
            stillTick()
            linkTick()
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
                if (app.falak.player.record.RecordCenter.book.value.recordings.isNotEmpty()) add(MenuPlace("RECORDINGS", "Recordings"))
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
        // Settings, Recordings, What's new: a live channel carries on in the mini player meanwhile
        // (the guide has its own preview).
        if (where in setOf("SETTINGS", "NOTICES", "RECORDINGS", "DOWNLOADS")) offerMini(takeFocus = false)
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
        updateTopBar()
        val ch = queue.getOrNull(index)
        if (ch == null || !canFavorite(ch)) {
            favButton.visibility = View.GONE
            return
        }
        val fav = isFavorite(ch)
        favButton.visibility = View.VISIBLE
        // Just the star (filled when it's a favorite); read out as what it does.
        favButton.contentDescription = if (fav) "Remove from Favorites" else "Add to Favorites"
        setPillIcon(favButton, if (fav) R.drawable.ic_pl_star else R.drawable.ic_pl_star_outline)
    }

    private fun setPillIcon(b: TextView, icon: Int) {
        val size = dp(if (tvLayout) 20 else 18)
        ResourcesCompat.getDrawable(resources, icon, theme)?.mutate()?.let {
            it.setBounds(0, 0, size, size)
            b.setCompoundDrawablesRelative(it, null, null, null)
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
        // Live from the buffer: how far back it goes, where the picture is, and LIVE.
        liveBar = LiveTimeline(this, onSeek = { behind -> shiftToBehind(behind) }, onLive = { shiftToLive() }).apply {
            accent = accentInt
            visibility = View.GONE
            val pad = dp(if (tvLayout) 40 else 20)
            setPadding(pad, 0, pad, 0)
        }
        controls.addView(liveBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), Gravity.BOTTOM).apply { bottomMargin = dp(52) })
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
        // The controls are up: their top already says what's playing.
        if (playerView.isControllerFullyVisible && ::topBar.isInitialized) {
            updateTopBar()
            return
        }
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

        // What shows is picked in Settings → Player (8.45; all of it but the clock unless changed).
        val info = repo.state.value.settings.playerInfo
        val text = SpannableStringBuilder()
        if (ch.kind == Kind.LIVE && PlayerInfo.NUMBER in info) {
            text.append("${index + 1}   ", ForegroundColorSpan(accentInt), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text.append(ch.name, StyleSpan(Typeface.BOLD), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (PlayerInfo.CLOCK in info) appendClock(text, now)
        // The picture's real quality, from the video being played (not from the channel's name), and
        // how fast the stream is coming in.
        (if (PlayerInfo.QUALITY in info) listOfNotNull(qualityLabel(), rateLabel()).joinToString(" · ") else "").takeIf { it.isNotEmpty() }?.let { q ->
            text.append("   ")
            val start = text.length
            text.append(q)
            text.setSpan(ForegroundColorSpan(0xFFF2B33D.toInt()), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(android.text.style.RelativeSizeSpan(0.85f), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val showNow = PlayerInfo.NOW in info
        val showLeft = PlayerInfo.LEFT in info
        if (current != null && (showNow || showLeft)) {
            val left = ((current.stop - now) / 60_000).coerceAtLeast(0)
            val leftText = if (showLeft && left > 0) "$left min left" else ""
            text.append(
                if (showNow) "\nNow   ${time(current.start)}–${time(current.stop)}   ${current.title}" + (if (leftText.isNotEmpty()) "   ·   $leftText" else "")
                else if (leftText.isNotEmpty()) "\n$leftText" else "",
            )
        }
        if (next != null && PlayerInfo.NEXT in info) {
            text.append("\nNext  ${time(next.start)}   ${next.title}")
        }
        if (current == null && next == null && (showNow || PlayerInfo.NEXT in info)) text.append("\n${ch.group}")
        val tracks = if (PlayerInfo.TRACKS in info) trackSummary() else ""
        if (tracks.isNotEmpty()) text.append("\n$tracks")

        bannerView.text = text
    }

    /**
     * The page shown while a channel or film opens. It sits in the player view's own overlay: over
     * the picture (ExoPlayer's and VLC's, which is drawn underneath) and the small loading ring,
     * under the controls, the banner and the messages, which stay usable while it is up.
     */
    private fun buildLoadingView() {
        val name = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = ResourcesCompat.getFont(this@PlayerActivity, R.font.inter_semibold)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(28), dp(16), dp(28), 0)
        }
        val logo = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        val dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        loadingDots = List(3) {
            View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accentInt)
                }
                alpha = 0.28f
            }.also { dot -> dots.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { setMargins(dp(4), 0, dp(4), 0) }) }
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF0D0C0C.toInt())
            visibility = View.GONE
            addView(logo, LinearLayout.LayoutParams(dp(148), dp(96)))
            addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
            // The small window (picture in picture, the mini player) has room for the logo only.
            addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
                val small = bottom - top < dp(190)
                val want = if (small && loadingLogo.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                if (name.visibility != want) v.post { name.visibility = want }
            }
        }
        loadingLogo = logo
        loadingName = name
        loadingView = page
        (playerView.overlayFrameLayout ?: root).addView(
            page,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    /** The device has a working internet connection (not known: taken as yes). */
    private fun online(): Boolean = try {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        caps == null && cm == null || caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    } catch (e: Exception) {
        true
    }

    private fun showLoading(ch: Channel) {
        val page = loadingView ?: return
        loadingName.text = ch.name
        page.contentDescription = "Loading ${ch.name}"
        if (loadingLogoFor != ch.id) {
            loadingLogoFor = ch.id
            loadingLogoJob?.dispose()
            loadingLogo.setImageDrawable(null)
            loadingLogo.visibility = View.GONE
            if (ch.logo.isNotBlank()) {
                // (No logo, or its link fails: the name alone.)
                loadingLogoJob = coil.Coil.imageLoader(this).enqueue(
                    coil.request.ImageRequest.Builder(this)
                        .data(ch.logo)
                        .target(
                            onSuccess = { picture ->
                                loadingLogo.setImageDrawable(picture)
                                loadingLogo.visibility = View.VISIBLE
                            },
                        )
                        .build(),
                )
            }
        }
        page.animate().cancel()
        page.alpha = 1f
        page.visibility = View.VISIBLE
        handler.removeCallbacks(loadingTick)
        handler.post(loadingTick)
    }

    /** The picture is there (or nothing more is tried): the loading page fades away. */
    private fun hideLoading() {
        val page = loadingView ?: return
        handler.removeCallbacks(loadingTick)
        if (page.visibility != View.VISIBLE) return
        page.animate().alpha(0f).setDuration(220).withEndAction { page.visibility = View.GONE }.start()
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
        repeat(MAX_SCREENS) {
            val v = label()
            dualLabels += v
            root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        }
        dualHint = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tvLayout) 16f else 13f)
            typeface = ResourcesCompat.getFont(this@PlayerActivity, R.font.inter_medium)
            gravity = Gravity.CENTER
            text = dualHintText()
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
                    val at = pictureAt(e)
                    if (at >= 0 && at != heard) {
                        heard = at
                        dualChanged()
                    }
                    showDualHint()
                    return true
                }

                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    val at = pictureAt(e)
                    if (at >= 0) endDual(keep = at)
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
            addScreenButton = pill("Add", R.drawable.ic_pl_two) { startPickingSecond() }
            val exit = pill("One screen", R.drawable.ic_pl_close) { endDual() }
            for (b in listOf(channels, addScreenButton, exit)) {
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

    /** "Two screens" (and, once on, "Add a screen"): the channel list opens to pick the next picture's channel. */
    private fun startPickingSecond() {
        val ch = queue.getOrNull(index) ?: return
        if (ch.kind != Kind.LIVE || inPip || panes.size >= MAX_SCREENS - 1) return
        playerView.hideController()
        openBrowser()
        if (!browserOpen) return
        pickingSecond = true
        Toast.makeText(
            this,
            if (dual == null) "Pick a channel to watch beside “${ch.name}”" else "Pick a channel for the new screen",
            Toast.LENGTH_LONG,
        ).show()
    }

    /** The channels on screen now, main first. */
    private fun channelsOnScreen(): List<Channel?> = listOf(queue.getOrNull(index)) + panes.map { it.channel }

    /** A channel picked in the list: a new picture (when adding one), or the one being steered. */
    private fun pickForDual(list: List<Channel>, ch: Channel) {
        val at = list.indexOfFirst { it.id == ch.id }
        if (at < 0) return
        val adding = pickingSecond || dual == null
        if (adding) {
            if (channelsOnScreen().any { it?.id == ch.id }) {
                Toast.makeText(this, "That one is already on screen: pick another channel", Toast.LENGTH_SHORT).show()
                return
            }
            closeBrowser()
            addScreen(list, at)
            return
        }
        closeBrowser()
        val steered = heardPane
        if (steered != null) {
            steered.play(list, at)
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

    /** One more picture (two to four screens). */
    private fun addScreen(list: List<Channel>, at: Int) {
        if (panes.size >= MAX_SCREENS - 1) return
        val first = panes.isEmpty()
        // One picture's captions over several pictures would only confuse: off with more than one.
        if (first) stopAutoCaptions()
        val d = DualPane(
            this,
            repo.http,
            headersFor = { c -> headersFor(c) },
            preferHls = repo.state.value.settings.preferHls,
            onChange = { updateDualLabels() },
        )
        root.addView(
            d.container,
            root.indexOfChild(playerView) + 1 + panes.size,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        panes += d
        if (first) {
            heard = 0
            playerView.hideController()
            playerView.useController = false
            bannerView.visibility = View.GONE
            twoButton.visibility = View.GONE
            updateHopButton()
            updateHandoffButton()
        }
        d.play(list, at)
        dualChanged()
        showDualHint()
        FreezeWatch.note("screens: ${panes.size + 1}")
    }

    /**
     * Back to one screen. [keep]: the picture that goes full screen (0 = the main one); [replay] =
     * false only tidies up (the player is closing).
     */
    private fun endDual(keep: Int = heard, replay: Boolean = true) {
        if (panes.isEmpty()) return
        val kept = if (keep > 0) panes.getOrNull(keep - 1) else null
        val list = kept?.list
        val at = kept?.index ?: 0
        val keptChannel = kept?.channel
        for (d in panes) {
            d.release()
            root.removeView(d.container)
        }
        panes.clear()
        updatePipParams()
        heard = 0
        handler.removeCallbacks(hideDualHint)
        for (v in listOf(dualFrame, dualHint, touchLayer, dualBar) + dualLabels) v.visibility = View.GONE
        playerView.useController = !inPip
        applyDualVolume()
        applyVideoArea()
        FreezeWatch.note("screens: 1")
        if (!replay) return
        if (keptChannel != null && list != null) {
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
                updateHopButton()
                updateHandoffButton()
                showBanner(it)
            }
        }
        playerView.requestFocus()
    }

    /** The picture being heard changed (or a screen came or went). */
    private fun dualChanged() {
        if (heard > panes.size) heard = 0
        applyDualVolume()
        applyVideoArea()
        updateDualLabels()
        updatePipParams()
        if (::dualHint.isInitialized) dualHint.text = dualHintText()
        addScreenButton?.visibility = if (panes.size < MAX_SCREENS - 1) View.VISIBLE else View.GONE
    }

    /** Only the picture being heard makes a sound. */
    private fun applyDualVolume() {
        val mainHeard = heard == 0
        exo?.volume = if (mainHeard) 1f else 0f
        vlc?.setMuted(!mainHeard)
        panes.forEachIndexed { i, d -> d.setHeard(i + 1 == heard) }
    }

    /** How to use several screens (TV: the remote's keys). */
    private fun dualHintText(): String {
        val more = panes.size < MAX_SCREENS - 1
        return if (tvLayout) {
            if (panes.size <= 1) {
                "◀ ▶  choose the sound     ▲ ▼  change channel     OK  channel list" + (if (more) "     hold OK  add a screen" else "") + "     BACK  one screen"
            } else {
                "◀ ▶ ▲ ▼  choose the sound     CH+ CH−  change channel     OK  channel list" + (if (more) "     hold OK  add a screen" else "") + "     BACK  one screen"
            }
        } else {
            "Tap a picture for its sound · double-tap for full screen"
        }
    }

    /** Which picture a touch is on (0 = main), or −1. */
    private fun pictureAt(e: android.view.MotionEvent): Int {
        for (i in 0..panes.size) if (inRect(rects[i], e)) return i
        return -1
    }

    /**
     * Two pictures side by side (one above the other on an upright phone); three or four in a
     * 2×2 grid. The heard one is framed.
     */
    private fun layoutDual(w: Int, h: Int) {
        if (w == 0 || h == 0) return
        val gap = dp(if (tvLayout) 16 else 6)
        val none = intArrayOf(0, 0, 0, 0)
        val n = panes.size + 1
        val r = Array(n) { none }
        if (browserOpen) {
            // The list beside (or below) the picture being steered; the others wait out of sight.
            r[heard.coerceIn(0, n - 1)] = if (h > w) {
                intArrayOf(0, 0, w, w * 9 / 16)
            } else {
                val panel = dp(BrowserLayout.panelWidthDp(tvLayout, browserShowGroups))
                fit16x9((panel + gap).coerceAtMost(w - dp(120)), gap, w - gap, h - gap)
            }
        } else if (n == 2 && h > w) {
            val ph = w * 9 / 16
            val top = ((h - 2 * ph - gap) / 2).coerceAtLeast(0)
            r[0] = intArrayOf(0, top, w, top + ph)
            r[1] = intArrayOf(0, top + ph + gap, w, top + 2 * ph + gap)
        } else if (n == 2) {
            val pw = (w - 3 * gap) / 2
            val ph = pw * 9 / 16
            val top = ((h - ph) / 2).coerceAtLeast(0)
            r[0] = intArrayOf(gap, top, gap + pw, top + ph)
            r[1] = intArrayOf(2 * gap + pw, top, 2 * gap + 2 * pw, top + ph)
        } else if (h > w && n == 3) {
            // An upright phone, three pictures: one above the other, each the full width.
            val ph = w * 9 / 16
            val top = ((h - 3 * ph - 2 * gap) / 2).coerceAtLeast(0)
            for (i in 0 until n) r[i] = intArrayOf(0, top + i * (ph + gap), w, top + i * (ph + gap) + ph)
        } else {
            // A 2×2 grid: main top left, then top right, bottom left, bottom right (kept together
            // in the middle of an upright phone).
            val cw = (w - 3 * gap) / 2
            val ch = if (h > w) cw * 9 / 16 else (h - 3 * gap) / 2
            val top = if (h > w) ((h - 2 * ch - gap) / 2).coerceAtLeast(0) else gap
            for (i in 0 until n) {
                val l = gap + (i % 2) * (cw + gap)
                val t = top + (i / 2) * (ch + gap)
                r[i] = fit16x9(l, t, l + cw, t + ch)
            }
        }
        for (i in 0 until MAX_SCREENS) rects[i] = r.getOrNull(i) ?: none
        for (v in listOfNotNull<View>(playerView, vlcLayout, messageView)) placeIn(v, r[0], w, h)
        panes.forEachIndexed { i, d -> placeIn(d.container, r[i + 1], w, h) }
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        vlc?.setScale(0)
        val heardRect = r[heard.coerceIn(0, n - 1)]
        val showing = !browserOpen
        // The frame sits just outside the heard picture.
        val pad = dp(4)
        placeIn(dualFrame, intArrayOf(heardRect[0] - pad, heardRect[1] - pad, heardRect[2] + pad, heardRect[3] + pad), w, h)
        dualFrame.visibility = if (showing) View.VISIBLE else View.GONE
        dualLabels.forEachIndexed { i, label ->
            val rect = r.getOrNull(i) ?: none
            val lp = label.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = rect[0] + dp(10)
            // A picture reaching the bottom of the screen (three or four screens) has its name at its top:
            // the keys' hint (TV) and the buttons (phone) sit along the bottom and covered it.
            if (h - rect[3] < dp(120) && rect[1] > dp(40)) {
                lp.gravity = Gravity.TOP or Gravity.START
                lp.topMargin = rect[1] + dp(10)
                lp.bottomMargin = 0
            } else {
                lp.gravity = Gravity.BOTTOM or Gravity.START
                lp.topMargin = 0
                lp.bottomMargin = (h - rect[3]) + dp(10)
            }
            label.layoutParams = lp
            label.maxWidth = (rect[2] - rect[0] - dp(20)).coerceAtLeast(dp(80))
            label.visibility = if (showing && rect[2] > rect[0]) View.VISIBLE else View.GONE
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

    /** OK went down on the remote (several screens): held, it adds a screen; let go, the channel list. */
    private var dualOkHeld = false

    /**
     * The remote with several screens. Two: Left/Right choose the picture heard, Up/Down (and CH±)
     * change its channel. Three or four (a 2×2 grid): the arrows choose the picture, CH± change
     * its channel. OK opens the channel list for it, OK held adds a screen, Back goes back to one
     * screen (the one heard).
     */
    private fun dualKey(event: KeyEvent): Boolean {
        val grid = panes.size >= 2
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (event.repeatCount == 0) endDual()
                    swallowUpKey = event.keyCode
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val right = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    val next = if (grid) {
                        // The other picture in the same row (when there is one).
                        val col = heard % 2
                        val target = heard - col + (if (right) 1 else 0)
                        if (target <= panes.size) target else heard
                    } else {
                        if (right) 1 else 0
                    }
                    if (next != heard) {
                        heard = next
                        dualChanged()
                    }
                    showDualHint()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (grid) {
                        val down = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        val target = (heard % 2) + (if (down) 2 else 0)
                        val next = when {
                            target <= panes.size -> target
                            down && 2 <= panes.size -> 2 // three screens: the one below on the left
                            else -> heard
                        }
                        if (next != heard) {
                            heard = next
                            dualChanged()
                        }
                        showDualHint()
                    } else if (event.repeatCount == 0) {
                        dualZap(if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) 1 else -1)
                    }
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (event.repeatCount == 0) dualZap(1)
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (event.repeatCount == 0) dualZap(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.repeatCount == 0) {
                        dualOkHeld = false
                    } else if (!dualOkHeld) {
                        // Held: a channel for one more screen.
                        dualOkHeld = true
                        swallowUpKey = event.keyCode
                        if (panes.size < MAX_SCREENS - 1) startPickingSecond()
                    }
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
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
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    // A short press: the channel list for the picture heard.
                    if (!dualOkHeld) openBrowserForDual()
                    dualOkHeld = false
                    return true
                }
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
        if (dual == null) return
        val steered = heardPane
        if (steered != null) {
            steered.zap(delta)
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
        if (dual == null) return
        openBrowser(heardPane?.channel)
    }

    /** Each picture's channel and what's on, the heard one marked. */
    private fun updateDualLabels() {
        if (dual == null || dualLabels.isEmpty()) return
        val now = System.currentTimeMillis()
        val epg = repo.epg.value
        fun line(ch: Channel?, isHeard: Boolean): String {
            if (ch == null) return ""
            val on = epg[ch.epgKey]?.firstOrNull { now >= it.start && now < it.stop }?.title
            return (if (isHeard) "🔊  " else "") + ch.name + (on?.let { "  ·  $it" } ?: "")
        }
        val shown = channelsOnScreen()
        dualLabels.forEachIndexed { i, label ->
            label.text = line(shown.getOrNull(i), i == heard)
            label.alpha = if (i == heard) 1f else 0.75f
        }
    }

    /** How to use several screens, for a few seconds. */
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
    internal fun previewDual(main: Player, second: Player, secondChannel: Channel, heardSecond: Boolean) =
        previewScreens(main, listOf(second to secondChannel), if (heardSecond) 1 else 0)

    /** Screenshot tests: several screens (the main one and [others]) with stand-in players, [heardAt] heard. */
    internal fun previewScreens(main: Player, others: List<Pair<Player, Channel>>, heardAt: Int) {
        playerView.player = main
        for ((stand, ch) in others) {
            val d = DualPane(this, repo.http, { c -> headersFor(c) }, false) { updateDualLabels() }
            root.addView(
                d.container,
                root.indexOfChild(playerView) + 1 + panes.size,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            panes += d
            d.previewWith(stand, listOf(ch), 0)
        }
        heard = heardAt
        playerView.hideController()
        playerView.useController = false
        bannerView.visibility = View.GONE
        dualChanged()
        dualHint.visibility = View.VISIBLE
        handler.removeCallbacks(hideDualHint)
    }

    // ---- a stream that freezes is started again (8.31, see FreezeRule) ----

    private val freezeRule = app.falak.player.player.FreezeRule()
    /** (Screen tests: their stand-in players never move; nothing is started again there.) */
    private val FREEZE_OFF = android.os.Build.FINGERPRINT == "robolectric"
    private val freezeTick = object : Runnable {
        override fun run() {
            checkPicture()
            checkFrozen()
            handler.postDelayed(this, 2_000)
        }
    }

    /** A picture was drawn for the item playing (ExoPlayer); when sound began without one. */
    private var frameShown = false
    private var soundSinceMs = 0L

    /**
     * Sound and subtitles but a black screen (8.51, the owner): ExoPlayer took the picture but never
     * drew a frame (a decoder that accepts it and shows nothing: some HEVC 10-bit, Dolby Vision, MPEG-2
     * or 4K on boxes that can't). After [NO_PICTURE_MS] of sound the item moves to VLC, at the same place.
     */
    private fun checkPicture() {
        // Only while the picture is on screen: in the background (Listen mode, screen off) there is none to draw.
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            soundSinceMs = 0L
            vlcSoundSinceMs = 0L
            return
        }
        vlc?.let { checkVlcPicture(it) }
        val p = exo ?: return
        val ch = queue.getOrNull(index) ?: return
        if (engine == Engine.VLC || frameShown || castingTo != null || dual != null || NoPicture.has(ch.id)) return
        val hasVideo = p.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
        if (!hasVideo || !p.isPlaying) {
            soundSinceMs = 0L
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (soundSinceMs == 0L) {
            soundSinceMs = now
            return
        }
        if (now - soundSinceMs >= NO_PICTURE_MS) noPictureSwitch(ch, "no frame in ${(now - soundSinceMs) / 1000} s")
    }

    /**
     * VLC with the hardware decoder and still no picture output after [NO_PICTURE_MS] of sound (8.52:
     * VLC used the same decoder that showed black): the title plays again with VLC decoding it itself.
     */
    private var vlcSoundSinceMs = 0L

    private fun checkVlcPicture(v: VlcPlayer) {
        val ch = queue.getOrNull(index) ?: return
        if (NoPicture.has(ch.id) || castingTo != null || dual != null || !v.isPlaying || v.vouts > 0 || !v.hasVideo()) {
            vlcSoundSinceMs = 0L
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (vlcSoundSinceMs == 0L) {
            vlcSoundSinceMs = now
            return
        }
        if (now - vlcSoundSinceMs < NO_PICTURE_MS) return
        vlcSoundSinceMs = 0L
        NoPicture.note(ch.id, null)
        FreezeWatch.note("no picture with VLC's hardware decoding: VLC decoding itself")
        if (ch.kind != Kind.LIVE || ch.id.startsWith("cu:")) resumePositionMs = v.currentPosition
        saveProgress()
        showMessage("No picture · Trying again…")
        playCurrent(showInfo = false)
    }

    private fun noPictureSwitch(ch: Channel, why: String, picture: androidx.media3.common.Format? = null) {
        if (NoPicture.has(ch.id) || engine == Engine.VLC || queue.getOrNull(index)?.id != ch.id) return
        NoPicture.note(ch.id, picture ?: exo?.videoFormat)
        FreezeWatch.note("no picture with ${engine.short} ($why): VLC")
        player?.let { p -> if (ch.kind != Kind.LIVE || ch.id.startsWith("cu:")) resumePositionMs = p.currentPosition }
        saveProgress()
        retried = false
        showMessage("No picture with this player · Trying VLC…")
        playCurrent(showInfo = false)
    }

    private fun checkFrozen() {
        val p = player ?: return
        val ch = queue.getOrNull(index) ?: return
        val state = p.playbackState
        // A torrent waits for its pieces (its own window says how it's going): not a freeze.
        val torrent = ch.id.startsWith("addon:") && currentUrl.contains("127.0.0.1")
        val watching = p.playWhenReady && castingTo == null && dual == null && !torrent &&
            state != Player.STATE_IDLE && state != Player.STATE_ENDED
        val live = ch.kind == Kind.LIVE && !ch.id.startsWith("cu:") && liveBeforeStartOver == null
        val action = freezeRule.step(
            now = android.os.SystemClock.elapsedRealtime(),
            itemId = ch.id,
            watching = watching,
            buffering = state == Player.STATE_BUFFERING,
            ready = state == Player.STATE_READY,
            playing = p.isPlaying,
            position = p.currentPosition,
            moves = exo != null,
            started = wasReady,
            live = live,
        )
        when (action) {
            app.falak.player.player.FreezeRule.Action.RESTART -> {
                FreezeWatch.note("stuck: started again")
                showMessage("Reconnecting…")
                retried = false
                if (live) {
                    handler.post { playCurrent(showInfo = false) }
                } else if (exo != null) {
                    // A film or episode: opened again where it was.
                    val at = p.currentPosition
                    p.stop()
                    p.prepare()
                    p.seekTo(at)
                    p.playWhenReady = true
                }
            }
            app.falak.player.player.FreezeRule.Action.GIVE_UP -> {
                FreezeWatch.note("stuck: gave up")
                val what = if (ch.kind == Kind.LIVE) "channel" else "video"
                val hint = if (engine != Engine.VLC) "\nTry another channel, or the ${engine.short} button below → VLC" else "\nTry another channel"
                showMessage("This $what keeps freezing$hint")
            }
            app.falak.player.player.FreezeRule.Action.NONE -> {}
        }
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

    // ---- half-time hop (8.42) ----

    /** Zapped away for a while: back to [id] (the [at]th of [list]) at [until] (wall clock). */
    private class Hop(val list: List<Channel>, val at: Int, val id: String, val name: String, val until: Long)

    private var hop: Hop? = null

    private fun updateHopButton() {
        if (!::hopButton.isInitialized) return
        val ch = queue.getOrNull(index)
        val live = ch != null && ch.kind == Kind.LIVE && !ch.id.startsWith("cu:")
        val h = hop
        val v = if (h != null || (live && dual == null && !inPip)) View.VISIBLE else View.GONE
        val changed = hopButton.visibility != v
        hopButton.visibility = v
        val label = if (h == null) {
            HOP_LABEL
        } else {
            val left = ((h.until - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            "Back in " + if (left >= 60) "${(left + 59) / 60} min" else "$left s"
        }
        if (hopButton.tag != label) setPillLabel(hopButton, label)
        if (changed && ::sleepButton.isInitialized) {
            if (tvLayout) fitTvPills() else fitPills()
        }
    }

    private fun hopClicked() {
        val h = hop
        if (h != null) {
            android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Back to ${h.name} at ${time(h.until)}")
                .setItems(arrayOf("Go back now", "Stay here (cancel)")) { d, which ->
                    d.dismiss()
                    if (which == 0) hopBack() else hop = null
                    updateHopButton()
                }
                .setNegativeButton("Close", null)
                .show()
            return
        }
        val ch = queue.getOrNull(index) ?: return
        val wall = System.currentTimeMillis()
        val choices = ArrayList<Pair<String, Long>>()
        for (m in listOf(5, 10, 15, 20)) choices += "In $m minutes" to wall + m * 60_000L
        // The guide: when this channel's next programme starts (the match after the studio talk).
        val progs = repo.epg.value[ch.epgKey]
        val nextStart = progs?.firstOrNull { wall >= it.start && wall < it.stop }?.stop ?: progs?.firstOrNull { it.start > wall }?.start
        val next = progs?.firstOrNull { it.start == nextStart }
        if (nextStart != null && nextStart - wall in 60_000L..4 * 3_600_000L) {
            choices += (if (next != null) "When “${next.title}” starts (${time(nextStart)})" else "When the next programme starts (${time(nextStart)})") to nextStart
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Watch something else, then back to ${ch.name}")
            .setItems(choices.map { it.first }.toTypedArray()) { d, which ->
                d.dismiss()
                val until = choices[which].second
                hop = Hop(queue, index, ch.id, ch.name, until)
                updateHopButton()
                Toast.makeText(this, "Back to ${ch.name} at ${time(until)}. Change channel as you like.", Toast.LENGTH_LONG).show()
                openBrowser()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Once a second: the time left on the button, and back when it's time. */
    private fun hopTick() {
        val h = hop ?: return
        if (System.currentTimeMillis() >= h.until) hopBack() else updateHopButton()
    }

    private fun hopBack() {
        val h = hop ?: return
        hop = null
        updateHopButton()
        // Already there, stopped by the sleep timer or "Still watching?": nothing to do.
        if (queue.getOrNull(index)?.id == h.id || sleptByTimer || h.at !in h.list.indices) return
        saveProgress()
        rememberPrevious()
        queue = h.list
        repo.playQueue = h.list
        index = h.at
        retried = false
        resumePositionMs = C.TIME_UNSET
        playCurrent(showInfo = true)
        Toast.makeText(this, "Back to ${h.name}", Toast.LENGTH_SHORT).show()
    }

    // ---- hand-off and the second screen (8.42) ----

    /** What plays here, as a linked device would play it (null for what only this device can open). */
    private fun handItem(): app.falak.player.data.HandItem? {
        val ch = queue.getOrNull(index) ?: return null
        val pl = repo.currentPlaylist ?: return null
        if (!app.falak.player.data.Handoff.canHand(ch, pl.isPortal)) return null
        val p = player
        val live = ch.kind == Kind.LIVE && !ch.id.startsWith("cu:")
        val state = repo.state.value
        val seriesId = repo.currentSeries?.seriesId?.takeIf { ch.kind == Kind.SERIES }
            ?: state.progress[progressKey(state.mine(pl.id), ch.id)]?.seriesId.orEmpty()
        return app.falak.player.data.HandItem(
            playlist = app.falak.player.data.SyncMerge.syncId(state, pl.id),
            channel = ch,
            pos = if (live) 0 else p?.currentPosition?.coerceAtLeast(0) ?: 0,
            dur = if (live) 0 else p?.duration?.takeIf { it > 0 } ?: 0,
            seriesId = seriesId,
        )
    }

    private fun updateHandoffButton() {
        if (!::handoffButton.isInitialized) return
        val sync = autoSync
        val ch = queue.getOrNull(index)
        val show = ch != null && dual == null && castingTo == null && !inPip && sync.runningKey != null && sync.anyNearby &&
            app.falak.player.data.Handoff.canHand(ch, repo.currentPlaylist?.isPortal == true)
        val v = if (show) View.VISIBLE else View.GONE
        if (handoffButton.visibility != v) {
            handoffButton.visibility = v
            if (tvLayout) fitTvPills() else fitPills()
        }
    }

    private fun handoffClicked() {
        val devices = autoSync.nearby()
        when (devices.size) {
            0 -> toast("No linked device found on this Wi-Fi. Open The Player on it and try again.")
            1 -> handTo(devices[0].first, devices[0].second)
            else -> android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Play this on")
                .setItems(devices.map { it.first }.toTypedArray()) { d, which ->
                    d.dismiss()
                    handTo(devices[which].first, devices[which].second)
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    /** Sends what plays to [name]; once it plays there, it stops here. */
    private fun handTo(name: String, address: String) {
        val item = handItem() ?: return toast("This can't play on another device")
        saveProgress()
        Toast.makeText(this, "Sending to $name…", Toast.LENGTH_SHORT).show()
        val sync = autoSync
        Thread({
            val answer = sync.ask(address, app.falak.player.data.LinkAsk("play", System.currentTimeMillis(), item = item))
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (answer.ok) {
                    Toast.makeText(this, "Playing on $name", Toast.LENGTH_SHORT).show()
                    FreezeWatch.note("handed to another device")
                    finish()
                } else {
                    toast("$name: ${answer.message}")
                }
            }
        }, "handoff").start()
    }

    /** Once a second: what plays, for a linked phone's second screen; the hand-off button. */
    private fun linkTick() {
        updateHandoffButton()
        // The top bar's clock (8.45), when the minute turns while the controls are up.
        if (clockShownAt >= 0 && clockShownAt != System.currentTimeMillis() / 60_000 && playerView.isControllerFullyVisible) updateTopBar()
        val item = handItem()
        if (item == null) {
            repo.handNow = null
            return
        }
        val ch = item.channel
        val wall = System.currentTimeMillis()
        val progs = if (ch.kind == Kind.LIVE) repo.epg.value[ch.epgKey] else null
        val now = progs?.firstOrNull { wall >= it.start && wall < it.stop }
        val next = progs?.firstOrNull { it.start >= (now?.stop ?: wall) }
        repo.handNow = app.falak.player.data.NowState(
            item = item,
            playing = player?.playWhenReady == true && !sleptByTimer,
            now = now?.let { app.falak.player.data.HandProgram(it.start, it.stop, it.title, it.desc.take(600)) },
            next = next?.let { app.falak.player.data.HandProgram(it.start, it.stop, it.title, it.desc.take(300)) },
        )
    }

    // ---- still watching? (8.42) ----

    /** The last key or touch (elapsed time). */
    private var lastTouchMs = android.os.SystemClock.elapsedRealtime()
    private var stillDialog: android.app.AlertDialog? = null
    private var stillLeft = 0

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastTouchMs = android.os.SystemClock.elapsedRealtime()
    }

    /** Once a second: hours without a key or touch → ask; a minute without an answer → stop. */
    private fun stillTick() {
        val d = stillDialog
        if (d != null) {
            stillLeft--
            if (stillLeft <= 0) {
                stillDialog = null
                runCatching { d.dismiss() }
                stillStop()
            } else {
                d.setMessage(stillText())
            }
            return
        }
        val hours = repo.state.value.settings.stillWatchingHours
        val now = android.os.SystemClock.elapsedRealtime()
        // Paused, stopped or sent to a Chromecast: nothing is used up here; the count starts again.
        if (hours <= 0 || sleptByTimer || castingTo != null || inPip || player?.playWhenReady != true) {
            lastTouchMs = now
            return
        }
        if (now - lastTouchMs < hours * 3_600_000L) return
        showStillDialog()
    }

    private fun showStillDialog() {
        stillLeft = STILL_ANSWER_S
        stillDialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Still watching?")
            .setMessage(stillText())
            .setPositiveButton("Keep watching") { _, _ ->
                stillDialog = null
                lastTouchMs = android.os.SystemClock.elapsedRealtime()
            }
            .setNegativeButton("Stop") { _, _ ->
                stillDialog = null
                stillStop()
            }
            .setOnCancelListener {
                // Back: as Keep watching (someone is there).
                stillDialog = null
                lastTouchMs = android.os.SystemClock.elapsedRealtime()
            }
            .show()
    }

    private fun stillText() = "Nothing pressed for a while. It stops in $stillLeft s to save your data and your provider's connection."

    private fun stillStop() {
        FreezeWatch.note("still watching: no answer, stopped")
        if (dual != null) endDual(keep = 0, replay = false)
        stopForNow("Stopped: nobody seemed to be watching\n${if (tvLayout) "OK" else "Play"} carries on")
        lastTouchMs = android.os.SystemClock.elapsedRealtime()
    }

    // ---- sleep timer ----

    /** Stopped by the sleep timer: the message is up and OK carries on. */
    private var sleptByTimer = false

    /** The fade's volume as last set (1 = full). */
    private var sleepVolume = 1f

    private fun updateSleepButton() {
        if (!::sleepButton.isInitialized) return
        val label = SleepTimer.buttonLabel(android.os.SystemClock.elapsedRealtime())
        if (sleepButton.tag != label) setPillLabel(sleepButton, label)
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
        stopForNow("Stopped by the sleep timer\n${if (tvLayout) "OK" else "Play"} carries on")
    }

    /** What plays stops (a live channel lets go of its connection), [message] shows, and OK carries on. */
    private fun stopForNow(message: String) {
        saveProgress()
        val ch = queue.getOrNull(index)
        val p = player
        if (ch != null && ch.kind == Kind.LIVE && !ch.id.startsWith("cu:")) {
            p?.stop()
            releaseShift()
        } else {
            p?.pause()
        }
        stopAutoCaptions()
        if (dual == null) setSleepVolume(1f)
        sleptByTimer = true
        playerView.keepScreenOn = false
        playerView.hideController()
        showMessage(message)
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

    // ---- pause & rewind live TV ----

    /** Plays [ch] from the buffer when it can: the address to play, or null to play [url] directly. */
    private fun openShift(ch: Channel, url: String, ua: String, saved: Boolean): String? {
        val s = repo.state.value.settings
        if (s.liveBuffer != "on" || ch.kind != Kind.LIVE || ch.id.startsWith("cu:") || ch.id.startsWith("rec:") || saved) return null
        if (dual != null || ch.id in noShift || url.contains(".mpd", ignoreCase = true) || !url.startsWith("http")) return null
        return try {
            val t = app.falak.player.record.Timeshift.open(this, url, ua, ch.referrer, s.liveBufferMinutes)
            shift = t
            handler.removeCallbacks(shiftTick)
            handler.postDelayed(shiftTick, 300)
            FreezeWatch.note("live from the buffer")
            t.live()
        } catch (e: Exception) {
            shift = null
            null
        }
    }

    /** The buffer goes (a recording of the same channel keeps its connection). */
    private fun releaseShift() {
        val t = shift ?: return
        shift = null
        handler.removeCallbacks(shiftTick)
        runCatching { t.release() }
        updateShiftViews()
    }

    /** Back or forward [deltaMs] in the buffer; catching up with live goes to live. */
    private fun shiftBy(deltaMs: Long) {
        val t = shift ?: return
        val p = player ?: return
        val now = System.currentTimeMillis()
        val target = t.showingAt(p.currentPosition) + deltaMs
        if (target >= now - 5_000) {
            shiftToLive()
            return
        }
        val oldest = t.oldest()
        val at = if (oldest != null) maxOf(target, oldest) else target
        reopenShift(t.at(at))
        val edge = if (oldest != null && target < oldest) "  ·  as far back as it goes" else ""
        showShiftNote((if (deltaMs < 0) "⏪  " else "⏩  ") + clock((now - at).coerceAtLeast(0)) + " behind live" + edge)
    }

    /** To [behind] ms behind live (the live bar tapped or dragged on a phone). */
    private fun shiftToBehind(behind: Long) {
        val t = shift ?: return
        val p = player ?: return
        val now = System.currentTimeMillis()
        shiftBy((now - behind) - t.showingAt(p.currentPosition))
    }

    /** From a paused or rewound live channel to what's on now. */
    private fun shiftToLive() {
        val t = shift ?: return
        reopenShift(t.live())
        showShiftNote("Live")
    }

    /** Opens the buffer at another point (the rest of the player stays as it is). */
    private fun reopenShift(url: String) {
        val ch = queue.getOrNull(index) ?: return
        val v = vlc
        if (v != null) {
            v.load(url, ch.name, headersFor(ch).first, ch.referrer, 0L, hardware = true)
            updateShiftViews()
            return
        }
        val p = exo ?: return
        val ds = currentDataSource ?: return
        val ex = currentExtractors ?: return
        val item = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(ch.name).build())
            .build()
        currentItem = item
        p.setMediaSource(DefaultMediaSourceFactory(ds, ex).createMediaSource(item))
        p.prepare()
        p.playWhenReady = true
        updateShiftViews()
    }

    private fun showShiftNote(text: String) {
        if (!::seekView.isInitialized || pendingSeek != null) return
        seekView.text = text
        seekView.visibility = View.VISIBLE
        handler.removeCallbacks(hideSeek)
        handler.postDelayed(hideSeek, 1_800)
    }

    /** "Live · −1:20" on the button while paused or rewound; "Back to live" after From the start. */
    private fun updateShiftViews() {
        if (!::liveButton.isInitialized) return
        val t = shift
        val p = player
        val behind = if (t != null && p != null) t.behind(p.currentPosition) else 0L
        val label = when {
            liveBeforeStartOver != null -> "Back to live"
            t != null && behind > 10_000 -> "Live  ·  −" + clock(behind)
            else -> null
        }
        liveButton.visibility = if (label != null) View.VISIBLE else View.GONE
        if (label != null && liveButton.tag != label) setPillLabel(liveButton, label)
        // The live bar: playing from the buffer, on a live channel (not a show from its start).
        if (::liveBar.isInitialized) {
            val bar = t != null && p != null && liveBeforeStartOver == null && queue.getOrNull(index)?.kind == Kind.LIVE && !inPip
            liveBar.visibility = if (bar) View.VISIBLE else View.GONE
            if (bar && t != null) {
                val now = System.currentTimeMillis()
                val window = repo.state.value.settings.liveBufferMinutes.coerceAtLeast(1) * 60_000L
                val held = t.oldest()?.let { now - it } ?: 0L
                liveBar.set(window, held, behind)
            }
        }
    }

    // ---- recording ----

    /** Live TV: the Record button, and "Recording" while this channel is being recorded. */
    private fun updateRecordButton() {
        if (!::recordButton.isInitialized) return
        val ch = queue.getOrNull(index)
        val show = ch != null && ch.kind == Kind.LIVE && !ch.id.startsWith("cu:") && dual == null &&
            repo.currentPlaylist != null && !repo.kidsActive && !inPip
        recordButton.visibility = if (show) View.VISIBLE else View.GONE
        if (ch == null || !show) return
        val label = if (app.falak.player.record.RecordCenter.busyNow(ch.id) != null) "● Recording" else "Record"
        if (recordButton.tag != label) setPillLabel(recordButton, label)
    }

    /** Record this channel: the programme on now (from its start when the buffer has it), or for a while; or stop. */
    private fun showRecordDialog() {
        val ch = queue.getOrNull(index) ?: return
        val pl = repo.currentPlaylist ?: return
        if (ch.kind != Kind.LIVE || ch.id.startsWith("cu:")) return
        val rc = app.falak.player.record.RecordCenter
        val busy = rc.busyNow(ch.id)
        if (busy != null) {
            android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Recording “${busy.title}”")
                .setMessage(
                    "Since ${time(busy.startedAt.takeIf { it > 0 } ?: busy.start)}, until ${time(busy.stop)}  ·  " +
                        app.falak.player.OfflineCenter.sizeLabel(busy.size),
                )
                .setPositiveButton("Stop recording") { _, _ ->
                    rc.stop(busy.id)
                    toast("Saved in Recordings")
                }
                .setNegativeButton("Keep recording", null)
                .setNeutralButton("Recordings") { _, _ -> leaveTo("RECORDINGS") }
                .show()
            return
        }
        val now = System.currentTimeMillis()
        val late = repo.state.value.settings.recordLate.coerceIn(0, 60) * 60_000L
        val prog = repo.epg.value[ch.epgKey]?.firstOrNull { now >= it.start && now < it.stop }
        val choices = ArrayList<Pair<String, () -> Unit>>()
        if (prog != null && prog.stop > now + 60_000L) {
            // From its start when the buffer still has it.
            val fromStart = shift?.oldest()?.let { it <= prog.start } == true
            choices += "“${prog.title}” until ${time(prog.stop + late)}" + (if (fromStart) " (from its start)" else "") to {
                startRecording(pl, ch, prog, prog.stop + late)
            }
        }
        for ((label, minutes) in listOf("30 minutes" to 30, "1 hour" to 60, "2 hours" to 120, "3 hours" to 180)) {
            choices += "For $label" to { startRecording(pl, ch, null, System.currentTimeMillis() + minutes * 60_000L) }
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Record ${ch.name}")
            .setItems(choices.map { it.first }.toTypedArray()) { d, which ->
                d.dismiss()
                choices[which].second()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun startRecording(pl: app.falak.player.data.Playlist, ch: Channel, prog: app.falak.player.data.Program?, until: Long) {
        val r = app.falak.player.record.RecordCenter.recordNow(pl, ch, prog, until)
        if (r == null) {
            toast("This channel can't be recorded")
            return
        }
        FreezeWatch.note("record now")
        toast("Recording “${r.title}” until ${time(r.stop)}. It's saved in Recordings.")
        updateRecordButton()
    }

    /** A recording of another channel uses a connection: said once (many providers allow only one). */
    private fun hintRecording(ch: Channel, url: String) {
        if (recordHintShown || ch.kind != Kind.LIVE) return
        val rec = app.falak.player.record.RecordCenter.book.value.recordings.firstOrNull {
            it.state == app.falak.player.record.RecState.RECORDING && it.channelId != ch.id && it.url != url
        } ?: return
        recordHintShown = true
        toast("Recording “${rec.title}” on ${rec.channelName}. If your subscription allows one connection, this channel may not play until it ends.")
    }

    // ---- remote buttons given a job (Settings → Remote buttons) ----

    /** Does what [job] says; false when it doesn't apply here (the button does what it usually does). */
    private fun runKeyAction(job: String): Boolean {
        val ch = queue.getOrNull(index)
        val live = ch != null && ch.kind == Kind.LIVE && !ch.id.startsWith("cu:")
        when (job) {
            KeyActions.CHANNELS -> openBrowser()
            KeyActions.GUIDE -> leaveTo("GUIDE")
            KeyActions.FAVORITE -> if (ch != null && canFavorite(ch)) toggleFavorite(ch) else return false
            KeyActions.RECORD -> if (live) showRecordDialog() else return false
            KeyActions.RECORDINGS -> leaveTo("RECORDINGS")
            KeyActions.AUDIO -> showTrackDialog(C.TRACK_TYPE_AUDIO)
            KeyActions.SUBTITLES -> showTrackDialog(C.TRACK_TYPE_TEXT)
            KeyActions.PICTURE -> cycleAspect()
            KeyActions.LAST -> recallPrevious()
            KeyActions.TWO -> if (live && dual == null) startPickingSecond() else return false
            KeyActions.SLEEP -> showSleepDialog()
            KeyActions.REWIND -> when {
                canSeek() -> seekBy(-30_000L)
                live && shift != null -> shiftBy(-30_000L)
                else -> return false
            }
            KeyActions.FORWARD -> when {
                canSeek() -> seekBy(30_000L)
                live && shift != null -> shiftBy(30_000L)
                else -> return false
            }
            KeyActions.LIVE -> when {
                liveBeforeStartOver != null -> backToLive()
                shift != null -> shiftToLive()
                else -> return false
            }
            KeyActions.PLAY_PAUSE -> {
                val p = player ?: return false
                if (p.isPlaying) p.pause() else p.play()
            }
            KeyActions.INFO -> if (ch != null) showBanner(ch) else return false
            KeyActions.MENU -> showAppMenu()
            KeyActions.PLAYER -> showEngineDialog()
            else -> return false
        }
        return true
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
            if (exoSynced) "" else " (Settings → Sound & subtitles → Subtitle style: In time with the speech)"
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
    // ---- the screen lock (phones, 8.28) ----

    /** Touches do nothing until the lock is pressed again (Back too); the picture and sound go on. */
    private fun lockScreen() {
        if (locked) return
        locked = true
        playerView.hideController()
        playerView.useController = false
        val shield = FrameLayout(this).apply {
            isClickable = true
            setOnClickListener { showUnlockPill() }
        }
        val pill = pill("Tap to unlock", R.drawable.ic_pl_lock_open) { unlockScreen() }
        shield.addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(18) })
        root.addView(shield, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        lockShield = shield
        unlockPill = pill
        showUnlockPill()
    }

    /** Screen tests: the lock and the stream info window, as if pressed. */
    /** Screen tests (8.42): the hand-off button as if a linked TV were near, and a hop 12 minutes from its end. */
    internal fun previewHop(): Pair<String, String> {
        val ch = queue.getOrNull(index) ?: return "" to ""
        handoffButton.visibility = View.VISIBLE
        hop = Hop(queue, index, ch.id, ch.name, System.currentTimeMillis() + 12 * 60_000L - 1_000)
        updateHopButton()
        if (tvLayout) fitTvPills() else fitPills()
        playerView.showController()
        return (hopButton.tag as? String).orEmpty() to (handoffButton.tag as? String).orEmpty()
    }

    /** Screen tests (8.45): the channel banner as it shows now (its text, to check what's in it). */
    internal fun previewBanner(): String {
        playerView.hideController()
        val ch = queue.getOrNull(index) ?: return ""
        renderBanner(ch)
        bannerView.visibility = View.VISIBLE
        handler.removeCallbacks(hideBanner)
        return bannerView.text.toString()
    }

    /** Screen tests: "Still watching?" as it asks. */
    internal fun askStillWatchingForTest(): Boolean {
        playerView.hideController()
        showStillDialog()
        return stillDialog != null
    }

    internal fun lockForTest(): Boolean {
        lockScreen()
        return locked && lockShield != null
    }

    internal fun streamInfoForTest(): String {
        unlockScreen()
        showStreamInfo()
        return streamInfoText().toString()
    }

    private fun showUnlockPill() {
        val pill = unlockPill ?: return
        pill.visibility = View.VISIBLE
        handler.removeCallbacks(hideUnlockPill)
        handler.postDelayed(hideUnlockPill, 3_000)
    }

    private fun unlockScreen() {
        handler.removeCallbacks(hideUnlockPill)
        lockShield?.let { root.removeView(it) }
        lockShield = null
        unlockPill = null
        locked = false
        playerView.useController = true
        playerView.showController()
    }

    // ---- sound timing (8.29) ----

    /** What ExoPlayer's sound clock is moved by, read on its playback thread. */
    private val soundTiming = app.falak.player.player.SoundTiming.Offset()

    private fun applySoundTiming(ms: Int) {
        soundTiming.us = ms * 1000L
        vlc?.setAudioDelay(ms)
    }

    /** Player → Sound timing: the sound earlier or later in steps of 50 ms, heard at once, kept for this device. */
    private fun showSoundTiming() {
        var ms = repo.state.value.settings.audioDelayMs
        val themed = android.view.ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        val value = TextView(themed).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        val hint = TextView(themed).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            alpha = 0.7f
            gravity = Gravity.CENTER
            setPadding(dp(20), 0, dp(20), dp(12))
            text = "Lips move after you hear the words: sound later. Words come after the lips (a soundbar): sound earlier."
        }
        fun show() {
            value.text = app.falak.player.player.SoundTiming.label(ms)
        }
        fun step(by: Int) {
            ms = app.falak.player.player.SoundTiming.clamp(ms + by)
            show()
            applySoundTiming(ms)
            val keep = ms
            repo.updateSettings { it.copy(audioDelayMs = keep) }
        }
        val earlier = android.widget.Button(themed).apply { text = "− Earlier"; setOnClickListener { step(-50) } }
        val reset = android.widget.Button(themed).apply { text = "In step"; setOnClickListener { step(-ms) } }
        val later = android.widget.Button(themed).apply { text = "Later +"; setOnClickListener { step(50) } }
        val buttons = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            for (b in listOf(earlier, reset, later)) addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        val box = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
            addView(value)
            addView(hint)
            addView(buttons)
        }
        show()
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Sound timing")
            .setView(box)
            .setNegativeButton("Done", null)
            .create()
            .apply { setOnShowListener { later.requestFocus() } }
            .show()
    }

    // ---- stream info (8.28) ----

    /** Player → Stream info: what is playing, in figures, kept up to date each second. */
    private fun showStreamInfo() {
        val text = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tvLayout) 16f else 15f)
            typeface = ResourcesCompat.getFont(this@PlayerActivity, R.font.inter_medium)
            setLineSpacing(0f, 1.3f)
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Stream info")
            .setView(text)
            .setNegativeButton("Close", null)
            .create()
        val tick = object : Runnable {
            override fun run() {
                text.text = streamInfoText()
                if (dialog.isShowing) handler.postDelayed(this, 1_000)
            }
        }
        dialog.setOnShowListener { handler.post(tick) }
        dialog.setOnDismissListener { handler.removeCallbacks(tick) }
        dialog.show()
    }

    private fun streamInfoText(): CharSequence {
        val ch = queue.getOrNull(index)
        val p = player
        val v = exo?.videoFormat
        val a = exo?.audioFormat
        val size = p?.videoSize
        val lines = app.falak.player.player.StreamInfo.lines(
            engine = engine.short,
            width = v?.width?.takeIf { it > 0 } ?: size?.width ?: 0,
            height = v?.height?.takeIf { it > 0 } ?: size?.height ?: 0,
            fps = v?.frameRate?.takeIf { it > 0f } ?: vlc?.frameRate() ?: -1f,
            videoMime = v?.sampleMimeType,
            declaredBps = listOfNotNull(v?.peakBitrate, v?.averageBitrate, v?.bitrate).firstOrNull { it > 0 }?.toLong() ?: 0L,
            audioMime = a?.sampleMimeType,
            channels = a?.channelCount ?: 0,
            sampleRate = a?.sampleRate ?: 0,
            audioLang = a?.language,
            incomingBps = if (rateSamples.size >= 2) rateBps else 0L,
            bufferedMs = p?.let { (it.bufferedPosition - it.currentPosition).coerceAtLeast(0) } ?: 0L,
            dropped = droppedFrames,
            link = app.falak.player.player.StreamInfo.linkKind(currentUrl.ifEmpty { ch?.url.orEmpty() }),
        )
        val out = SpannableStringBuilder()
        ch?.let { out.append(it.name, StyleSpan(Typeface.BOLD), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE).append("\n") }
        for ((label, value) in lines) {
            val start = out.length
            out.append(label).append("   ")
            out.setSpan(ForegroundColorSpan(0x99FFFFFF.toInt()), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.append(value).append("\n")
        }
        return out.trimEnd()
    }

    // ---- Chromecast (phones, 8.28) ----

    private fun castClicked() {
        val c = cast ?: return
        val session = c.sessionManager.currentCastSession
        if (session != null && session.isConnected) {
            android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Playing on ${session.castDevice?.friendlyName ?: "a Chromecast"}")
                .setPositiveButton("Stop casting") { _, _ -> c.sessionManager.endCurrentSession(true) }
                .setNegativeButton("Close", null)
                .show()
            return
        }
        showCastPicker(c)
    }

    /** The Chromecasts on this Wi-Fi, found as the window is open; picking one starts casting (castListener). */
    private fun showCastPicker(c: com.google.android.gms.cast.framework.CastContext) {
        val selector = c.mergedSelector ?: return
        val router = androidx.mediarouter.media.MediaRouter.getInstance(this)
        val themed = android.view.ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        val names = android.widget.ArrayAdapter<String>(themed, android.R.layout.simple_list_item_1)
        var routes: List<androidx.mediarouter.media.MediaRouter.RouteInfo> = emptyList()
        val status = TextView(themed).apply {
            setPadding(dp(24), dp(8), dp(24), dp(8))
            text = "Looking for Chromecasts on your Wi-Fi…"
        }
        val list = android.widget.ListView(themed).apply { adapter = names }
        val box = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        fun refresh() {
            routes = router.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
            names.clear()
            names.addAll(routes.map { it.name })
            status.text = if (routes.isEmpty()) "Looking for Chromecasts on your Wi-Fi…" else "Cast to:"
        }
        val found = object : androidx.mediarouter.media.MediaRouter.Callback() {
            override fun onRouteAdded(r: androidx.mediarouter.media.MediaRouter, route: androidx.mediarouter.media.MediaRouter.RouteInfo) = refresh()
            override fun onRouteRemoved(r: androidx.mediarouter.media.MediaRouter, route: androidx.mediarouter.media.MediaRouter.RouteInfo) = refresh()
            override fun onRouteChanged(r: androidx.mediarouter.media.MediaRouter, route: androidx.mediarouter.media.MediaRouter.RouteInfo) = refresh()
        }
        router.addCallback(selector, found, androidx.mediarouter.media.MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or androidx.mediarouter.media.MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refresh()
        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Cast")
            .setView(box)
            .setNegativeButton("Close", null)
            .create()
        list.setOnItemClickListener { _, _, which, _ ->
            routes.getOrNull(which)?.let { router.selectRoute(it) }
            dialog.dismiss()
        }
        dialog.setOnDismissListener { router.removeCallback(found) }
        dialog.show()
    }

    private fun markCasting(session: com.google.android.gms.cast.framework.CastSession) {
        castingTo = session.castDevice?.friendlyName ?: "a Chromecast"
        castButton.setColorFilterTint(true)
        player?.pause()
        showMessage("Playing on $castingTo")
    }

    /**
     * Sends what is playing to the Chromecast (its own link: the TV plays it from the provider).
     * False when it can't go (a torrent, a download, the app's own link): it plays here as before.
     */
    private fun castNow(): Boolean {
        val session = cast?.sessionManager?.currentCastSession ?: return false
        if (session.remoteMediaClient == null) return false
        val ch = queue.getOrNull(index) ?: return false
        // A MAC portal's channel or title (8.35): the portal's own link is asked for first (the app's
        // 127.0.0.1 link can't be reached from the Chromecast), then that goes.
        val portal = app.falak.player.data.PortalLinks.fix(ch.url)
        if (app.falak.player.data.PortalLinks.ours(portal) && app.falak.player.OfflineCenter.fileFor(ch.id) == null) {
            val at = index
            showLoading(ch)
            Thread {
                val real = runCatching { app.falak.player.data.PortalLinks.real(portal) }
                runOnUiThread {
                    if (isFinishing || isDestroyed || index != at) return@runOnUiThread
                    val s = cast?.sessionManager?.currentCastSession
                    val url = real.getOrNull()?.let { app.falak.player.cast.CastLinks.castable(it) }
                    if (s == null || url == null) {
                        toast(real.exceptionOrNull()?.message?.let { "The Chromecast can't have it ($it): it plays here." }
                            ?: "This one can't go to a Chromecast: it plays here.")
                        castBypass = true
                        playCurrent(false)
                    } else {
                        castLoad(s, ch, url)
                    }
                }
            }.start()
            return true
        }
        val url = app.falak.player.cast.CastLinks.castable(ch.url)
        if (url == null || app.falak.player.OfflineCenter.fileFor(ch.id) != null) {
            toast("This one can't go to a Chromecast: it plays here (torrent links and downloads stay on this phone).")
            return false
        }
        castLoad(session, ch, url)
        return true
    }

    /** Casting stopped by a portal link that couldn't go: the next play is here. */
    private var castBypass = false

    private fun castLoad(session: com.google.android.gms.cast.framework.CastSession, ch: Channel, url: String) {
        val client = session.remoteMediaClient ?: return
        val live = ch.kind == Kind.LIVE && !ch.id.startsWith("cu:")
        val meta = com.google.android.gms.cast.MediaMetadata(
            if (live) com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_GENERIC else com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MOVIE,
        ).apply {
            putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, ch.name)
            if (ch.logo.startsWith("http")) addImage(com.google.android.gms.common.images.WebImage(android.net.Uri.parse(ch.logo)))
        }
        val info = com.google.android.gms.cast.MediaInfo.Builder(url)
            .setStreamType(if (live) com.google.android.gms.cast.MediaInfo.STREAM_TYPE_LIVE else com.google.android.gms.cast.MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(app.falak.player.cast.CastLinks.contentType(url))
            .setMetadata(meta)
            .build()
        val from = if (live) 0L else (player?.currentPosition ?: resumePositionMs.takeIf { it > 0 } ?: 0L)
        client.load(
            com.google.android.gms.cast.MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).setCurrentTime(from).build(),
        ).setResultCallback { r ->
            if (!r.status.isSuccess) toast("The Chromecast couldn't play it (a provider may only allow its own app or player)")
        }
        hideLoading()
        updateTopBar()
        markCasting(session)
    }

    /** Casting stopped: the picture comes back here, where it was. */
    private fun castEnded() {
        if (castingTo == null) return
        castingTo = null
        castButton.setColorFilterTint(false)
        hideMessage()
        // Moved on to another channel or episode while casting: that one starts here.
        if (playingHere != index) playCurrent(false) else player?.play()
    }

    private fun TextView.setColorFilterTint(on: Boolean) {
        compoundDrawablesRelative.firstOrNull()?.setTint(if (on) accentInt else Color.WHITE)
    }

    /**
     * The Home button: out of the player onto the app's Home, whichever page it was opened from
     * (a film's page, the add-ons' portal, the guide). A live channel carries on in the mini
     * player, as with Back (Settings → Mini player).
     */
    private fun goHome(where: String = "HOME") {
        saveProgress()
        offerMini()
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(app.falak.player.data.Notifier.EXTRA_OPEN, where)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    private fun offerMini(takeFocus: Boolean = true) {
        val ch = queue.getOrNull(index) ?: return
        val p = player ?: return
        if (!repo.state.value.settings.miniPlayer || inPip || dual != null) return
        if (ch.kind != Kind.LIVE || ch.id.startsWith("cu:") || p.playerError != null || !p.playWhenReady) return
        val list = queue
        val at = index
        val ua = headersFor(ch).first
        releasePlayer()
        MiniPlayerState.show(list, at, ua, repo.currentPlaylist?.id, takeFocus)
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
        if (dual != null) endDual(keep = 0, replay = false)
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
