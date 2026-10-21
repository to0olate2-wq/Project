package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Guide sources checked against each other, and sunrise/sunset for the automatic theme. */
class GuideCheckTest {
    private val now = 1_790_000_000_000L
    private val titles = listOf("Morning Briefing", "Coastal Kitchen", "World Cup Highlights", "Nature Hour", "Late Debate", "Night Movie")

    private fun guide(shiftMin: Int) = titles.mapIndexed { i, t ->
        val start = now - 2 * 3_600_000L + i * 3_600_000L + shiftMin * 60_000L
        Program(start, start + 3_600_000L, t, "")
    }

    @Test
    fun sameTimesAgree() {
        assertEquals(0, GuideCheck.channelOffset(guide(0), guide(0), now))
    }

    @Test
    fun sourceAnHourEarlyNeedsPlusSixty() {
        // The other source lists every show an hour before the reference: add 60 minutes to it.
        assertEquals(60, GuideCheck.channelOffset(guide(0), guide(-60), now))
        assertEquals(-30, GuideCheck.channelOffset(guide(0), guide(30), now))
    }

    @Test
    fun differentProgrammesSayNothing() {
        val other = guide(0).map { it.copy(title = it.title + " (other channel)") }
        assertNull(GuideCheck.channelOffset(guide(0), other, now))
    }

    @Test
    fun tallySuggestsAFixOnlyWhenMostChannelsAgreeOnIt() {
        val t = GuideCheck.Tally()
        repeat(5) { t.add(60) }
        t.add(0)
        t.add(null)
        assertEquals(6, t.compared)
        assertEquals(1, t.agree)
        assertEquals(60, t.suggestedShift)
        assertEquals(5, t.shiftVotes)

        val mostlyFine = GuideCheck.Tally()
        repeat(8) { mostlyFine.add(0) }
        repeat(2) { mostlyFine.add(60) }
        assertEquals(0, mostlyFine.suggestedShift)
    }

    @Test
    fun sunriseAndSunsetInDubai() {
        val tz = TimeZone.getTimeZone("Asia/Dubai")
        val cal = Calendar.getInstance(tz).apply { set(2026, Calendar.OCTOBER, 21, 12, 0, 0) }
        val day = SunTimes.day(SunTimes.Place("Asia/Dubai", "Dubai", 25.3, 55.3, guessed = false), cal)
        // Published times for that day: sunrise 06:16, sunset 17:45.
        assertTrue("sunrise ${day.sunrise}", day.sunrise!! in (6 * 60 + 10)..(6 * 60 + 24))
        assertTrue("sunset ${day.sunset}", day.sunset!! in (17 * 60 + 39)..(17 * 60 + 52))
    }

    @Test
    fun polarNightHasNoSunrise() {
        val tz = TimeZone.getTimeZone("Arctic/Longyearbyen")
        val cal = Calendar.getInstance(tz).apply { set(2026, Calendar.DECEMBER, 21, 12, 0, 0) }
        val day = SunTimes.day(SunTimes.Place("Arctic/Longyearbyen", "Longyearbyen", 78.0, 16.0, guessed = false), cal)
        assertNull(day.sunrise)
    }
}
