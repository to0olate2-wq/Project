package app.falak.player.data

import android.icu.util.IslamicCalendar
import android.icu.util.ULocale
import java.util.TimeZone

/** Ramadan's dates from the Umm al-Qura calendar (Android's own, no internet). */
object RamadanCalendar {
    private fun calendar(zone: TimeZone): IslamicCalendar =
        IslamicCalendar(android.icu.util.TimeZone.getTimeZone(zone.id), ULocale.ROOT).apply {
            calculationType = IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
        }

    /** Ramadan of Hijri [year]: its first day, and the day before 1 Shawwal. */
    fun span(year: Int, zone: TimeZone = TimeZone.getDefault()): Ramadan.Span {
        val cal = calendar(zone)
        cal.clear()
        cal.set(year, IslamicCalendar.RAMADAN, 1)
        val first = Ramadan.ymd(cal.timeInMillis + 12 * 3_600_000L, zone)
        cal.clear()
        cal.set(year, IslamicCalendar.SHAWWAL, 1)
        val eid = Ramadan.ymd(cal.timeInMillis + 12 * 3_600_000L, zone)
        return Ramadan.Span(year, first, Ramadan.plusDays(eid, -1))
    }

    /** The Hijri year of [now]. */
    fun hijriYear(now: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        val cal = calendar(zone)
        cal.timeInMillis = now
        return cal.get(android.icu.util.Calendar.YEAR)
    }

    /**
     * The Ramadan that [now] is in, or the next one; just after it ends, still that one (for Eid).
     * With the dates moved in Settings for that year ([moved]).
     */
    fun current(now: Long, moved: Map<String, String>, zone: TimeZone = TimeZone.getDefault()): Ramadan.Span {
        val today = Ramadan.ymd(now, zone)
        val year = hijriYear(now, zone)
        val thisYear = Ramadan.withMoves(span(year, zone), moved)
        // Eid (the day after the last) still belongs to this one; after that, next year's.
        return if (today <= Ramadan.plusDays(thisYear.last, 1)) thisYear else Ramadan.withMoves(span(year + 1, zone), moved)
    }
}
