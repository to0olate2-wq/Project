package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** The provider report card: averages, slow evenings, channels that didn't play, the verdict. */
class ServiceReportTest {
    private val zone = TimeZone.getTimeZone("Asia/Dubai")

    private fun at(day: Int, hour: Int, minute: Int = 0): Long = Calendar.getInstance(zone).apply {
        clear()
        set(2026, Calendar.OCTOBER, day, hour, minute)
    }.timeInMillis

    @Test
    fun nothingYet() {
        val s = ServiceReport.summary(ServiceLog(), at(25, 12), zone)
        assertNull(s.verdict)
        assertEquals("Nothing watched this week yet: it fills in as you watch", ServiceReport.line(s))
    }

    @Test
    fun goodWeekWithASlowEvening() {
        var log = ServiceLog()
        // Five days: an hour each afternoon with hardly any buffering, and 9–11 PM that keeps stopping.
        for (d in 20..24) {
            log = ServiceReport.add(log, at(d, 15, 10), zone, watchedMs = 60 * 60_000L, stalls = if (d == 22) 1 else 0)
            repeat(4) { log = ServiceReport.add(log, at(d, 15, 20), zone, startMs = 1_200L) }
            log = ServiceReport.add(log, at(d, 21, 10), zone, watchedMs = 60 * 60_000L, stalls = 5)
            log = ServiceReport.add(log, at(d, 22, 10), zone, watchedMs = 60 * 60_000L, stalls = 6)
        }
        log = ServiceReport.add(log, at(23, 21, 30), zone, failed = "Sports 4")
        log = ServiceReport.add(log, at(24, 21, 30), zone, failed = "Sports 4")
        log = ServiceReport.add(log, at(24, 20, 0), zone, failed = "News 2")
        // Older than a week: not counted.
        log = ServiceReport.add(log, at(10, 21, 0), zone, watchedMs = 10 * 60 * 60_000L, stalls = 500, failed = "Old one")

        val s = ServiceReport.summary(log, at(25, 12), zone)
        assertEquals(15 * 60 * 60_000L, s.watchedMs)
        assertEquals(20, s.starts)
        assertEquals(1_200L, s.averageStartMs)
        assertEquals(56.0 / 15, s.stallsPerHour!!, 0.001)
        assertEquals(listOf(21..22), s.slowHours)
        assertEquals(listOf("Sports 4" to 2, "News 2" to 1), s.failedChannels)
        assertTrue(s.slowFactor!! > 20)
        // 3.7 stops an hour, 3 failures in 23 tries: fair.
        assertEquals(ServiceReport.Verdict.FAIR, s.verdict)
        assertEquals("9 PM–11 PM", ServiceReport.hoursLabel(21..22) { h -> "${if (h % 12 == 0) 12 else h % 12} ${if (h < 12) "AM" else "PM"}" })
        assertEquals("Fair · channels start in 1.2 s · 3.7 buffering stops an hour · 2 channels didn't play", ServiceReport.line(s))

        // Two weeks on, only the last two weeks are kept.
        val pruned = ServiceReport.prune(log, at(25, 12), zone)
        assertTrue(pruned.hours.keys.none { it.startsWith("20261010") })
        assertTrue("Old one" !in pruned.failed)
    }

    @Test
    fun poorWhenChannelsKeepFailing() {
        var log = ServiceLog()
        repeat(12) { log = ServiceReport.add(log, at(24, 20), zone, startMs = 2_000L) }
        repeat(4) { log = ServiceReport.add(log, at(24, 20), zone, failed = "Channel $it") }
        val s = ServiceReport.summary(log, at(25, 12), zone)
        assertEquals(ServiceReport.Verdict.POOR, s.verdict)
    }
}
