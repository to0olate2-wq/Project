package app.falak.player.data

/**
 * Profile pictures (8.38; the owner: "random cartoon pictures"): original cartoon characters drawn
 * for the app (res/drawable/avatar_*.xml, made by a small script from shapes; no known character).
 * A new profile gets one at random that no other profile has; one without a pick yet (made before
 * 8.38) gets a steady one from its id. The main profile's and the Kids profile's are in Settings.
 */
object Avatars {
    val KEYS = listOf(
        "fox", "bear", "cat", "owl", "frog", "robot", "alien", "penguin", "bunny", "monster", "lion", "octopus", "ghost",
        // 8.40: characters too (all made for the app).
        "astronaut", "pirate", "wizard", "ninja", "viking", "chef", "dragon", "unicorn", "tiger", "koala", "shark", "dino",
    )

    /**
     * Your own photo (8.40, phones): "photo:<file>" in [photoDir] (filesDir/avatars, set at start).
     * The file stays on the phone it was picked on; other devices show the profile's cartoon.
     */
    const val PHOTO = "photo:"
    @Volatile var photoDir: java.io.File? = null

    fun photoFile(key: String): java.io.File? =
        if (!key.startsWith(PHOTO)) null else photoDir?.let { java.io.File(it, key.removePrefix(PHOTO)) }?.takeIf { it.isFile }

    /** One of ours, or a photo that is on this device. */
    fun usable(key: String): Boolean = key in KEYS || photoFile(key) != null

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
        picked.forEach { (id, key) -> if (usable(key)) out[id] = key }
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
