package app.falak.player.player

import android.graphics.Color
import android.graphics.Typeface
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

/**
 * The subtitle look chosen in Settings (size, colour, dark box or outline), turned into what each
 * player needs. Left as it comes (Normal, White, Dark box), the players draw subtitles exactly
 * as they always have: nothing is changed. A chosen look only restyles the text; what the
 * subtitles say, where they sit and the picture subtitles (DVB, PGS) stay as the stream sends them.
 */
object SubtitleStyle {
    val SIZES = listOf("Small", "Normal", "Large", "Extra large")

    /** The look as it comes: the players keep their own subtitle drawing. */
    fun isDefault(size: Int, color: String, edge: String): Boolean = size == 1 && color != "yellow" && edge != "outline"

    /** Text size against the players' standard size. */
    fun scale(size: Int): Float = floatArrayOf(0.8f, 1f, 1.25f, 1.5f)[size.coerceIn(0, 3)]

    /** ARGB of the letters. */
    fun textColor(color: String): Int = if (color == "yellow") 0xFFFFE14D.toInt() else 0xFFFFFFFF.toInt()

    /** The dark band behind the text (70 % black). */
    const val BOX = 0xB3000000.toInt()

    /** ExoPlayer's subtitle view in the chosen look (or its usual look when nothing was chosen). */
    fun applyTo(view: SubtitleView, size: Int, color: String, edge: String) {
        if (isDefault(size, color, edge)) {
            // Exactly what the player view does by itself (the system's caption settings).
            view.setUserDefaultStyle()
            view.setUserDefaultTextSize()
            view.setApplyEmbeddedStyles(true)
            view.setApplyEmbeddedFontSizes(true)
            return
        }
        val box = edge != "outline"
        view.setStyle(
            CaptionStyleCompat(
                textColor(color),
                if (box) BOX else Color.TRANSPARENT,
                Color.TRANSPARENT,
                if (box) CaptionStyleCompat.EDGE_TYPE_NONE else CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK,
                Typeface.DEFAULT,
            ),
        )
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale(size))
        // The subtitles' own styling (italics, colours, positions) stays; only a chosen size
        // replaces sizes written into the subtitle file.
        view.setApplyEmbeddedStyles(true)
        view.setApplyEmbeddedFontSizes(size == 1)
    }

    /**
     * libVLC options for the chosen look; none when nothing was chosen (VLC's own look). VLC's
     * relative font size divides the picture height, so a smaller number means bigger text
     * (16 is VLC's normal).
     */
    fun vlcOptions(size: Int, color: String, edge: String): List<String> {
        if (isDefault(size, color, edge)) return emptyList()
        return listOf(
            "--freetype-rel-fontsize=" + intArrayOf(20, 16, 13, 11)[size.coerceIn(0, 3)],
            "--freetype-color=" + (textColor(color) and 0xFFFFFF),
            "--freetype-background-color=0",
            "--freetype-background-opacity=" + if (edge == "box") 179 else 0,
        )
    }
}
