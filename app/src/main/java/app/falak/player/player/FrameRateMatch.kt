package app.falak.player.player

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import kotlin.math.abs

/**
 * Auto frame rate (TVs): switches the screen to the video's frame rate (24, 25, 50, 60 Hz…) so
 * films and sport move without judder. It keeps the screen's resolution, picks a rate the TV
 * offers that is the video's rate or a whole multiple of it, and gives the screen back its own
 * rate when the video doesn't need a change or the player closes.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class FrameRateMatch(private val activity: Activity) {
    /** One display mode: its id, size and refresh rate. */
    data class Mode(val id: Int, val width: Int, val height: Int, val hz: Float)

    private val main = Handler(Looper.getMainLooper())

    // Set on the main thread, read by ExoPlayer's playback thread.
    @Volatile private var generation = 0
    @Volatile private var measuring = false
    private var enabled = false

    /** The mode asked for (0 = the screen's own choice). */
    private var applied = 0

    // Measuring, on the playback thread only.
    private var seenGeneration = -1
    private var lastPts = Long.MIN_VALUE
    private val deltas = LongArray(SAMPLES)
    private var count = 0

    /** A new video: match its rate when [on]; otherwise the screen goes back to its own rate. */
    fun start(on: Boolean) {
        generation++
        main.removeCallbacksAndMessages(null)
        enabled = on && offeredRates(display()).size > 1
        measuring = enabled
        if (!enabled) restore()
    }

    /** The rate the stream declares (ExoPlayer's format, VLC's track), when it has one. */
    fun declared(fps: Float) {
        if (!enabled || !measuring || fps <= 1f) return
        measuring = false
        apply(fps)
    }

    /** ExoPlayer: the time of each frame shown, until enough have passed to tell the rate. */
    val frames = VideoFrameMetadataListener { presentationTimeUs, _, format, _ ->
        if (!measuring) return@VideoFrameMetadataListener
        val gen = generation
        if (gen != seenGeneration) {
            seenGeneration = gen
            lastPts = Long.MIN_VALUE
            count = 0
        }
        if (format.frameRate > 1f) {
            measuring = false
            val fps = format.frameRate
            main.post { if (gen == generation) apply(fps) }
            return@VideoFrameMetadataListener
        }
        val last = lastPts
        lastPts = presentationTimeUs
        if (last == Long.MIN_VALUE) return@VideoFrameMetadataListener
        val d = presentationTimeUs - last
        // Seeks, gaps and repeated frames don't count.
        if (d < 5_000 || d > 200_000) return@VideoFrameMetadataListener
        deltas[count++] = d
        if (count == SAMPLES) {
            measuring = false
            val median = deltas.sorted()[SAMPLES / 2]
            val fps = 1_000_000f / median
            main.post { if (gen == generation) apply(fps) }
        }
    }

    /** The screen back to its own rate (player closing). */
    fun restore() {
        if (applied == 0) return
        applied = 0
        runCatching {
            val lp = activity.window.attributes
            lp.preferredDisplayModeId = 0
            activity.window.attributes = lp
        }
    }

    fun release() {
        generation++
        measuring = false
        main.removeCallbacksAndMessages(null)
        restore()
    }

    private fun apply(raw: Float) {
        if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val display = display() ?: return
        val cur = display.mode
        val current = Mode(cur.modeId, cur.physicalWidth, cur.physicalHeight, cur.refreshRate)
        val modes = display.supportedModes.map { Mode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val pick = choose(modes, current, snap(raw))
        if (pick == null) {
            // Nothing suits this video: leave the screen at its own rate.
            restore()
            return
        }
        if (pick.id == current.id && applied == 0) return
        runCatching {
            val lp = activity.window.attributes
            if (lp.preferredDisplayModeId != pick.id) {
                lp.preferredDisplayModeId = pick.id
                activity.window.attributes = lp
            }
            applied = pick.id
        }
    }

    private fun display(): Display? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay
    }.getOrNull()

    companion object {
        private const val SAMPLES = 31
        private val STANDARD = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 47.952f, 48f, 50f, 59.94f, 60f, 100f, 119.88f, 120f)

        /** 23.98 → 23.976, 49.9 → 50: the nearest standard rate when within 1 %. */
        fun snap(fps: Float): Float {
            val near = STANDARD.minByOrNull { abs(it - fps) } ?: return fps
            return if (abs(near - fps) <= near * 0.01f) near else fps
        }

        /** The TV mode for a video at [fps], at the current resolution; null when none suits. */
        fun choose(modes: List<Mode>, current: Mode, fps: Float): Mode? {
            if (fps < 23f) return null
            val same = modes.filter { it.width == current.width && it.height == current.height }
            val multiples = when {
                fps < 24.5f -> intArrayOf(1, 5, 2) // 23.976 / 24: 24 Hz, else 120, else 48
                fps < 31f -> intArrayOf(2, 1) // 25 / 29.97 / 30: 50 / 59.94 / 60 Hz, else 25 / 30
                else -> intArrayOf(1) // 50 / 59.94 / 60
            }
            for (k in multiples) {
                val target = fps * k
                val m = same.filter { abs(it.hz - target) <= target * 0.0012f + 0.02f }.minByOrNull { abs(it.hz - target) }
                if (m != null) return m
            }
            return null
        }

        /** The refresh rates the screen offers at its current resolution (one entry: nothing to match). */
        fun offeredRates(display: Display?): List<Float> {
            if (display == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
            val cur = display.mode
            return display.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .map { it.refreshRate }
                .distinctBy { Math.round(it * 100) }
                .sorted()
        }

        /** "23.98", "25", "59.94". */
        fun label(hz: Float): String {
            val r = Math.round(hz * 100) / 100f
            return if (r % 1f == 0f) r.toInt().toString() else String.format(java.util.Locale.US, "%.2f", r)
        }
    }
}
