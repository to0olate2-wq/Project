package app.falak.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMatchTest {
    @Test
    fun languagesMatchWhateverCodeTheStreamUses() {
        assertTrue(TrackMatch.sameLanguage("ar", "ara"))
        assertTrue(TrackMatch.sameLanguage("arb", "ar"))
        assertTrue(TrackMatch.sameLanguage("en-US", "eng"))
        assertTrue(TrackMatch.sameLanguage("ger", "de"))
        assertFalse(TrackMatch.sameLanguage("en", "fr"))
        assertFalse(TrackMatch.sameLanguage(null, "en"))
    }

    @Test
    fun rememberedValues() {
        assertEquals("lang:ar", TrackMatch.valueFor("ar", "Arabic"))
        assertEquals("name:Commentary", TrackMatch.valueFor(null, " Commentary "))
        assertEquals("name:Track 2", TrackMatch.valueFor("und", "Track 2"))
    }

    @Test
    fun picksByLanguageThenByName() {
        val exo = listOf<Pair<String?, String>>("en" to "English", "ara" to "Arabic", null to "Commentary")
        assertEquals(1, TrackMatch.pick("lang:ar", exo))
        assertEquals(2, TrackMatch.pick("name:commentary", exo))
        assertEquals(-1, TrackMatch.pick("lang:fr", exo))
        // VLC lists names only.
        val vlc = listOf<Pair<String?, String>>(null to "Track 1 - [English]", null to "Track 2 - [Arabic]")
        assertEquals(1, TrackMatch.pick("lang:ar", vlc))
        assertEquals(0, TrackMatch.pick("lang:eng", vlc))
        assertEquals(-1, TrackMatch.pick("off", vlc))
    }
}
