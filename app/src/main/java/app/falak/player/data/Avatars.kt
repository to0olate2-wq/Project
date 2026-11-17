package app.falak.player.data

/**
 * Profile pictures (8.38; the owner: "random cartoon pictures"): original cartoon characters drawn
 * for the app (res/drawable/avatar_*.xml, made by a small script from shapes; no known character).
 * A new profile gets one at random that no other profile has; one without a pick yet (made before
 * 8.38) gets a steady one from its id. The main profile's and the Kids profile's are in Settings.
 */
object Avatars {
    val KEYS = listOf("fox", "bear", "cat", "owl", "frog", "robot", "alien", "penguin", "bunny", "monster", "lion", "octopus", "ghost")

    /** The Kids profile's picture when none was picked. */
    const val KIDS_DEFAULT = "octopus"

    /** [chosen] when it is one of ours, else a steady pick from [id]. */
    fun of(id: String, chosen: String): String =
        chosen.takeIf { it in KEYS } ?: KEYS[Math.floorMod(("avatar:" + id).hashCode(), KEYS.size)]

    /** A random one, not among [taken] while any is left. */
    fun random(taken: Collection<String>, rnd: kotlin.random.Random = kotlin.random.Random.Default): String =
        (KEYS - taken.toSet()).ifEmpty { KEYS }.random(rnd)

    /** The pictures in use: the main profile's, the others', the Kids one's. */
    fun inUse(state: AppState): List<String> =
        listOf(of("", state.settings.mainAvatar)) + state.profiles.map { of(it.id, it.avatar) } +
            of(KIDS_PROFILE, state.settings.kidsAvatar.ifBlank { KIDS_DEFAULT })
}
