package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NoticeTest {
    @Test
    fun summaryNamesWhatWasAdded() {
        assertEquals("27 new films · 2 new series · 3 new channels", Notice(1, "p", "Strong", live = 3, films = 27, series = 2).summary())
        assertEquals("1 new film", Notice(1, "p", "Strong", live = 0, films = 1, series = 0).summary())
        assertEquals("1 new channel", Notice(1, "p", "Strong", live = 1, films = 0, series = 0).summary())
    }
}
