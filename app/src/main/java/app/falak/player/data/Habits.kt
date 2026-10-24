package app.falak.player.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * Home's "For this hour": the live channels you usually watch around this time of day, learned on
 * this device from what you watch (a channel counts after two minutes). Kept in the app's own
 * storage, never sent anywhere: the last 600 viewings, the ones from the past two months used.
 */
object Habits {
    @Serializable
    data class Seen(val key: String, val id: String, val at: Long)

    private const val FILE = "habits.json"
    private const val MAX = 600
    private const val KEEP_MS = 60L * 86_400_000L
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Seen.serializer())
    private val lock = Any()

    /** [id] watched now, for the playlist/profile [key] (see AppState.mine). */
    fun record(dir: File, key: String, id: String, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val list = read(dir).filter { now - it.at < KEEP_MS }.toMutableList()
            list += Seen(key, id, now)
            val kept = if (list.size > MAX) list.takeLast(MAX) else list
            runCatching { File(dir, FILE).writeText(json.encodeToString(serializer, kept)) }
        }
    }

    fun read(dir: File): List<Seen> = synchronized(lock) {
        try {
            val f = File(dir, FILE)
            if (f.exists()) json.decodeFromString(serializer, f.readText()) else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Channel ids for around [now], best first: viewings within 75 minutes of this time of day count,
     * more on the same weekday and the more recent; a channel needs viewings on 2 different days.
     */
    fun forNow(seen: List<Seen>, key: String, now: Long, zone: TimeZone = TimeZone.getDefault(), max: Int = 10): List<String> {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = now
        val nowMinute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val nowDay = cal.get(Calendar.DAY_OF_WEEK)
        val score = HashMap<String, Double>()
        val days = HashMap<String, MutableSet<Long>>()
        for (s in seen) {
            if (s.key != key || s.at > now || now - s.at > KEEP_MS) continue
            cal.timeInMillis = s.at
            val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            var diff = kotlin.math.abs(minute - nowMinute)
            if (diff > 720) diff = 1440 - diff
            if (diff > 75) continue
            val near = 1.0 - diff / 75.0 * 0.6
            val sameDay = if (cal.get(Calendar.DAY_OF_WEEK) == nowDay) 1.0 else 0.6
            val ageDays = (now - s.at) / 86_400_000.0
            val fresh = Math.exp(-ageDays / 21.0)
            score[s.id] = (score[s.id] ?: 0.0) + near * sameDay * fresh
            days.getOrPut(s.id) { HashSet() } += (s.at + zone.getOffset(s.at)) / 86_400_000L
        }
        return score.entries
            .filter { (days[it.key]?.size ?: 0) >= 2 && it.value >= 0.8 }
            .sortedByDescending { it.value }
            .take(max)
            .map { it.key }
    }
}
