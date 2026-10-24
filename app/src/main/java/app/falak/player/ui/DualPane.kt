package app.falak.player.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.falak.player.R
import app.falak.player.data.Channel
import okhttp3.OkHttpClient

/**
 * The second picture in two-screen mode: its own ExoPlayer, silent unless it's the one being
 * heard. It never takes the sound away from the main picture (no audio focus of its own) and
 * says plainly when the stream won't play beside the other one (many providers allow one stream
 * at a time; some TV boxes can decode only one).
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class DualPane(
    private val context: Context,
    private val http: OkHttpClient,
    /** The user agent and referrer to open a channel with. */
    private val headersFor: (Channel) -> Pair<String, String>,
    private val preferHls: Boolean,
    /** Something changed (channel, error): labels refresh. */
    private val onChange: () -> Unit,
) {
    val container = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }

    private val view = PlayerView(context).apply {
        useController = false
        setShutterBackgroundColor(Color.BLACK)
        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        keepScreenOn = true
        isFocusable = false
    }

    private val message = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setLineSpacing(0f, 1.2f)
        gravity = Gravity.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.inter_medium)
        background = GradientDrawable().apply {
            cornerRadius = 12 * context.resources.displayMetrics.density
            setColor(0xCC000000.toInt())
        }
        val pad = (14 * context.resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        visibility = View.GONE
    }

    var list: List<Channel> = emptyList()
        private set
    var index = 0
        private set
    val channel: Channel? get() = list.getOrNull(index)

    private var player: ExoPlayer? = null
    private var retried = false
    private var url = ""
    private var heard = false

    init {
        container.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val margin = (16 * context.resources.displayMetrics.density).toInt()
        container.addView(
            message,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                .apply { setMargins(margin, margin, margin, margin) },
        )
    }

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            if (!retried) {
                retried = true
                // Xtream live TV comes as .ts or HLS; if one fails, try the other once.
                val alt = when {
                    !url.contains("/live/") -> null
                    url.endsWith(".ts") -> url.removeSuffix(".ts") + ".m3u8"
                    url.endsWith(".m3u8") -> url.removeSuffix(".m3u8") + ".ts"
                    else -> null
                }
                if (alt != null) load(alt) else p.prepare()
                return
            }
            val name = channel?.name ?: "This channel"
            val http = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            show(
                if (http) "“$name” won't play beside the other channel.\nYour provider may allow only one channel at a time."
                else "“$name” won't play beside the other channel on this device.",
            )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                retried = false
                show(null)
            }
        }
    }

    /** Plays [list]'s item at [at]. */
    fun play(list: List<Channel>, at: Int) {
        this.list = list
        index = at.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        val ch = channel ?: return
        retried = false
        show(null)
        val u = if (preferHls && ch.url.contains("/live/") && ch.url.endsWith(".ts")) ch.url.removeSuffix(".ts") + ".m3u8" else ch.url
        load(u)
        onChange()
    }

    /** The next or previous channel of its list. */
    fun zap(delta: Int) {
        if (list.size < 2) return
        play(list, (index + delta).mod(list.size))
    }

    /** Only the picture being heard makes a sound. */
    fun setHeard(on: Boolean) {
        heard = on
        player?.volume = if (on) 1f else 0f
    }

    fun release() {
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
        view.player = null
    }

    private fun load(u: String) {
        val ch = channel ?: return
        url = u
        val p = player ?: create().also { player = it }
        val (ua, referrer) = headersFor(ch)
        val httpSource = OkHttpDataSource.Factory(http).setUserAgent(ua)
        if (referrer.isNotBlank()) httpSource.setDefaultRequestProperties(mapOf("Referer" to referrer))
        val item = MediaItem.Builder()
            .setUri(u)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(ch.name).build())
            .apply {
                when {
                    u.contains("m3u8", ignoreCase = true) -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    u.contains(".mpd", ignoreCase = true) -> setMimeType(MimeTypes.APPLICATION_MPD)
                }
            }
            .build()
        p.setMediaSource(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpSource)).createMediaSource(item))
        p.prepare()
        p.playWhenReady = true
    }

    private fun create(): ExoPlayer {
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val audio = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
        // A smaller buffer than the main picture's: two streams share the memory.
        val bufferBytes = (Runtime.getRuntime().maxMemory() / 10).coerceIn(16L shl 20, 48L shl 20).toInt()
        val p = ExoPlayer.Builder(context, renderers)
            .setAudioAttributes(audio, false)
            .setLoadControl(DefaultLoadControl.Builder().setTargetBufferBytes(bufferBytes).setPrioritizeTimeOverSizeThresholds(false).build())
            .build()
        p.addListener(listener)
        p.volume = if (heard) 1f else 0f
        view.player = p
        return p
    }

    private fun show(text: String?) {
        message.text = text.orEmpty()
        message.visibility = if (text == null) View.GONE else View.VISIBLE
        onChange()
    }

    /** Screenshot tests: a stand-in player and channel, no network. */
    fun previewWith(stand: Player, list: List<Channel>, at: Int) {
        this.list = list
        index = at
        view.player = stand
        onChange()
    }
}
