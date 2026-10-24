package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class AccountTest {
    private val zone = TimeZone.getTimeZone("Asia/Dubai")
    private val day = 86_400_000L
    private val date: (Long) -> String = { "D" }

    /** 2026-10-25 [hour]:00 in Dubai. */
    private fun at(hour: Int): Long {
        val c = Calendar.getInstance(zone)
        c.clear()
        c.set(2026, Calendar.OCTOBER, 25, hour, 0)
        return c.timeInMillis
    }

    private fun fields(vararg pairs: Pair<String, String>): (String) -> String {
        val m = mapOf(*pairs)
        return { m[it].orEmpty() }
    }

    @Test
    fun readsXtreamUserInfo() {
        val now = at(12)
        val a = Accounts.parse(
            fields("auth" to "1", "status" to "Active", "exp_date" to "1800000000", "is_trial" to "0", "active_cons" to "1", "max_connections" to "2"),
            now,
        )
        assertEquals("Active", a.status)
        assertEquals(1_800_000_000_000L, a.expires)
        assertFalse(a.trial)
        assertEquals(1, a.active)
        assertEquals(2, a.max)
        assertEquals(now, a.checkedAt)
        assertFalse(a.allInUse)
        // No end date ("null", "", "0"), milliseconds, a refused login.
        assertNull(Accounts.parse(fields("auth" to "1", "exp_date" to "null"), now).expires)
        assertNull(Accounts.parse(fields("auth" to "1", "exp_date" to "0"), now).expires)
        assertEquals(1_800_000_000_000L, Accounts.parse(fields("exp_date" to "1800000000000"), now).expires)
        assertEquals("Login failed", Accounts.parse(fields("auth" to "0"), now).status)
        assertTrue(Accounts.parse(fields("active_cons" to "2", "max_connections" to "2"), now).allInUse)
    }

    @Test
    fun calendarDaysInTheLocalZone() {
        val now = at(22)
        assertEquals(0, Accounts.daysUntil(at(23), now, zone))
        // 3 hours later, but after midnight: tomorrow.
        assertEquals(1, Accounts.daysUntil(now + 3 * 3_600_000L, now, zone))
        assertEquals("ends tomorrow", Accounts.endsLabel(now + 3 * 3_600_000L, now, date, zone))
        assertEquals("ends in 6 days", Accounts.endsLabel(now + 6 * day, now, date, zone))
        assertEquals("ended on D", Accounts.endsLabel(now - 1, now, date, zone))
    }

    @Test
    fun summaryForSettings() {
        val now = at(12)
        assertEquals(
            "Active · ends D (139 days left) · 1 of 2 connections in use",
            Accounts.summary(Account("Active", now + 139 * day, active = 1, max = 2), now, date, zone),
        )
        assertEquals("Trial · ends D (tomorrow) · 0 of 1 connection in use", Accounts.summary(Account("Active", now + day, trial = true, max = 1), now, date, zone))
        assertEquals("Active · no end date", Accounts.summary(Account("Active"), now, date, zone))
        assertEquals("Expired · ended on D", Accounts.summary(Account("Expired", now - day), now, date, zone))
    }

    @Test
    fun remindsOnlyInTheLastWeek() {
        val now = at(12)
        assertNull(Accounts.warning(Account("Active", now + 8 * day), now, date, zone))
        assertNull(Accounts.warning(Account("Active"), now, date, zone))
        assertEquals(
            "Your subscription ends in 7 days (D). Renew it with your provider to keep watching.",
            Accounts.warning(Account("Active", now + 7 * day), now, date, zone),
        )
        assertEquals(
            "Your subscription ended on D. Renew it with your provider to keep watching.",
            Accounts.warning(Account("Active", now - day), now, date, zone),
        )
        assertEquals("Your subscription is banned. Contact your provider to keep watching.", Accounts.warning(Account("Banned"), now, date, zone))
    }

    @Test
    fun explainsARefusedChannel() {
        val now = at(12)
        assertEquals(
            "All 2 connections of your subscription are in use (another device is watching).\nStop it there, or try again in a minute.",
            Accounts.refusal(Account("Active", now + 30 * day, active = 2, max = 2), now, date),
        )
        assertEquals(
            "Your subscription ended on D. Renew it with your provider to keep watching.",
            Accounts.refusal(Account("Active", now - day, active = 0, max = 2), now, date),
        )
        // The account is fine: the stream itself is the problem.
        assertNull(Accounts.refusal(Account("Active", now + 30 * day, active = 1, max = 2), now, date))
        assertNull(Accounts.refusal(Account("Active"), now, date))
    }
}
