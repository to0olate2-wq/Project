package app.falak.player.player

/**
 * The subtitle look chosen in Settings (size, colour, dark box or outline), turned into what each
 * player needs, so ExoPlayer and VLC show subtitles the same way.
 */
object SubtitleStyle {
    val SIZES = listOf("Small", "Normal", "Large", "Extra large")

    /** Text size against the players' standard size. */
    fun scale(size: Int): Float = floatArrayOf(0.8f, 1f, 1.25f, 1.5f)[size.coerceIn(0, 3)]

    /** ARGB of the letters. */
    fun textColor(color: String): Int = if (color == "yellow") 0xFFFFE14D.toInt() else 0xFFFFFFFF.toInt()

    /** The dark band behind the text (70 % black). */
    const val BOX = 0xB3000000.toInt()

    /**
     * libVLC options for the same look. VLC's relative font size divides the picture height, so a
     * smaller number means bigger text (16 is VLC's normal).
     */
    fun vlcOptions(size: Int, color: String, edge: String): List<String> = listOf(
        "--freetype-rel-fontsize=" + intArrayOf(20, 16, 13, 11)[size.coerceIn(0, 3)],
        "--freetype-color=" + (textColor(color) and 0xFFFFFF),
        "--freetype-background-color=0",
        "--freetype-background-opacity=" + if (edge == "box") 179 else 0,
    )
}
