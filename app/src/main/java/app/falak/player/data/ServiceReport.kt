package app.falak.player.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** What the player saw of one playlist's provider, hour by hour (see [ServiceReport]). */
@Serializable
data class ServiceLog(
    /** "yyyyMMddHH" (local time) → [time watched (ms), buffering stops, starts, time to start (ms, all added up), failures]. */
    val hours: Map<String, List<Long>> = emptyMap(),
    /** Channel name → [times it wouldn't play, last time (epoch ms)]. */
    val failed: Map<String, List<Long>> = emptyMap(),
)

/**
 * Provider report card (Settings → Playlists & TV guide → Service report): how the provider
 * actually performs, from what was played on this device over the last 7 days: how fast
 * channels start, how often they stop to load, which ones wouldn't play, and the times of day
 * when it gets slow. Worked out and kept on the device only.
 */
object ServiceReport {
    const val DAYS = 7
    private const val KEEP_DAYS = 14
    private const val DAY_MS = 86_400_000L
    private const val HOUR_MS = 3_600_000L
    private const val WATCHED = 0
    private const val STALLS = 1
    private const val STARTS = 2
    private const val START_MS = 3
    private const val FAILS = 4

    enum class Verdict(val label: String) { GOOD("Good"), FAIR("Fair"), POOR("Poor") }

    class Summary(
        val watchedMs: Long,
        val starts: Int,
        /** Average time from choosing a channel to its picture; null with too few starts. */
        val averageStartMs: Long?,
        /** Buffering stops per hour watched; null with too little watched. */
        val stallsPerHour: Double?,
        val failures: Int,
        /** Channels that wouldn't play this week, most often first. */
        val failedChannels: List<Pair<String, Int>>,
        /** Hours of the day (0–23) when it stops to load far more than usual, as ranges. */
        val slowHours: List<IntRange>,
        /** How many times more it stops to load then; null when it can't be told. */
        val slowFactor: Double?,
        /** Null until enough was watched to judge. */
        val verdict: Verdict?,
    )

    fun hourKey(at: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(zone)
        c.timeInMillis = at
        return String.format(
            Locale.US, "%04d%02d%02d%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY),
        )
    }

    /** [log] with what happened at [at] added. */
    fun add(
        log: ServiceLog,
        at: Long,
        zone: TimeZone = TimeZone.getDefault(),
        watchedMs: Long = 0,
        stalls: Int = 0,
        startMs: Long? = null,
        failed: String? = null,
    ): ServiceLog {
        val key = hourKey(at, zone)
        val cur = log.hours[key]?.takeIf { it.size >= 5 } ?: listOf(0L, 0L, 0L, 0L, 0L)
        val next = listOf(
            cur[WATCHED] + watchedMs.coerceAtLeast(0),
            cur[STALLS] + stalls,
            cur[STARTS] + if (startMs != null) 1 else 0,
            cur[START_MS] + (startMs ?: 0L).coerceAtLeast(0),
            cur[FAILS] + if (failed != null) 1 else 0,
        )
        var failedMap = log.failed
        if (failed != null) {
            val f = failedMap[failed]
            failedMap = failedMap + (failed to listOf((f?.getOrNull(0) ?: 0L) + 1, at))
        }
        return ServiceLog(log.hours + (key to next), failedMap)
    }

    /** Only the last two weeks, and the 50 channels that failed most recently. */
    fun prune(log: ServiceLog, now: Long, zone: TimeZone = TimeZone.getDefault()): ServiceLog {
        val from = hourKey(now - KEEP_DAYS * DAY_MS, zone)
        val failed = log.failed.entries
            .filter { (it.value.getOrNull(1) ?: 0L) >= now - KEEP_DAYS * DAY_MS }
            .sortedByDescending { it.value.getOrNull(1) ?: 0L }
            .take(50)
            .associate { it.key to it.value }
        return ServiceLog(log.hours.filterKeys { it >= from }, failed)
    }

    fun summary(log: ServiceLog, now: Long, zone: TimeZone = TimeZone.getDefault()): Summary {
        val from = hourKey(now - DAYS * DAY_MS, zone)
        var watched = 0L
        var stalls = 0L
        var starts = 0L
        var startMs = 0L
        var fails = 0L
        val byHourWatched = LongArray(24)
        val byHourStalls = LongArray(24)
        for ((k, v) in log.hours) {
            if (k < from || v.size < 5) continue
            val h = k.takeLast(2).toIntOrNull()?.takeIf { it in 0..23 } ?: continue
            watched += v[WATCHED]
            stalls += v[STALLS]
            starts += v[STARTS]
            startMs += v[START_MS]
            fails += v[FAILS]
            byHourWatched[h] += v[WATCHED]
            byHourStalls[h] += v[STALLS]
        }
        val perHour = if (watched >= 30 * 60_000L) stalls / (watched.toDouble() / HOUR_MS) else null
        val avgStart = if (starts >= 3) startMs / starts else null
        val failedChannels = log.failed.entries
            .filter { (it.value.getOrNull(1) ?: 0L) >= now - DAYS * DAY_MS }
            .sortedWith(compareByDescending<Map.Entry<String, List<Long>>> { it.value.getOrNull(0) ?: 0L }.thenBy { it.key })
            .map { it.key to (it.value.getOrNull(0) ?: 0L).toInt() }

        // Slow times: hours with at least 20 minutes watched that stop to load at least 3 times an
        // hour, and at least twice as often as the other hours do (which need 30 minutes watched).
        val slow = BooleanArray(24)
        if (perHour != null) {
            fun rateAt(h: Int) = byHourStalls[h] / (byHourWatched[h].toDouble() / HOUR_MS)
            val candidates = (0..23).filter { byHourWatched[it] >= 20 * 60_000L && rateAt(it) >= 3.0 }
            var otherW = 0L
            var otherS = 0L
            for (h in 0..23) {
                if (h in candidates) continue
                otherW += byHourWatched[h]
                otherS += byHourStalls[h]
            }
            if (otherW >= 30 * 60_000L) {
                val usual = otherS / (otherW.toDouble() / HOUR_MS)
                for (h in candidates) if (rateAt(h) >= 2 * usual) slow[h] = true
            }
        }
        val ranges = ArrayList<IntRange>()
        var h = 0
        while (h < 24) {
            if (!slow[h]) {
                h++
                continue
            }
            val start = h
            while (h < 24 && slow[h]) h++
            ranges += start until h
        }
        var factor: Double? = null
        if (ranges.isNotEmpty()) {
            var inW = 0L
            var inS = 0L
            var outW = 0L
            var outS = 0L
            for (i in 0..23) {
                if (slow[i]) {
                    inW += byHourWatched[i]
                    inS += byHourStalls[i]
                } else {
                    outW += byHourWatched[i]
                    outS += byHourStalls[i]
                }
            }
            if (outW >= 30 * 60_000L && outS > 0) factor = (inS / (inW.toDouble() / HOUR_MS)) / (outS / (outW.toDouble() / HOUR_MS))
        }

        val attempts = starts + fails
        val enough = starts >= 10 || watched >= HOUR_MS
        val verdict = if (!enough) {
            null
        } else {
            val failRate = if (attempts > 0) fails.toDouble() / attempts else 0.0
            val p = perHour ?: 0.0
            val a = avgStart ?: 0L
            when {
                p >= 4.0 || failRate >= 0.2 || a >= 8_000L -> Verdict.POOR
                p < 1.0 && failRate < 0.05 && a < 3_000L -> Verdict.GOOD
                else -> Verdict.FAIR
            }
        }
        return Summary(watched, starts.toInt(), avgStart, perHour, fails.toInt(), failedChannels, ranges, factor, verdict)
    }

    /** "1.4 s" */
    fun seconds(ms: Long): String = String.format(Locale.US, "%.1f s", ms / 1000.0)

    /** "0.6", "12" */
    fun rate(r: Double): String = if (r < 10) String.format(Locale.US, "%.1f", r) else r.toInt().toString()

    /** "9–11 PM", "11 PM–1 AM" ([hourLabel] gives an hour's name, "9 PM" or "21:00"). */
    fun hoursLabel(range: IntRange, hourLabel: (Int) -> String): String =
        hourLabel(range.first) + "–" + hourLabel((range.last + 1) % 24)

    /** "12 h 30 min", "45 min" */
    fun duration(ms: Long): String {
        val min = ms / 60_000L
        return if (min < 60) "$min min" else if (min % 60 == 0L) "${min / 60} h" else "${min / 60} h ${min % 60} min"
    }

    /** One line for Settings. */
    fun line(s: Summary): String {
        if (s.verdict == null) {
            return if (s.watchedMs == 0L && s.starts == 0 && s.failures == 0) {
                "Nothing watched this week yet: it fills in as you watch"
            } else {
                "Not enough watched this week to judge yet"
            }
        }
        val parts = ArrayList<String>()
        parts += s.verdict.label
        s.averageStartMs?.let { parts += "channels start in ${seconds(it)}" }
        s.stallsPerHour?.let { parts += "${rate(it)} buffering stops an hour" }
        when (s.failedChannels.size) {
            0 -> Unit
            1 -> parts += "1 channel didn't play"
            else -> parts += "${s.failedChannels.size} channels didn't play"
        }
        return parts.joinToString(" · ")
    }
}

/** The player's figures for [ServiceReport], kept in a small file per playlist. */
object ServiceLogStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var openId: String? = null
    private var open = ServiceLog()
    private var dirty = false

    private fun file(dir: File, id: String) = File(File(dir, "service"), "$id.json")

    private fun load(dir: File, id: String): ServiceLog = try {
        val f = file(dir, id)
        if (f.exists()) json.decodeFromString(ServiceLog.serializer(), f.readText()) else ServiceLog()
    } catch (e: Exception) {
        ServiceLog()
    }

    /** The playlist's log as it is now (with what hasn't been saved yet). */
    fun read(dir: File, id: String): ServiceLog = synchronized(lock) {
        if (id == openId) open else load(dir, id)
    }

    fun update(dir: File, id: String, change: (ServiceLog) -> ServiceLog) {
        synchronized(lock) {
            if (openId != id) {
                saveLocked(dir)
                open = load(dir, id)
                openId = id
            }
            open = change(open)
            dirty = true
        }
    }

    /** Writes what changed (called off the main thread). */
    fun save(dir: File) = synchronized(lock) { saveLocked(dir) }

    private fun saveLocked(dir: File) {
        val id = openId ?: return
        if (!dirty) return
        val f = file(dir, id)
        try {
            f.parentFile?.mkdirs()
            f.writeText(json.encodeToString(ServiceLog.serializer(), ServiceReport.prune(open, System.currentTimeMillis())))
            dirty = false
        } catch (e: Exception) {
            // kept in memory; tried again next time
        }
    }

    /** Starts afresh (Settings, or the playlist removed). */
    fun clear(dir: File, id: String) = synchronized(lock) {
        if (openId == id) {
            open = ServiceLog()
            dirty = false
        }
        file(dir, id).delete()
    }
}
