package app.falak.player.ui

import app.falak.player.data.Channel
import app.falak.player.data.Program
import org.junit.Assert.assertEquals
import org.junit.Test

class GuideSearchTest {
    private val now = 1_000_000_000_000L
    private val h = 3_600_000L

    private fun ch(name: String, days: Int = 0) =
        Channel(id = name, name = name, group = "G", url = "http://x/$name", tvgId = name.lowercase(), catchupDays = days)

    @Test
    fun onNowThenComingUpThenReplays() {
        val a = ch("Alpha", days = 2)
        val b = ch("Beta")
        val epg = mapOf(
            "alpha" to listOf(
                Program(now - 3 * h, now - 2 * h, "The Big Match", ""),
                Program(now - h / 2, now + h / 2, "Big Match Live", ""),
                Program(now + 2 * h, now + 3 * h, "The Big Match", ""),
                Program(now + 3 * 24 * h, now + 3 * 24 * h + h, "Big Match far ahead", ""),
            ),
            "beta" to listOf(
                // Over, and Beta has no replay: not offered.
                Program(now - 3 * h, now - 2 * h, "The Big Match", ""),
                Program(now + h, now + 2 * h, "Big Match Preview", ""),
                Program(now + h, now + 2 * h, "News", ""),
            ),
        )
        val hits = searchGuide(listOf(a, b), epg, "big match", now)
        assertEquals(listOf("Big Match Live", "Big Match Preview", "The Big Match", "The Big Match"), hits.map { it.second.title })
        assertEquals(listOf("Alpha", "Beta", "Alpha", "Alpha"), hits.map { it.first.name })
    }
}
