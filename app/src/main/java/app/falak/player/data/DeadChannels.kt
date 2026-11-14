package app.falak.player.data

/**
 * Channels that don't work (8.24; the owner asked for "hide dead channels"). A channel that
 * couldn't be opened after everything was tried is hidden from the Live TV rows and lists once
 * another channel plays within ten minutes: that shows it was the channel, not the internet or
 * the provider being away. It comes back by itself after [KEEP_MS], to be tried again, and at
 * once when it plays (from Favorites, the search, or "Show" under the list). Kept on this
 * device only (AppState.dead: playlist → channel → when).
 */
object DeadChannels {
    /** A hidden channel is shown again after this long. */
    const val KEEP_MS = 7 * 24 * 3_600_000L

    /** Another channel has to play within this long of the failure. */
    const val CONFIRM_MS = 10 * 60_000L

    /** A channel that played this lately has only hiccupped. */
    const val WORKED_MS = 30 * 60_000L

    /** More failures than this waiting at once is the provider or the network, not the channels. */
    const val TOO_MANY = 5

    private const val MAX = 4000

    /** The channels hidden now. */
    fun hidden(marks: Map<String, Long>?, now: Long): Set<String> =
        if (marks.isNullOrEmpty()) emptySet() else marks.filterValues { now - it < KEEP_MS }.keys

    fun mark(marks: Map<String, Long>, ids: Collection<String>, now: Long): Map<String, Long> {
        if (ids.isEmpty()) return marks
        val out = LinkedHashMap(marks.filterValues { now - it < KEEP_MS })
        for (id in ids) out[id] = now
        return if (out.size <= MAX) out else out.entries.sortedByDescending { it.value }.take(MAX).associate { it.key to it.value }
    }

    /** What happened lately, in memory: which failures wait for another channel to play. */
    class Tracker {
        private val waiting = LinkedHashMap<String, Long>()
        private val worked = HashMap<String, Long>()
        private var lastOk: Long? = null
        private var lastOkId = ""

        /**
         * [id] couldn't be opened. Returns the channels to hide now: itself when another channel
         * played a moment ago, nothing otherwise (it waits for one to play).
         */
        @Synchronized
        fun failed(id: String, now: Long): List<String> {
            if (worked[id]?.let { now - it < WORKED_MS } == true) return emptyList()
            if (lastOkId != id && lastOk?.let { now - it < CONFIRM_MS / 3 } == true) return listOf(id)
            waiting[id] = now
            return emptyList()
        }

        /** [id] plays. Returns the channels that failed lately, now known to be the ones at fault. */
        @Synchronized
        fun played(id: String, now: Long): List<String> {
            worked[id] = now
            lastOk = now
            lastOkId = id
            waiting.remove(id)
            val fresh = waiting.filterValues { now - it < CONFIRM_MS }.keys.toList()
            waiting.clear()
            return if (fresh.size > TOO_MANY) emptyList() else fresh
        }
    }
}
