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

    /**
     * Every profile's picture: id ("" = main, [KIDS_PROFILE]) → key. Picked ones as picked; the
     * others a steady one from their id, moved on to the next free one when another profile has it.
     */
    fun all(state: AppState): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val picked = LinkedHashMap<String, String>()
        picked[""] = state.settings.mainAvatar
        state.profiles.forEach { picked[it.id] = it.avatar }
        picked[KIDS_PROFILE] = state.settings.kidsAvatar.ifBlank { KIDS_DEFAULT }
        picked.forEach { (id, key) -> if (key in KEYS) out[id] = key }
        val used = out.values.toMutableSet()
        picked.keys.filter { it !in out }.forEach { id ->
            val start = KEYS.indexOf(of(id, ""))
            val free = KEYS.indices.map { KEYS[(start + it) % KEYS.size] }.firstOrNull { it !in used } ?: KEYS[start]
            out[id] = free
            used += free
        }
        return out
    }

    /** The pictures in use: the main profile's, the others', the Kids one's. */
    fun inUse(state: AppState): List<String> = all(state).values.toList()
}
