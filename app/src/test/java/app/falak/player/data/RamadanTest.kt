package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Calendar
import java.util.TimeZone

/** Ramadan mode: the Umm al-Qura dates, moved dates, and the day's phase (Iftar, Suhoor, Eid). */
@RunWith(RobolectricTestRunner::class)
class RamadanTest {
    private val zone = TimeZone.getTimeZone("Asia/Dubai")

    private fun at(ymd: Int, hour: Int, minute: Int = 0): Long = Calendar.getInstance(zone).apply {
        clear()
        set(ymd / 10_000, ymd / 100 % 100 - 1, ymd % 100, hour, minute)
    }.timeInMillis

    /** Fajr 5:20, Maghrib 18:15 every day (enough for the phases). */
    private val prayers: (Int) -> List<PrayerTimes.Prayer> = {
        listOf(
            PrayerTimes.Prayer("Fajr", 5 * 60 + 20), PrayerTimes.Prayer("Dhuhr", 12 * 60 + 30),
            PrayerTimes.Prayer("Asr", 15 * 60 + 50), PrayerTimes.Prayer("Maghrib", 18 * 60 + 15),
            PrayerTimes.Prayer("Isha", 19 * 60 + 35),
        )
    }

    @Test
    fun ummAlQuraDates() {
        // 1 Ramadan 1448 = 8 February 2027; 1 Shawwal = 9 March 2027.
        assertEquals(Ramadan.Span(1448, 20270208, 20270308), RamadanCalendar.span(1448, zone))
        assertEquals(Ramadan.Span(1447, 20260218, 20260319), RamadanCalendar.span(1447, zone))
        // In October 2026 the next one is 1448's; on Eid day 1447's is still the current one.
        assertEquals(20270208, RamadanCalendar.current(at(20261025, 12), emptyMap(), zone).first)
        assertEquals(1447, RamadanCalendar.current(at(20260320, 12), emptyMap(), zone).hijriYear)
        assertEquals(1448, RamadanCalendar.current(at(20260321, 12), emptyMap(), zone).hijriYear)
    }

    @Test
    fun datesMovedByHand() {
        val moved = mapOf("1448" to Ramadan.encode(20270207, 20270308))
        val s = RamadanCalendar.current(at(20261025, 12), moved, zone)
        assertEquals(Ramadan.Span(1448, 20270207, 20270308, moved = true), s)
        // Another year's moves don't apply; nonsense is ignored.
        assertEquals(20270208, RamadanCalendar.current(at(20261025, 12), mapOf("1447" to Ramadan.encode(20260217, 20260319)), zone).first)
        assertEquals(20270208, Ramadan.withMoves(RamadanCalendar.span(1448, zone), mapOf("1448" to "junk")).first)
    }

    @Test
    fun phasesThroughTheDay() {
        val span = Ramadan.Span(1448, 20270208, 20270308)
        fun phase(ymd: Int, h: Int, m: Int = 0) = Ramadan.phase(span, at(ymd, h, m), zone, prayers)

        assertNull(phase(20270201, 12))
        assertEquals(Ramadan.Phase.Soon(3, 20270208), phase(20270205, 12))
        assertEquals(Ramadan.Phase.Soon(1, 20270208), phase(20270207, 12))
        // The evening before the first fast: Suhoor until Imsak (Fajr − 10 min).
        assertEquals(Ramadan.Phase.Night(1, at(20270208, 5, 10)), phase(20270207, 20))
        assertEquals(Ramadan.Phase.Night(1, at(20270208, 5, 10)), phase(20270208, 3))
        assertEquals(Ramadan.Phase.Fasting(1, at(20270208, 18, 15)), phase(20270208, 5, 15))
        assertEquals(Ramadan.Phase.Fasting(12, at(20270219, 18, 15)), phase(20270219, 14))
        assertEquals(Ramadan.Phase.Night(13, at(20270220, 5, 10)), phase(20270219, 21))
        // The last day's Maghrib starts Eid; Eid day is Eid; after that, nothing.
        assertEquals(Ramadan.Phase.Fasting(29, at(20270308, 18, 15)), phase(20270308, 12))
        assertEquals(Ramadan.Phase.Eid, phase(20270308, 19))
        assertEquals(Ramadan.Phase.Eid, phase(20270309, 10))
        assertNull(phase(20270310, 10))
    }

    @Test
    fun countdownAndDays() {
        assertEquals("2 h 13 min", Ramadan.countdown(0L, (2 * 60 + 13) * 60_000L))
        assertEquals("45 min", Ramadan.countdown(0L, 45 * 60_000L - 30_000L))
        assertEquals("1 h", Ramadan.countdown(0L, 60 * 60_000L))
        assertEquals(20270301, Ramadan.plusDays(20270228, 1))
        assertEquals(29L, Ramadan.epochDay(20270309) - Ramadan.epochDay(20270208))
    }

    @Test
    fun ramadanSeriesShelf() {
        val list = listOf(
            Channel(id = "s1", name = "Old show", group = "رمضان 2026", url = "", kind = Kind.SERIES, seriesId = "1", added = 10),
            Channel(id = "s2", name = "New show", group = "مسلسلات رمضان 2027", url = "", kind = Kind.SERIES, seriesId = "2", added = 30),
            Channel(id = "s3", name = "Newer show", group = "Ramadan 2027", url = "", kind = Kind.SERIES, seriesId = "3", added = 40),
            Channel(id = "s4", name = "Drama", group = "Drama", url = "", kind = Kind.SERIES, seriesId = "4", added = 50),
        )
        val index = LibraryIndex.build(list)
        val span = Ramadan.Span(1448, 20270208, 20270308)
        val (items, group) = Ramadan.titles(index, span, emptySet())
        // This year's groups only, newest first.
        assertEquals(listOf("s3", "s2"), items.map { it.id })
        assertTrue(group == "مسلسلات رمضان 2027" || group == "Ramadan 2027")
        // Hidden groups stay out.
        assertEquals(listOf("s3"), Ramadan.titles(index, span, setOf("مسلسلات رمضان 2027")).first.map { it.id })
    }
}
