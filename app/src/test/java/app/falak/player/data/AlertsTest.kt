package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertsTest {
    private val now = 1_800_000_000_000L
    private val h = 3_600_000L

    private fun ch(id: String) = Channel(id = id, name = id.uppercase(), group = "Sports", url = "http://x/$id", tvgId = id)
    private val a = ch("a")
    private val b = ch("b")
    private val c = ch("c")

    private val epg = mapOf(
        "a" to listOf(
            Program(now + h, now + 3 * h, "Real Madrid v Barcelona", ""),
            Program(now + 5 * h, now + 6 * h, "Madrid Open", ""),
            Program(now + 30 * h, now + 31 * h, "Real Madrid v Sevilla", ""), // past the next day
        ),
        "b" to listOf(
            Program(now + h, now + 3 * h, "Real Madrid v Barcelona", ""),
            Program(now + 2 * h, now + 3 * h, "مباراة الهلال والنصر", ""),
        ),
        "c" to listOf(
            Program(now + h, now + 3 * h, "REAL MADRID V BARCELONA", ""),
            Program(now - 10 * 60_000L, now + h, "News at Ten", ""),
        ),
    )

    @Test
    fun alertWordsFindProgrammesOnceWithTheOtherChannelsCounted() {
        val list = Alerts.upcoming("p", emptyList(), listOf("real madrid", "الهلال"), listOf(a, b, c), epg, now, favorites = setOf("b"))
        assertEquals(listOf("Real Madrid v Barcelona", "مباراة الهلال والنصر"), list.map { it.title })
        val match = list.first()
        assertEquals("b", match.channelId) // the favorite channel is offered
        assertEquals(2, match.alsoOn)
        assertEquals("real madrid", match.word)
        assertEquals("الهلال", list[1].word)
    }

    @Test
    fun remindersSetByHandComeFirstAndAreNotRepeatedByWords() {
        val r = Reminder("p", "a", "A", "Real Madrid v Barcelona", now + h, now + 3 * h)
        val news = Reminder("p", "c", "C", "News at Ten", now - 10 * 60_000L, now + h)
        val old = Reminder("p", "c", "C", "Old", now - 2 * h, now - h)
        val other = Reminder("q", "a", "A", "Other playlist", now + h, now + 2 * h)
        val hidden = Reminder("p", "zz", "ZZ", "Hidden channel", now + h, now + 2 * h)
        val list = Alerts.upcoming("p", listOf(r, news, old, other, hidden), listOf("real madrid"), listOf(a, b, c), epg, now)
        // Started 10 minutes ago: past the 5 minutes it is offered for.
        assertEquals(listOf("Real Madrid v Barcelona"), list.map { it.title })
        assertEquals(null, list.single().word)
        assertEquals("a", list.single().channelId)
    }

    @Test
    fun aReminderJustStartedIsStillOffered() {
        val r = Reminder("p", "c", "C", "News at Ten", now - 3 * 60_000L, now + h)
        assertEquals(1, Alerts.upcoming("p", listOf(r), emptyList(), listOf(c), epg, now).size)
    }

    @Test
    fun wordsMustMatchWholeWordStarts() {
        assertTrue(Alerts.upcoming("p", emptyList(), listOf("drid"), listOf(a, b, c), epg, now).isEmpty())
        assertEquals(1, Alerts.upcoming("p", emptyList(), listOf("madrid open"), listOf(a, b, c), epg, now).size)
        assertTrue(Alerts.upcoming("p", emptyList(), listOf("  "), listOf(a, b, c), epg, now).isEmpty())
    }

    @Test
    fun whenLabels() {
        assertEquals("starts in 2 min", Alerts.whenLabel(now + 2 * 60_000L, now))
        assertEquals("starts now", Alerts.whenLabel(now + 10_000L, now))
        assertEquals("started 3 min ago", Alerts.whenLabel(now - 3 * 60_000L, now))
    }
}
