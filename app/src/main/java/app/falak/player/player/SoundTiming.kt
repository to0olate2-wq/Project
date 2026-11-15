package app.falak.player.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * Sound timing (8.29): moves the sound against the picture. ExoPlayer shows each frame when the
 * sound's clock reaches it, so the clock it reads is moved: ahead by d = frames sooner = the sound
 * d later; behind = the picture waits = the sound earlier. VLC has its own (VlcPlayer.setAudioDelay).
 */
@UnstableApi
object SoundTiming {
    const val MAX_MS = 2_000

    /** Read on the playback thread, changed from the window: takes effect at once. */
    class Offset {
        @Volatile var us: Long = 0L
    }

    fun wrap(sink: AudioSink, offset: Offset): AudioSink = object : ForwardingAudioSink(sink) {
        override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
            shifted(super.getCurrentPositionUs(sourceEnded), offset.us)
    }

    /** The clock as the picture sees it; "not set" stays so. */
    fun shifted(us: Long, by: Long): Long =
        if (us == AudioSink.CURRENT_POSITION_NOT_SET || by == 0L) us else (us + by).coerceAtLeast(0L)

    fun clamp(ms: Int): Int = ms.coerceIn(-MAX_MS, MAX_MS)

    fun label(ms: Int): String = when {
        ms == 0 -> "In step (as the stream sends it)"
        ms > 0 -> "Sound $ms ms later"
        else -> "Sound ${-ms} ms earlier"
    }
}
