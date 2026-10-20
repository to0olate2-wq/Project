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
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTrackNameProvider
import androidx.media3.ui.PlayerView
import app.falak.player.R
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

        private val CAPTION_FORMATS = listOf(
            androidx.media3.common.Format.Builder()
                .setSampleMimeType(MimeTypes.APPLICATION_CEA608)
                .setAccessibilityChannel(1)
                .build(),
        )

        /** A caption track we added ourselves (not declared by the stream): only real once text arrives. */
        fun isUnverified(f: androidx.media3.common.Format): Boolean =
            f.sampleMimeType == MimeTypes.APPLICATION_CEA608 && f.language == null
    }

    private lateinit var playerView: PlayerView
    private lateinit var bannerView: TextView
    private lateinit var messageView: TextView
    private lateinit var aspectButton: TextView
    private lateinit var audioButton: TextView
    private lateinit var ccButton: TextView
    private lateinit var toolbar: LinearLayout
    private lateinit var browseButton: TextView
    private lateinit var browserView: ComposeView

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
    private var currentExtractors = DefaultExtractorsFactory()
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

        // Audio / Subtitles / Picture-size buttons, shown together with the player controls.
        audioButton = pill("🔊  Audio") { showTrackDialog(C.TRACK_TYPE_AUDIO) }.apply { visibility = View.GONE }
        ccButton = pill("CC  Subtitles") { showTrackDialog(C.TRACK_TYPE_TEXT) }.apply { visibility = View.GONE }
        aspectButton = pill("⛶  ${aspectNames[aspect]}") { cycleAspect() }
        val externalButton = pill("↗  VLC") { openExternally() }
        browseButton = pill("☰  Channels") { openBrowser() }
        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            val gap = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(8) }
            addView(browseButton, LinearLayout.LayoutParams(gap))
            addView(audioButton, gap)
            addView(ccButton, LinearLayout.LayoutParams(gap))
            addView(aspectButton, LinearLayout.LayoutParams(gap))
            addView(externalButton)
        }
        root.addView(
            toolbar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
                .apply { setMargins(dp(20), dp(20), dp(20), dp(20)) },
        )
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                toolbar.visibility = if (inPip) View.GONE else visibility
            },
        )

        browserView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            visibility = View.GONE
            setContent {
                val app by repo.state.collectAsState()
                val epg by repo.epg.collectAsState()
                FalakTheme(app.settings.theme) {
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
        val audio = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        // FFmpeg takes over audio the device can't decode (AC-3, E-AC-3, DTS, MP2…),
        // or all audio when "Software audio" is on.
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(
                if (repo.state.value.settings.softwareAudio) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            )
        val builder = ExoPlayer.Builder(this, renderers)
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
        // Language preferences from Settings.
        val s = repo.state.value.settings
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().apply {
            if (s.audioLang.isNotEmpty()) setPreferredAudioLanguage(s.audioLang)
            if (s.subtitlesOn) {
                setPreferredTextLanguage(s.subtitleLang.ifEmpty { null })
                setSelectUndeterminedTextLanguage(true)
            }
        }.build()
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
        currentChannelId = ch.id
        // Remembered so the list can put the selection back on this item after the player closes.
        if (!ch.id.startsWith("cu:")) repo.lastPlayedId = ch.id
        browseButton.text = when (ch.kind) {
            Kind.LIVE -> "☰  Channels"
            Kind.MOVIE -> "☰  Films"
            Kind.SERIES -> "☰  Episodes"
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

        val httpSource = OkHttpDataSource.Factory(repo.http).setUserAgent(ua)
        if (ch.referrer.isNotBlank()) httpSource.setDefaultRequestProperties(mapOf("Referer" to ch.referrer))
        // DefaultDataSource adds local files (downloaded subtitles) on top of the network source.
        val dataSource = DefaultDataSource.Factory(this, httpSource)
        // Media3 only exposes captions embedded in the video when the stream declares them, and
        // IPTV restreams almost never do. Like other IPTV players, always offer CC1 (CEA-608) and
        // CEA-708; a stream that does declare its captions still takes priority.
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
            .setTsSubtitleFormats(CAPTION_FORMATS)
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
                .setExtractorFactory(
                    DefaultHlsExtractorFactory(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES, true),
                )
                .createMediaSource(item)
        } else {
            DefaultMediaSourceFactory(dataSource, extractors).createMediaSource(item)
        }
        autoSubtitlesDone = false
        probeDone = false
        captionsDetected = false
        captionsShown = repo.state.value.settings.subtitlesOn
        applyCaptionVisibility()

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

        override fun onCues(cueGroup: CueGroup) {
            if (cueGroup.cues.isNotEmpty()) {
                captionsSeen = true
                handler.removeCallbacks(captionWatch)
                if (!captionsDetected) {
                    captionsDetected = true
                    updateTrackButtons()
                }
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateTrackButtons()
            maybeAutoSubtitles()
            probeCaptions()
            if (bannerView.visibility == View.VISIBLE) bannerFor?.let { renderBanner(it) }
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
            if (browserOpen) {
                browserOpen = false
                browserView.visibility = View.GONE
            }
            bannerView.visibility = View.GONE
            toolbar.visibility = View.GONE
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

    private fun cycleAspect() {
        aspect = (aspect + 1) % resizeModes.size
        playerView.resizeMode = resizeModes[aspect]
        aspectButton.text = "⛶  ${aspectNames[aspect]}"
        Toast.makeText(this, "Picture: ${aspectNames[aspect]}", Toast.LENGTH_SHORT).show()
        repo.updateSettings { it.copy(aspect = aspect) }
    }

    // ---- overlays ----

    // ---- audio & subtitle tracks ----

    private class TrackOption(val label: String, val selected: Boolean, val select: () -> Unit)

    private fun trackOptions(type: Int): List<TrackOption> {
        val p = player ?: return emptyList()
        val out = ArrayList<TrackOption>()
        if (type == C.TRACK_TYPE_TEXT) {
            val anyOn = captionsShown && p.currentTracks.groups.any { it.type == type && it.isSelected }
            out += TrackOption("Off", !anyOn) {
                captionsShown = false
                applyCaptionVisibility()
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
                if (type == C.TRACK_TYPE_TEXT && isUnverified(g.getTrackFormat(i)) && !captionsDetected) continue
                val base = trackLabel(g.getTrackFormat(i))
                val n = (seen[base] ?: 0) + 1
                seen[base] = n
                val label = if (n > 1) "$base ($n)" else base
                out += TrackOption(label, g.isTrackSelected(i) && (type != C.TRACK_TYPE_TEXT || captionsShown)) {
                    if (type == C.TRACK_TYPE_TEXT) {
                        captionsShown = true
                        applyCaptionVisibility()
                    }
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                        .build()
                    if (type == C.TRACK_TYPE_TEXT) watchCaptions()
                }
            }
        }
        return out
    }

    /** Names that say what a track really is (CC vs DVB subtitles), with the language. */
    private fun trackLabel(f: androidx.media3.common.Format): String {
        val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { java.util.Locale(it).displayLanguage.replaceFirstChar(Char::uppercase) }
        return when (f.sampleMimeType) {
            MimeTypes.APPLICATION_CEA608 -> buildString {
                append("Closed captions")
                if (f.accessibilityChannel != androidx.media3.common.Format.NO_VALUE) append(" CC").append(f.accessibilityChannel)
                if (lang != null) append(" · ").append(lang)
            }
            MimeTypes.APPLICATION_CEA708 -> "Closed captions (708)" + (lang?.let { " · $it" } ?: "")
            MimeTypes.APPLICATION_DVBSUBS -> "Subtitles" + (lang?.let { " · $it" } ?: "") + " (DVB)"
            else -> trackNames.getTrackName(f)
        }
    }

    private var captionsSeen = false
    private val captionWatch = Runnable {
        val textOn = player?.currentTracks?.groups?.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected } == true
        if (!captionsSeen && textOn) {
            toast("This channel isn't sending captions right now. Try another track, or ↗ Open with VLC.")
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
        val groups = player?.currentTracks?.groups ?: return 0
        return groups.filter { it.type == type }.sumOf { g ->
            (0 until g.length).count { i ->
                g.isTrackSupported(i) &&
                    (type != C.TRACK_TYPE_TEXT || captionsDetected || !isUnverified(g.getTrackFormat(i)))
            }
        }
    }

    // ---- caption verification ----
    // Many streams carry no captions even though a CC track can be offered. We quietly decode
    // CC1 with the subtitle view hidden; the CC button appears only once real text arrives.
    private var captionsDetected = false
    private var captionsShown = false
    private var probeDone = false

    private fun applyCaptionVisibility() {
        playerView.subtitleView?.visibility = if (captionsShown) View.VISIBLE else View.INVISIBLE
    }

    private fun probeCaptions() {
        val p = player ?: return
        if (probeDone) return
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (groups.isEmpty()) return
        probeDone = true
        if (groups.any { it.isSelected }) return
        val cc = groups.firstOrNull { g -> (0 until g.length).any { isUnverified(g.getTrackFormat(it)) } } ?: return
        applyCaptionVisibility()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(cc.mediaTrackGroup, 0))
            .build()
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
        audioButton.text = "🔊  Audio ($audio)"
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
        val p = player ?: return
        val item = currentItem ?: return
        val dataSource = currentDataSource ?: return
        val sub = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage(lang)
            .setLabel("Online (${lang.uppercase()})")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val withSub = item.buildUpon().setSubtitleConfigurations(listOf(sub)).build()
        val source = DefaultMediaSourceFactory(dataSource, currentExtractors).createMediaSource(withSub)
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
        val p = player ?: return
        if (autoSubtitlesDone || !repo.state.value.settings.subtitlesOn) return
        val groups = p.currentTracks.groups.filter { g ->
            g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { !isUnverified(g.getTrackFormat(it)) }
        }
        if (groups.isEmpty()) return
        autoSubtitlesDone = true
        captionsShown = true
        applyCaptionVisibility()
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
        val p = player ?: return ""
        val audios = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { trackNames.getTrackName(g.getTrackFormat(it)) } }
            .distinct()
        val subs = countTracks(C.TRACK_TYPE_TEXT)
        val parts = ArrayList<String>()
        if (audios.size > 1) parts += "Audio: " + audios.take(4).joinToString(", ")
        if (subs > 0) parts += "Subtitles: $subs"
        return parts.joinToString("  ·  ")
    }

    private fun pill(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        text = label
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(0xCC111010.toInt())
            setStroke(dp(1), 0x33FFFFFF)
        }
        typeface = ResourcesCompat.getFont(this@PlayerActivity, R.font.inter_medium)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
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
