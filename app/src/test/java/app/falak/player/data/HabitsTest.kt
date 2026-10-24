package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Calendar
import java.util.TimeZone

class HabitsTest {
    private val zone = TimeZone.getTimeZone("UTC")
    private val day = 86_400_000L

    /** Wednesday 2026-10-21 at [hour]:[minute] UTC. */
    private fun at(hour: Int, minute: Int = 0): Long {
        val c = Calendar.getInstance(zone)
        c.clear()
        c.set(2026, Calendar.OCTOBER, 21, hour, minute)
        return c.timeInMillis
    }

    @Test
    fun usualChannelsForThisTimeOfDay() {
        val now = at(20, 10)
        val seen = listOf(
            Habits.Seen("p", "news", at(20) - 1 * day),
            Habits.Seen("p", "news", at(19, 45) - 2 * day),
            Habits.Seen("p", "news", at(20, 30) - 7 * day),
            Habits.Seen("p", "film", at(20) - 3 * day),
            Habits.Seen("p", "film", at(20, 15) - 4 * day),
            // Mornings don't count in the evening.
            Habits.Seen("p", "morning", at(8) - 1 * day),
            Habits.Seen("p", "morning", at(8) - 2 * day),
        )
        assertEquals(listOf("news", "film"), Habits.forNow(seen, "p", now, zone))
        assertEquals(listOf("morning"), Habits.forNow(seen, "p", at(8, 20), zone))
    }

    @Test
    fun oneDayIsNotAHabit() {
        val now = at(20)
        val seen = (1..5).map { Habits.Seen("p", "once", at(20) - day + it * 60_000L) }
        assertTrue(Habits.forNow(seen, "p", now, zone).isEmpty())
    }

    @Test
    fun eachProfileAndPlaylistHasItsOwn() {
        val now = at(20)
        val seen = listOf(Habits.Seen("kids", "cartoons", at(20) - day), Habits.Seen("kids", "cartoons", at(20) - 2 * day))
        assertTrue(Habits.forNow(seen, "p", now, zone).isEmpty())
        assertEquals(listOf("cartoons"), Habits.forNow(seen, "kids", now, zone))
    }

    @Test
    fun aroundMidnight() {
        // Tuesday 23:55 and Monday 23:50, seen from Wednesday 00:20.
        val seen = listOf(Habits.Seen("p", "late", at(23, 55) - day), Habits.Seen("p", "late", at(23, 50) - 2 * day))
        assertEquals(listOf("late"), Habits.forNow(seen, "p", at(0, 20), zone))
    }

    @Test
    fun recordKeepsTheLatestAndDropsOldOnes() {
        val dir = Files.createTempDirectory("habits").toFile()
        val now = at(20)
        Habits.record(dir, "p", "old", now - 90 * day)
        repeat(605) { Habits.record(dir, "p", "c$it", now + it) }
        val read = Habits.read(dir)
        assertEquals(600, read.size)
        assertEquals("c5", read.first().id)
        assertEquals("c604", read.last().id)
        assertTrue(read.none { it.id == "old" })
        dir.deleteRecursively()
    }
}
