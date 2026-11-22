package app.falak.player.data

/**
 * What the player's information shows (8.45; the owner picked "choose what the player's info bar
 * shows" of what premium apps have): the channel banner when zapping, and the top of the controls.
 * The name of what plays always shows. Everything was on before 8.45 except the clock, so DEFAULT
 * changes nothing for anyone.
 */
object PlayerInfo {
    const val CLOCK = "clock"
    const val NUMBER = "number"
    const val NOW = "now"
    const val LEFT = "left"
    const val NEXT = "next"
    const val QUALITY = "quality"
    const val TRACKS = "tracks"

    val DEFAULT = listOf(NUMBER, NOW, LEFT, NEXT, QUALITY, TRACKS)

    /** In the order shown in Settings, with their names. */
    val CHOICES = listOf(
        CLOCK to "The time",
        NUMBER to "Channel number",
        NOW to "What's on now",
        LEFT to "Time left",
        NEXT to "What's on next",
        QUALITY to "Picture quality and speed",
        TRACKS to "Sound and subtitles",
    )

    /** [shown] with [key] turned on or off (kept in the order of [CHOICES]). */
    fun toggled(shown: List<String>, key: String): List<String> {
        val on = if (key in shown) shown - key else shown + key
        return CHOICES.map { it.first }.filter { it in on }
    }
}
