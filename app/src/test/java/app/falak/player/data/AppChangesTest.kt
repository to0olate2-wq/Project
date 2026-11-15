package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppChangesTest {
    @Test
    fun versionsCompareAsNumbers() {
        assertTrue(AppChanges.newer("8.10", "8.9"))
        assertTrue(AppChanges.newer("9.0", "8.27"))
        assertTrue(AppChanges.newer("8.27", ""))
        assertFalse(AppChanges.newer("8.27", "8.27"))
        assertFalse(AppChanges.newer("8.26", "8.27"))
    }

    @Test
    fun onlyWhatIsNewSinceTheLastOpened() {
        assertEquals(3, AppChanges.since(AppChanges.FIRST_NOTED, "8.27").size)
        assertTrue(AppChanges.since("8.27", "8.27").isEmpty())
        // (An entry for a version not out yet isn't shown.)
        assertTrue(AppChanges.since("8.26", "8.26").isEmpty())
        // Every entry's version has its place (no typing slips like "8.270").
        assertTrue(AppChanges.BIG.all { Regex("\\d+\\.\\d{1,2}").matches(it.version) })
    }
}
