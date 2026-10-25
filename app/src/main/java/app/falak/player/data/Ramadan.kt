package app.falak.player.data

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Ramadan mode (Settings → Prayer times; on by itself during Ramadan unless switched off): an
 * Iftar countdown on Home and the ambient screen, a Suhoor reminder before Imsak, and a shelf of
 * the provider's Ramadan series. The dates come from the Umm al-Qura calendar; where the month
 * starts or ends a day apart from it (moon sighting), the first and last day can be moved, for
 * that year only.
 */
object Ramadan {
    /** One Ramadan: its [first] and [last] day (yyyymmdd), Eid the day after. */
    data class Span(val hijriYear: Int, val first: Int, val last: Int, val moved: Boolean = false)

    /** Imsak, when Suhoor ends: this long before Fajr (the usual timetables). */
    const val IMSAK_MINUTES = 10

    sealed interface Phase {
        /** Ramadan begins in [days] days, on [first] (yyyymmdd). */
        data class Soon(val days: Int, val first: Int) : Phase

        /** Fasting day [day]; Iftar at [iftar] (Maghrib, epoch ms). */
        data class Fasting(val day: Int, val iftar: Long) : Phase

        /** Before fasting day [day]: Suhoor until Imsak at [imsak] (epoch ms). */
        data class Night(val day: Int, val imsak: Long) : Phase

        /** Eid al-Fitr: from the last day's Maghrib to the end of Eid day. */
        data object Eid : Phase
    }

    fun ymd(at: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        val c = Calendar.getInstance(zone)
        c.timeInMillis = at
        return c.get(Calendar.YEAR) * 10_000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }

    /** Days from the epoch for a yyyymmdd date (to count between dates). */
    fun epochDay(ymd: Int): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(ymd / 10_000, ymd / 100 % 100 - 1, ymd % 100)
        return c.timeInMillis / 86_400_000L
    }

    fun fromEpochDay(day: Long): Int {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.timeInMillis = day * 86_400_000L
        return c.get(Calendar.YEAR) * 10_000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }

    fun plusDays(ymd: Int, days: Int): Int = fromEpochDay(epochDay(ymd) + days)

    /** Midnight starting [ymd] in [zone]. */
    fun startOf(ymd: Int, zone: TimeZone = TimeZone.getDefault()): Long {
        val c = Calendar.getInstance(zone)
        c.clear()
        c.set(ymd / 10_000, ymd / 100 % 100 - 1, ymd % 100, 0, 0, 0)
        return c.timeInMillis
    }

    /** "2027-02-08|2027-03-08" (a year's moved dates, as Settings keeps them). */
    fun encode(first: Int, last: Int): String = "${iso(first)}|${iso(last)}"

    private fun iso(ymd: Int) = String.format(Locale.US, "%04d-%02d-%02d", ymd / 10_000, ymd / 100 % 100, ymd % 100)

    private fun parseIso(s: String): Int? {
        val p = s.trim().split('-')
        if (p.size != 3) return null
        val y = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val d = p[2].toIntOrNull() ?: return null
        return y * 10_000 + m * 100 + d
    }

    /** [calendar] with the year's moved dates from Settings ([moved]: Hijri year → [encode]d dates), if any. */
    fun withMoves(calendar: Span, moved: Map<String, String>): Span {
        val v = moved[calendar.hijriYear.toString()] ?: return calendar
        val first = parseIso(v.substringBefore('|')) ?: return calendar
        val last = parseIso(v.substringAfter('|', "")) ?: return calendar
        if (epochDay(last) - epochDay(first) !in 25..31) return calendar
        return Span(calendar.hijriYear, first, last, moved = true)
    }

    /**
     * Where [now] stands: Ramadan soon (3 days or less), a fasting day, a night of Suhoor, Eid, or
     * null. [prayers] gives a day's prayer times (Fajr first, Maghrib fourth; see [PrayerTimes]).
     */
    fun phase(span: Span, now: Long, zone: TimeZone, prayers: (Int) -> List<PrayerTimes.Prayer>): Phase? {
        val today = ymd(now, zone)
        fun at(day: Int, minute: Int) = startOf(day, zone) + minute * 60_000L
        fun fajr(day: Int) = prayers(day).getOrNull(0)?.minute
        fun maghrib(day: Int) = prayers(day).getOrNull(3)?.minute
        val eid = plusDays(span.last, 1)
        val dayNo = (epochDay(today) - epochDay(span.first) + 1).toInt()
        return when {
            today < span.first -> {
                val days = (epochDay(span.first) - epochDay(today)).toInt()
                val m = maghrib(today)
                // The evening before the first fast: Suhoor comes next.
                if (days == 1 && m != null && now >= at(today, m)) {
                    fajr(span.first)?.let { f -> Phase.Night(1, at(span.first, f) - IMSAK_MINUTES * 60_000L) } ?: Phase.Soon(days, span.first)
                } else if (days <= 3) {
                    Phase.Soon(days, span.first)
                } else {
                    null
                }
            }
            today <= span.last -> {
                val f = fajr(today) ?: return null
                val m = maghrib(today) ?: return null
                val imsak = at(today, f) - IMSAK_MINUTES * 60_000L
                when {
                    now < imsak -> Phase.Night(dayNo, imsak)
                    now < at(today, m) -> Phase.Fasting(dayNo, at(today, m))
                    today < span.last -> {
                        val next = plusDays(today, 1)
                        val nf = fajr(next) ?: return null
                        Phase.Night(dayNo + 1, at(next, nf) - IMSAK_MINUTES * 60_000L)
                    }
                    else -> Phase.Eid
                }
            }
            today == eid -> Phase.Eid
            else -> null
        }
    }

    /** "2 h 13 min", "45 min" until [at]. */
    fun countdown(now: Long, at: Long): String {
        val min = ((at - now + 59_999L) / 60_000L).coerceAtLeast(0L)
        return when {
            min < 60 -> "$min min"
            min % 60 == 0L -> "${min / 60} h"
            else -> "${min / 60} h ${min % 60} min"
        }
    }

    /**
     * Ramadan series (or films) of the playlist: from groups named for Ramadan ("رمضان 2027",
     * "Ramadan Series"), this year's first when the names say the year; newest first. Also the
     * group to open for all of them.
     */
    fun titles(index: LibraryIndex, span: Span, excluded: Set<String>): Pair<List<Channel>, String?> {
        val years = setOf((span.first / 10_000).toString(), span.hijriYear.toString())
        fun pick(groups: Map<String, List<Channel>>): Pair<List<Channel>, String?> {
            val named = groups.keys.filter { g -> g !in excluded && (g.contains("رمضان") || g.contains("ramadan", ignoreCase = true)) }
            if (named.isEmpty()) return emptyList<Channel>() to null
            val thisYear = named.filter { g -> years.any { g.contains(it) } }
            val chosen = thisYear.ifEmpty { named }
            val items = chosen.flatMap { groups[it].orEmpty() }.sortedByDescending { it.added }.take(40)
            return items to chosen.first()
        }
        val series = pick(index.groupsOf(Kind.SERIES))
        return if (series.first.isNotEmpty()) series else pick(index.groupsOf(Kind.MOVIE))
    }
}
