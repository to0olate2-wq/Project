package app.falak.player.record

import app.falak.player.data.Program
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordRulesTest {
    private val rule = SeriesRule("r1", "pl", "ch1", "MBC 1", "Bab Al-Hara")

    @Test
    fun theSameShowWhateverItsEpisodeName() {
        assertTrue(RecordRules.matches(rule, Program(0, 1, "Bab Al-Hara", "")))
        assertTrue(RecordRules.matches(rule, Program(0, 1, "BAB AL HARA: Episode 12", "")))
        assertTrue(RecordRules.matches(rule, Program(0, 1, "Bab Al-Hara - S02E03", "")))
        assertFalse(RecordRules.matches(rule, Program(0, 1, "Bab Al-Harat", "")))
        assertFalse(RecordRules.matches(rule, Program(0, 1, "News", "")))
    }

    @Test
    fun plansWhatsComingUpOnceEach() {
        val now = 10_000_000L
        val hour = 3_600_000L
        val progs = listOf(
            Program(now - 2 * hour, now - hour, "Bab Al-Hara", ""), // over
            Program(now + hour, now + 2 * hour, "Bab Al-Hara", ""),
            Program(now + 2 * hour, now + 3 * hour, "News", ""),
            Program(now + 30 * hour, now + 31 * hour, "Bab Al-Hara", ""),
            Program(now + 100 * hour, now + 101 * hour, "Bab Al-Hara", ""), // too far ahead
        )
        val made = ArrayList<Program>()
        fun plan(book: RecordBook) = RecordRules.plan(book, "pl", { id -> if (id == "ch1") progs else null }, now, 72 * hour) { r, p ->
            made += p
            Recording(
                id = "x" + p.start, playlistId = "pl", channelId = r.channelId, channelName = r.channelName,
                title = p.title, start = p.start, stop = p.stop, progStart = p.start, progStop = p.stop, url = "u", ruleId = r.id,
            )
        }
        val first = plan(RecordBook(rules = listOf(rule)))
        assertEquals(listOf(now + hour, now + 30 * hour), first.map { it.progStart })
        // Already planned, or cancelled by hand: not again.
        val again = plan(RecordBook(recordings = first.take(1), rules = listOf(rule), skipped = listOf("ch1|${now + 30 * hour}")))
        assertTrue(again.isEmpty())
        // Another playlist's rules don't plan here.
        assertTrue(plan(RecordBook(rules = listOf(rule.copy(playlistId = "other")))).isEmpty())
    }
}
