package app.falak.player.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class PrayerTimesTest {
    private fun day(zone: String, y: Int, m: Int, d: Int) = Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
        clear()
        set(y, m - 1, d, 12, 0)
    }

    private fun near(name: String, got: Int, hh: Int, mm: Int) {
        val want = hh * 60 + mm
        assertTrue("$name: got ${got / 60}:${got % 60}, want $hh:$mm", kotlin.math.abs(got - want) <= 2)
    }

    @Test
    fun dubaiMatchesAPublishedTimetable() {
        // 23 October 2026, Karachi angles (18°/18°), as published for Dubai: 05:04 12:04 15:19 17:45 19:02.
        val t = PrayerTimes.forDay(day("Asia/Dubai", 2026, 10, 23), 25.3, 55.3, PrayerTimes.Method.KARACHI, hanafi = false)
        near("Fajr", t[0].minute, 5, 4)
        near("Dhuhr", t[1].minute, 12, 4)
        near("Asr", t[2].minute, 15, 19)
        near("Maghrib", t[3].minute, 17, 45)
        near("Isha", t[4].minute, 19, 2)
    }

    @Test
    fun ummAlQuraIshaIsNinetyMinutesAfterMaghrib() {
        val t = PrayerTimes.forDay(day("Asia/Riyadh", 2026, 10, 23), 24.63, 46.72, PrayerTimes.Method.UMM_AL_QURA, hanafi = false)
        assertTrue(t[4].minute - t[3].minute == 90)
    }

    @Test
    fun hanafiAsrIsLater() {
        val c = day("Asia/Karachi", 2026, 10, 23)
        val std = PrayerTimes.forDay(c, 24.86, 67.01, PrayerTimes.Method.KARACHI, hanafi = false)[2].minute
        val han = PrayerTimes.forDay(c, 24.86, 67.01, PrayerTimes.Method.KARACHI, hanafi = true)[2].minute
        assertTrue(han > std + 20)
    }
}
