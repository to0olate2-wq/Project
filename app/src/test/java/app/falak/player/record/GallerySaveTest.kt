package app.falak.player.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/** Save to gallery (8.36): the file's name. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class GallerySaveTest {
    @Test
    fun namesTheFileByProgrammeChannelAndTime() {
        val at = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 17, 21, 0, 0) }.timeInMillis
        val r = Recording(
            id = "x", playlistId = "p", channelId = "c", channelName = "Atlas News", title = "News: 10/11 \"Live\"",
            start = at, stop = at + 3_600_000L, progStart = at, url = "http://x", state = RecState.DONE,
        )
        val name = GallerySave.fileName(r)
        assertEquals("News 10 11 Live · Atlas News · 17 Nov 2026 21.00", name)
        assertFalse(name.any { it in "\\/:*?\"<>|" })
        // Recorded without a programme: the title is the channel's name, said once.
        val plain = GallerySave.fileName(r.copy(title = "Atlas News"))
        assertTrue(plain.startsWith("Atlas News · 17 Nov"))
    }
}
