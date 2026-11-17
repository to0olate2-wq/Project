package app.falak.player.player

/**
 * A stream that freezes is started again (8.31; the owner picked it: other apps reload a channel
 * that hangs). Fed every 2 seconds with what the player is doing; says when to start it again and
 * when to stop trying. No Android here (tested on its own, FreezeRuleTest).
 *
 * - Stuck: loading (buffering) without end, or "playing" with the picture standing still (the
 *   position doesn't move; ExoPlayer only, VLC's clock isn't sure enough for that).
 * - How long counts as stuck: a live channel 12 s, a film or episode 20 s; before the first picture
 *   came, 20 s and 30 s (a slow start isn't a freeze).
 * - At most 3 restarts in 3 minutes for one channel or film; then it says so and stops trying.
 */
class FreezeRule {
    enum class Action { NONE, RESTART, GIVE_UP }

    private var stuckSince = -1L  // (-1: not stuck)
    private var lastPos = Long.MIN_VALUE
    private var item: String? = null
    private val restarts = ArrayDeque<Long>()
    private var gaveUp = false

    /**
     * [watching]: meant to be playing (not paused, not ended, not casting, nothing failed);
     * [buffering] / [ready] / [playing] as the player says; [position] in ms; [moves] whether a
     * still position means frozen (ExoPlayer); [started]: the first picture came; [live].
     */
    fun step(
        now: Long,
        itemId: String,
        watching: Boolean,
        buffering: Boolean,
        ready: Boolean,
        playing: Boolean,
        position: Long,
        moves: Boolean,
        started: Boolean,
        live: Boolean,
    ): Action {
        if (itemId != item) {
            item = itemId
            restarts.clear()
            gaveUp = false
            stuckSince = -1
            lastPos = Long.MIN_VALUE
        }
        val moved = position != lastPos
        lastPos = position
        val stuck = watching && (buffering || (moves && ready && playing && !moved))
        if (!stuck || gaveUp) {
            stuckSince = -1
            return Action.NONE
        }
        if (stuckSince < 0) {
            stuckSince = now
            return Action.NONE
        }
        val limit = when {
            !started -> if (live) 20_000L else 30_000L
            live -> 12_000L
            else -> 20_000L
        }
        if (now - stuckSince < limit) return Action.NONE
        stuckSince = -1
        lastPos = Long.MIN_VALUE
        while (restarts.isNotEmpty() && now - restarts.first() > 180_000L) restarts.removeFirst()
        if (restarts.size >= 3) {
            gaveUp = true
            return Action.GIVE_UP
        }
        restarts.addLast(now)
        return Action.RESTART
    }

    /** Played well again after a restart: it starts counting afresh. */
    fun forget() {
        item = null
    }
}
