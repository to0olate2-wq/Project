package app.falak.player.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.VideoSize
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * VLC (libVLC) behind the standard Media3 [Player] interface, so the same on-screen controls,
 * zapping and progress saving work with either engine. VLC draws the picture and its own
 * subtitles (closed captions, teletext, DVB…) into a [VLCVideoLayout].
 */
class VlcPlayer(context: Context, cachingMs: Int, levelVolume: Boolean) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val libVlc = LibVLC(
        context.applicationContext,
        arrayListOf(
            "--http-reconnect",
            "--no-stats",
            // Never look up cover art or other metadata online (VLC's default, made explicit):
            // the app only talks to the servers in the user's own playlists.
            "--no-metadata-network-access",
            "--audio-time-stretch",
            "--network-caching=$cachingMs",
            "--live-caching=$cachingMs",
        ).apply {
            // VLC's own volume normaliser evens out loud and quiet channels.
            if (levelVolume) addAll(listOf("--audio-filter=normvol", "--norm-buff-size=20", "--norm-max-level=2.0"))
        },
    )
    private val vlc = MediaPlayer(libVlc)
    private val main = Handler(Looper.getMainLooper())

    private var item: MediaItem? = null
    private var itemUid = 0
    private var playWhenReady = true
    private var state = Player.STATE_IDLE
    private var error: PlaybackException? = null
    private var lengthMs = 0L
    private var seekable = false
    private var videoSize = VideoSize.UNKNOWN
    private var released = false
    private var speed = 1f

    /** Called (on the main thread) when VLC finds or changes audio/subtitle tracks. */
    var onTracksChanged: (() -> Unit)? = null

    init {
        vlc.setEventListener { event -> if (Looper.myLooper() == Looper.getMainLooper()) onEvent(event) else main.post { onEvent(event) } }
    }

    fun attach(layout: VLCVideoLayout) {
        if (!released) vlc.attachViews(layout, null, true, false)
    }

    fun detach() {
        if (!released) runCatching { vlc.detachViews() }
    }

    /** Starts [url]; [startMs] > 0 resumes a film or episode. */
    fun load(url: String, title: String, userAgent: String, referrer: String, startMs: Long, hardware: Boolean) {
        if (released) return
        val media = Media(libVlc, Uri.parse(url))
        media.setHWDecoderEnabled(hardware, false)
        media.addOption(":http-user-agent=$userAgent")
        if (referrer.isNotBlank()) media.addOption(":http-referrer=$referrer")
        if (startMs > 0) media.addOption(":start-time=${startMs / 1000}")
        vlc.media = media
        media.release()
        item = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        itemUid++
        error = null
        lengthMs = 0
        seekable = false
        videoSize = VideoSize.UNKNOWN
        state = Player.STATE_BUFFERING
        if (playWhenReady) vlc.play()
        if (speed != 1f) vlc.rate = speed
        invalidateState()
    }

    // ---- tracks ----

    class Track(val id: Int, val name: String)

    fun audioTracks(): List<Track> = tracks(vlc.audioTracks)
    fun subtitleTracks(): List<Track> = tracks(vlc.spuTracks)
    val selectedAudio: Int get() = if (released) -1 else vlc.audioTrack
    val selectedSubtitle: Int get() = if (released) -1 else vlc.spuTrack
    fun selectAudio(id: Int) { if (!released) vlc.setAudioTrack(id) }
    /** -1 turns subtitles off. */
    fun selectSubtitle(id: Int) { if (!released) vlc.setSpuTrack(id) }

    private fun tracks(list: Array<MediaPlayer.TrackDescription>?): List<Track> =
        if (released) emptyList() else list.orEmpty().filter { it.id >= 0 }.map { Track(it.id, it.name.orEmpty()) }

    /** Adds a downloaded subtitle file and shows it. */
    fun addSubtitleFile(file: java.io.File) {
        if (!released) vlc.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle, Uri.fromFile(file), true)
    }

    /** 0 = fit, 1 = fill (stretch), 2 = zoom (crop). */
    fun setScale(mode: Int) {
        if (released) return
        vlc.videoScale = when (mode) {
            1 -> MediaPlayer.ScaleType.SURFACE_FILL
            2 -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
        }
    }

    // ---- libVLC events ----

    private fun onEvent(event: MediaPlayer.Event) {
        if (released) return
        when (event.type) {
            MediaPlayer.Event.Opening -> state = Player.STATE_BUFFERING
            MediaPlayer.Event.Buffering -> {
                val ready = event.buffering >= 100f
                if (state != Player.STATE_ENDED) state = if (ready) Player.STATE_READY else Player.STATE_BUFFERING
            }
            MediaPlayer.Event.Playing -> state = Player.STATE_READY
            MediaPlayer.Event.Paused -> if (state == Player.STATE_BUFFERING) state = Player.STATE_READY
            MediaPlayer.Event.EndReached -> state = Player.STATE_ENDED
            MediaPlayer.Event.EncounteredError -> {
                error = PlaybackException(
                    "VLC couldn't play this stream",
                    null,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                )
                state = Player.STATE_IDLE
            }
            MediaPlayer.Event.LengthChanged -> lengthMs = event.lengthChanged
            MediaPlayer.Event.SeekableChanged -> seekable = event.seekable
            MediaPlayer.Event.Vout -> updateVideoSize()
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> {
                updateVideoSize()
                onTracksChanged?.invoke()
            }
            else -> return // TimeChanged & co.: the position is read live, nothing else to update
        }
        invalidateState()
    }

    private fun updateVideoSize() {
        val t = runCatching { vlc.currentVideoTrack }.getOrNull() ?: return
        if (t.width <= 0 || t.height <= 0) return
        val par = if (t.sarNum > 0 && t.sarDen > 0) t.sarNum.toFloat() / t.sarDen else 1f
        videoSize = VideoSize(t.width, t.height, par)
    }

    // ---- SimpleBasePlayer ----

    override fun getState(): State {
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_RELEASE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_SPEED_AND_PITCH,
        )
        val canSeek = seekable && lengthMs > 0
        if (canSeek) {
            commands.addAll(
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            )
        }
        val builder = State.Builder()
            .setAvailableCommands(commands.build())
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlayerError(error)
            .setVideoSize(videoSize)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setPlaybackParameters(PlaybackParameters(speed))
        val current = item
        if (current == null || released) return builder.setPlaybackState(Player.STATE_IDLE).build()
        val live = lengthMs <= 0
        val data = MediaItemData.Builder(itemUid)
            .setMediaItem(current)
            .setDurationUs(if (live) C.TIME_UNSET else lengthMs * 1000)
            .setIsSeekable(canSeek)
            .setIsDynamic(live)
            .setLiveConfiguration(if (live) MediaItem.LiveConfiguration.UNSET else null)
            .build()
        return builder
            .setPlaylist(listOf(data))
            .setPlaybackState(if (error != null) Player.STATE_IDLE else state)
            .setContentPositionMs(PositionSupplier { if (released) 0L else vlc.time.coerceAtLeast(0L) })
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (!released) {
            if (playWhenReady) vlc.play() else vlc.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (!released && item != null) {
            error = null
            state = Player.STATE_BUFFERING
            vlc.stop()
            vlc.play()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        if (!released) vlc.stop()
        state = Player.STATE_IDLE
        return Futures.immediateVoidFuture()
    }

    /** Playback speed (films and episodes). */
    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters.speed
        if (!released) vlc.rate = speed
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (!released && positionMs != C.TIME_UNSET && seekable) vlc.setTime(positionMs, false)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        if (!released) {
            released = true
            onTracksChanged = null
            runCatching {
                vlc.setEventListener(null)
                vlc.stop()
                vlc.detachViews()
                vlc.release()
                libVlc.release()
            }
        }
        return Futures.immediateVoidFuture()
    }
}
