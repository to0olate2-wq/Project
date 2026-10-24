package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FootballTest {
    private val now = 1_800_000_000_000L
    private val h = 3_600_000L

    @Test
    fun matchTitlesInEnglishAndArabic() {
        assertEquals("Arsenal" to "Chelsea", Matches.teams("Arsenal v Chelsea", sports = false))
        assertEquals("Arsenal" to "Chelsea", Matches.teams("Premier League: Arsenal vs. Chelsea", sports = false))
        assertEquals("Real Madrid" to "Barcelona", Matches.teams("LIVE: Real Madrid VS Barcelona (Live)", sports = false))
        assertEquals("الهلال" to "النصر", Matches.teams("دوري روشن: الهلال × النصر", sports = false))
        assertEquals("الهلال" to "النصر", Matches.teams("الهلال ضد النصر", sports = false))
        // "A - B" only on sports channels.
        assertEquals("Liverpool" to "Everton", Matches.teams("Liverpool - Everton", sports = true))
        assertNull(Matches.teams("News - Evening edition", sports = false))
        assertNull(Matches.teams("Evening News", sports = true))
    }

    @Test
    fun teamsByNameShortNameOrCommonName() {
        val city = FootballTeam("Manchester City FC", "Man City", "MCI")
        val united = FootballTeam("Manchester United FC", "Man United", "MUN")
        val barca = FootballTeam("FC Barcelona", "Barça", "FCB")
        val arsenal = FootballTeam("Arsenal FC", "Arsenal", "ARS")
        assertTrue(Matches.sameTeam("Man City", city))
        assertTrue(Matches.sameTeam("Manchester City", city))
        assertTrue(Matches.sameTeam("مانشستر سيتي", city))
        assertTrue(Matches.sameTeam("Man Utd", united))
        assertFalse(Matches.sameTeam("Man Utd", city))
        assertTrue(Matches.sameTeam("Barcelona", barca))
        assertTrue(Matches.sameTeam("برشلونة", barca))
        assertTrue(Matches.sameTeam("أرسنال", arsenal))
        assertTrue(Matches.sameTeam("ARS", arsenal))
        assertFalse(Matches.sameTeam("Chelsea", arsenal))
    }

    private fun ch(id: String, name: String, group: String) = Channel(id = id, name = name, group = group, url = "http://x/$id", tvgId = id)

    @Test
    fun matchesInTheGuideWithScores() {
        val bein = ch("b1", "beIN Sports 1", "Sports")
        val bein2 = ch("b2", "beIN Sports 2", "Sports")
        val films = ch("f", "Movie Box", "Films")
        val epg = mapOf(
            "b1" to listOf(
                Program(now - h, now + h, "Premier League: Arsenal v Chelsea", ""),
                Program(now + 3 * h, now + 5 * h, "Liverpool - Everton", ""),
                Program(now + 5 * h, now + 6 * h, "Sports News", ""),
            ),
            "b2" to listOf(Program(now - h, now + h, "Arsenal vs Chelsea", "")),
            "f" to listOf(Program(now + h, now + 3 * h, "Kramer vs. Kramer", "")),
        )
        val feed = listOf(
            FootballMatch(id = 1, competition = "Premier League", time = now - h + 15 * 60_000L, status = "IN_PLAY", minute = 50,
                home = FootballTeam("Arsenal FC", "Arsenal", "ARS"), away = FootballTeam("Chelsea FC", "Chelsea", "CHE"), homeScore = 2, awayScore = 1),
        )
        val found = Matches.inGuide(listOf(bein, bein2, films), epg, now, now + 24 * h, favorites = setOf("b2"), feed = feed)
        assertEquals(listOf("Arsenal" to "Chelsea", "Liverpool" to "Everton"), found.map { it.home to it.away })
        val first = found.first()
        assertEquals("b2", first.channel.id) // the favorite channel
        assertEquals(1, first.alsoOn)
        assertEquals("2 – 1", first.score?.score)
        assertEquals("50'", first.score?.state)
        assertNull(found[1].score)
    }

    @Test
    fun matchStates() {
        val m = FootballMatch(status = "PAUSED", homeScore = 0, awayScore = 0)
        assertEquals("HT", m.state)
        assertEquals("0 – 0", m.score)
        assertNull(FootballMatch(status = "TIMED").score)
        assertEquals("FT", FootballMatch(status = "FINISHED", homeScore = 3, awayScore = 2).state)
        assertEquals("in 25 min", Matches.untilLabel(now + 25 * 60_000L, now))
        assertEquals("in 2 h 10 min", Matches.untilLabel(now + 130 * 60_000L, now))
    }
}
