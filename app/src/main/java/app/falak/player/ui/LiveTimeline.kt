package app.falak.player.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import app.falak.player.R
import java.util.Locale

/**
 * Pause & rewind live TV, in the player's controls: the buffer as a bar. How far back it can go
 * on the left ("−30:00"), the part it holds so far, where the picture is (with "−1:20" over it
 * when behind), and LIVE at the end: red when watching live, grey when behind. On a phone the
 * bar can be tapped or dragged to go back, and LIVE tapped to catch up.
 */
@SuppressLint("ViewConstructor")
class LiveTimeline(
    context: Context,
    /** Go to this far behind live (ms). */
    private val onSeek: (Long) -> Unit,
    private val onLive: () -> Unit,
) : View(context) {
    /** How far back the buffer can go (the length chosen in Settings). */
    private var windowMs = 30 * 60_000L

    /** How much of that the buffer holds so far. */
    private var bufferedMs = 0L

    /** How far behind live the picture is. */
    private var behindMs = 0L

    /** While a finger is on the bar: where it would go (ms behind live). */
    private var dragBehind: Long? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val font = runCatching { ResourcesCompat.getFont(context, R.font.inter_medium) }.getOrNull()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val held = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
    private val played = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF5A3C.toInt() }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
        textSize = dp(13f)
        typeface = font
        fontFeatureSettings = "tnum"
    }
    private val liveText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = dp(12f)
        typeface = font
        isFakeBoldText = true
        letterSpacing = 0.08f
    }
    private val liveBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    fun set(window: Long, buffered: Long, behind: Long) {
        val w = window.coerceAtLeast(60_000L)
        val b = buffered.coerceIn(0L, w)
        val h = behind.coerceIn(0L, w)
        if (w == windowMs && b / 1000 == bufferedMs / 1000 && h / 1000 == behindMs / 1000) return
        windowMs = w
        bufferedMs = b
        behindMs = h
        invalidate()
    }

    /** At live (within 10 s of it). */
    private fun atLive(behind: Long) = behind < 10_000L

    private fun label(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0)
        return if (s >= 3600) {
            String.format(Locale.US, "−%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        } else {
            String.format(Locale.US, "−%d:%02d", s / 60, s % 60)
        }
    }

    // The bar's ends (after the left label, before LIVE).
    private fun barLeft() = paddingLeft + text.measureText("−00:00") + dp(14f)
    private fun liveWidth() = liveText.measureText("LIVE") + dp(18f)
    private fun barRight() = width - paddingRight - liveWidth() - dp(14f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val y = h * 0.62f
        val l = barLeft()
        val r = barRight()
        if (r <= l) return
        val span = r - l
        val behind = dragBehind ?: behindMs
        fun xFor(behind: Long) = r - span * (behind.toFloat() / windowMs)
        val thick = dp(4f)
        // How far back it can go, on the left.
        canvas.drawText(label(windowMs), paddingLeft.toFloat(), y + text.textSize * 0.36f, text)
        box.set(l, y - thick / 2, r, y + thick / 2)
        canvas.drawRoundRect(box, thick, thick, track)
        // What the buffer holds so far, and the part watched up to where the picture is.
        val heldFrom = xFor(bufferedMs)
        box.set(heldFrom, y - thick / 2, r, y + thick / 2)
        canvas.drawRoundRect(box, thick, thick, held)
        val at = xFor(behind).coerceIn(l, r)
        box.set(heldFrom.coerceAtMost(at), y - thick / 2, at, y + thick / 2)
        canvas.drawRoundRect(box, thick, thick, played)
        canvas.drawCircle(at, y, dp(if (dragBehind != null) 9f else 7f), dot)
        // "−1:20" over the picture's place, when behind.
        if (!atLive(behind)) {
            val t = label(behind)
            val tw = text.measureText(t)
            canvas.drawText(t, (at - tw / 2).coerceIn(l, r - tw), y - dp(14f), text)
        }
        // LIVE: red at live, grey when behind.
        val lw = liveWidth()
        val lx = width - paddingRight - lw
        val lh = dp(22f)
        liveBg.color = if (atLive(behind)) 0xFFFF5A3C.toInt() else 0x40FFFFFF
        box.set(lx, y - lh / 2, lx + lw, y + lh / 2)
        canvas.drawRoundRect(box, dp(5f), dp(5f), liveBg)
        canvas.drawText("LIVE", lx + dp(9f), y + liveText.textSize * 0.36f, liveText)
    }

    /** How far behind live a finger at [x] means (only as far back as the buffer goes). */
    private fun behindAt(x: Float): Long {
        val l = barLeft()
        val r = barRight()
        if (r <= l) return 0L
        val f = ((r - x.coerceIn(l, r)) / (r - l)).coerceIn(0f, 1f)
        return (f * windowMs).toLong().coerceAtMost(bufferedMs)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                // On LIVE: nothing to drag, it catches up when the finger lifts.
                dragBehind = if (event.x >= barRight() + dp(6f)) null else behindAt(event.x)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (dragBehind != null) {
                dragBehind = behindAt(event.x)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val target = dragBehind
                dragBehind = null
                invalidate()
                if (target == null) onLive() else if (atLive(target)) onLive() else onSeek(target)
            }
            MotionEvent.ACTION_CANCEL -> {
                dragBehind = null
                invalidate()
            }
        }
        return true
    }
}
