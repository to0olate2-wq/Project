package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionCheckTest {
    private fun m(mbps: Double, firstMs: Long = 200) = ConnectionCheck.Measure((mbps * 1000 * 2000 / 8).toLong(), 2000, firstMs)
    private val failed = ConnectionCheck.Measure(0, 3000, 3000, "UnknownHost")

    @Test
    fun speedsInPlainWords() {
        assertEquals(40.0, m(40.0).mbps, 0.01)
        assertEquals("40 Mb/s", ConnectionCheck.rate(40.0))
        assertEquals("3.5 Mb/s", ConnectionCheck.rate(3.5))
        assertEquals("enough for 4K", ConnectionCheck.goodFor(40.0))
        assertEquals("enough for HD", ConnectionCheck.goodFor(5.0))
        assertEquals("too slow for smooth video", ConnectionCheck.goodFor(1.0))
        // Too little read to say a speed.
        assertEquals(0.0, ConnectionCheck.Measure(1000, 10, 5).mbps, 0.0)
    }

    @Test
    fun whichPartIsSlow() {
        assertTrue(ConnectionCheck.verdict(failed, failed, null).startsWith("This device can't reach the internet"))
        assertTrue(ConnectionCheck.verdict(m(2.0), m(1.0), m(1.0)).startsWith("Your internet is the slow part"))
        assertTrue(ConnectionCheck.verdict(m(50.0), failed, null).contains("your provider didn't answer"))
        assertTrue(ConnectionCheck.verdict(m(50.0), m(1.0), m(2.0)).contains("provider is sending slowly"))
        assertTrue(ConnectionCheck.verdict(m(50.0), m(1.0, firstMs = 5000), null).contains("provider is sending slowly"))
        assertTrue(ConnectionCheck.verdict(m(50.0), m(1.0), failed).contains("the channel didn't open"))
        assertTrue(ConnectionCheck.verdict(m(50.0), m(1.0), m(12.0)).contains("both look fine"))
    }
}
