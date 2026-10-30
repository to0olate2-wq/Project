package app.falak.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.util.Calendar

/**
 * The Kids profile's day on one device, sent to linked devices so the daily limit, extra time,
 * "no limit today" and a lifted bedtime count on all of them.
 */
@Serializable
data class KidsShare(
    /** "yyyyMMdd" (see KidsClock.today). */
    val day: String,
    val usedMs: Long = 0,
    val extraMinutes: Int = 0,
    val unlimited: Boolean = false,
    val bedtimeLiftedUntil: Long = 0,
    /** When a grown-up last set today's count back to nothing (on any device). */
    val resetAt: Long = 0,
)

/**
 * Kids screen time (Settings → Profiles & parental control, with kids mode on): how long the Kids
 * profile may watch each day, and a bedtime until the morning. When time is up the film or episode
 * on finishes if it ends soon (a live programme the same), then playing stops with a friendly
 * page; a grown-up's PIN adds time. Counted while something plays in the Kids profile.
 */
object KidsTime {
    enum class Block { TIME_UP, BEDTIME }

    /** [block]: why nothing may play now (null: it may); [minutesLeft]: of today's time (null: no limit). */
    data class State(val block: Block?, val minutesLeft: Int?)

    /** What finishes before stopping: up to this long left in it. */
    const val GRACE_MS = 20 * 60_000L

    /** [minute] (of the day) falls in the night from [bedtime] to [wake] (both minutes of the day; bedtime -1 = off). */
    fun inBedtime(minute: Int, bedtime: Int, wake: Int): Boolean {
        if (bedtime < 0 || bedtime == wake) return false
        return if (bedtime > wake) minute >= bedtime || minute < wake else minute >= bedtime && minute < wake
    }

    fun state(
        usedMs: Long,
        dailyMinutes: Int,
        extraMinutes: Int,
        unlimitedToday: Boolean,
        minute: Int,
        bedtime: Int,
        wake: Int,
        bedtimeLifted: Boolean,
    ): State {
        val left = if (dailyMinutes <= 0 || unlimitedToday) {
            null
        } else {
            val ms = (dailyMinutes + extraMinutes) * 60_000L - usedMs
            ((ms + 59_999L) / 60_000L).toInt().coerceAtLeast(0)
        }
        val block = when {
            !bedtimeLifted && inBedtime(minute, bedtime, wake) -> Block.BEDTIME
            left != null && left <= 0 -> Block.TIME_UP
            else -> null
        }
        return State(block, left)
    }

    /** "1 h 30 min", "45 min" */
    fun duration(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}

/**
 * Today's kids screen time on this device: minutes watched, minutes a grown-up added, "no limit
 * today", and bedtime lifted for tonight. A new day starts afresh.
 */
object KidsClock {
    private const val PREFS = "kids_time"

    /** Why the Kids profile was just stopped (the main screen shows its page); null when it's closed. */
    private val _blocked = MutableStateFlow<KidsTime.Block?>(null)
    val blocked: StateFlow<KidsTime.Block?> = _blocked.asStateFlow()

    /** Watched but not yet written down (written every 15 seconds). */
    private var pendingMs = 0L

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun today(now: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        return String.format(java.util.Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun minuteOfDay(now: Long): Int {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** "yyyyMMdd:value" kept only for today. */
    private fun todays(context: Context, key: String, now: Long): Long {
        val v = prefs(context).getString(key, null) ?: return 0L
        return if (v.substringBefore(':') == today(now)) v.substringAfter(':').toLongOrNull() ?: 0L else 0L
    }

    private fun putToday(context: Context, key: String, value: Long, now: Long) {
        prefs(context).edit().putString(key, "${today(now)}:$value").apply()
    }

    fun usedMs(context: Context, now: Long = System.currentTimeMillis()): Long = todays(context, "used", now) + pendingMs

    /** [ms] more watched in the Kids profile. */
    fun watched(context: Context, ms: Long, now: Long = System.currentTimeMillis()) {
        pendingMs += ms
        if (pendingMs >= 15_000L) flush(context, now)
    }

    fun flush(context: Context, now: Long = System.currentTimeMillis()) {
        if (pendingMs <= 0L) return
        putToday(context, "used", todays(context, "used", now) + pendingMs, now)
        pendingMs = 0L
    }

    fun state(context: Context, s: Settings, now: Long = System.currentTimeMillis()): KidsTime.State {
        val p = prefs(context)
        return KidsTime.state(
            usedMs = usedMs(context, now),
            dailyMinutes = s.kidsDailyMinutes,
            extraMinutes = todays(context, "extra", now).toInt(),
            unlimitedToday = p.getString("unlimited", null) == today(now),
            minute = minuteOfDay(now),
            bedtime = s.kidsBedtime,
            wake = s.kidsWakeUp,
            bedtimeLifted = now < p.getLong("bedtimeLiftedUntil", 0L),
        )
    }

    /** Kids watching now, and something stops them: show why. */
    fun stop(block: KidsTime.Block) {
        _blocked.value = block
    }

    fun dismiss() {
        _blocked.value = null
    }

    /** A grown-up added [minutes] for today. */
    fun addTime(context: Context, minutes: Int, now: Long = System.currentTimeMillis()) {
        putToday(context, "extra", todays(context, "extra", now) + minutes, now)
        _blocked.value = null
    }

    fun unlimitedToday(context: Context, now: Long = System.currentTimeMillis()) {
        prefs(context).edit().putString("unlimited", today(now)).apply()
        _blocked.value = null
    }

    /** Bedtime off until the next wake-up time. */
    fun liftBedtime(context: Context, s: Settings, now: Long = System.currentTimeMillis()) {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, s.kidsWakeUp / 60)
        c.set(Calendar.MINUTE, s.kidsWakeUp % 60)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        prefs(context).edit().putLong("bedtimeLiftedUntil", c.timeInMillis).apply()
        _blocked.value = null
    }

    /** Today's count back to nothing (Settings); linked devices do the same. */
    fun resetToday(context: Context, now: Long = System.currentTimeMillis()) {
        pendingMs = 0L
        prefs(context).edit().remove("used").remove("extra").remove("unlimited").remove("bedtimeLiftedUntil")
            .putLong("resetAt", now).apply()
        _blocked.value = null
    }

    /** Today here, for linked devices. */
    fun share(context: Context, now: Long = System.currentTimeMillis()): KidsShare {
        flush(context, now)
        val p = prefs(context)
        return KidsShare(
            day = today(now),
            usedMs = todays(context, "used", now),
            extraMinutes = todays(context, "extra", now).toInt(),
            unlimited = p.getString("unlimited", null) == today(now),
            bedtimeLiftedUntil = p.getLong("bedtimeLiftedUntil", 0L),
            resetAt = p.getLong("resetAt", 0L),
        )
    }

    /**
     * Today on a linked device: the most watched and the most extra time count here too (usually
     * the kids watch on one device after another), and a reset there resets here.
     */
    fun takeShare(context: Context, k: KidsShare, settings: Settings, now: Long = System.currentTimeMillis()) {
        val p = prefs(context)
        if (k.resetAt > p.getLong("resetAt", 0L)) {
            pendingMs = 0L
            p.edit().remove("used").remove("extra").remove("unlimited").remove("bedtimeLiftedUntil")
                .putLong("resetAt", k.resetAt).apply()
        }
        val day = today(now)
        if (k.day == day) {
            flush(context, now)
            val e = p.edit()
            if (k.usedMs > todays(context, "used", now)) e.putString("used", "$day:${k.usedMs}")
            if (k.extraMinutes > todays(context, "extra", now)) e.putString("extra", "$day:${k.extraMinutes}")
            if (k.unlimited) e.putString("unlimited", day)
            if (k.bedtimeLiftedUntil > p.getLong("bedtimeLiftedUntil", 0L)) e.putLong("bedtimeLiftedUntil", k.bedtimeLiftedUntil)
            e.apply()
        }
        // More time given on the other device: the "time's up" page here goes.
        if (_blocked.value != null && state(context, settings, now).block == null) _blocked.value = null
    }
}
