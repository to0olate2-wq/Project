package app.falak.player.player

import androidx.media3.common.text.Cue

/**
 * Where captions go on the picture, and how big they are. The stream's own captions and auto
 * captions follow the same rules, so both take exactly the same space:
 *  - they're drawn in the picture's own box (letterboxed or pillarboxed as the picture is),
 *  - kept on the part of the picture that's on screen (Zoom lets it spill past the edges),
 *  - at the players' usual size, about 1/19 of the picture's height.
 */
object CaptionLayout {
    /** Auto captions' places: key and name. */
    val POSITIONS = listOf("bottom" to "Bottom", "raised" to "Raised", "top" to "Top")

    /** Auto captions' lines at once. */
    val LINES = listOf(1, 2, 3)

    /** The text size against the picture's height (Media3's SubtitleView.DEFAULT_TEXT_SIZE_FRACTION). */
    const val SIZE_FRACTION = 0.0533f

    /** No line longer than this many letters: the broadcast standard, easy to read at a glance. */
    const val MAX_LINE_CHARS = 42

    /** "Raised": the last line's bottom at 78 % of the height, clear of news tickers and name straps. */
    const val RAISED_LINE = 0.78f

    /** "Top": the first line's top at 8 % of the height (as far from the edge as "Bottom" is). */
    const val TOP_LINE = 0.08f

    /** What the caption style panel shows while nothing is being said. */
    const val SAMPLE =
        "Good evening, and welcome. We're going live now to the stadium, where the teams are coming out for the second half."

    /** The stream's own captions' sample (one line, as in Settings). */
    const val STREAM_SAMPLE = "Subtitles will look like this"

    fun position(key: String): String = if (POSITIONS.any { it.first == key }) key else "bottom"

    fun lines(n: Int): Int = n.coerceIn(1, 3)

    /**
     * Padding (left, top, right, bottom) that keeps captions on the part of the picture that's on
     * screen. [left]..[bottom]: the picture's box inside a view [w] × [h]. With Zoom the box is
     * bigger than the view, and captions at its very bottom would be cut off.
     */
    fun padding(left: Int, top: Int, right: Int, bottom: Int, w: Int, h: Int): IntArray =
        intArrayOf(maxOf(0, -left), maxOf(0, -top), maxOf(0, right - w), maxOf(0, bottom - h))

    /**
     * The text size in pixels for captions in an area [w] × [h] ([scale]: the size chosen, 1 =
     * Normal). An area taller than 4:3 (a portrait video, or an upright phone on Zoom) counts as
     * 3/4 of its width high, or the letters would fill the screen.
     */
    fun textPx(w: Int, h: Int, scale: Float): Float = SIZE_FRACTION * scale * minOf(h, w * 3 / 4)

    /** How wide a line of text may be in an area [w] wide: the painter keeps 1/8 of the text size free on each side. */
    fun lineWidth(w: Int, textPx: Float): Float = (w - 2 * (textPx * 0.125f + 0.5f).toInt()).toFloat()

    /**
     * Where a cue of auto captions goes: the fraction of the height and the edge it's anchored by,
     * or null for the bottom (left to the view, exactly where the stream's own captions go).
     */
    fun line(position: String): Pair<Float, Int>? = when (position) {
        "raised" -> RAISED_LINE to Cue.ANCHOR_TYPE_END
        "top" -> TOP_LINE to Cue.ANCHOR_TYPE_START
        else -> null
    }
}
