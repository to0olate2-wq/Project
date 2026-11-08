package app.falak.player.ui

import android.os.Bundle
import app.falak.player.data.Channel
import app.falak.player.data.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The player comes back to what was playing after Android closed the app in the background. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class PlayerRestoreTest {
    private fun ch(i: Int) = Channel(id = "c$i", name = "Channel $i", group = "News", url = "http://x/$i")
    private val queue = (0 until 200).map { ch(it) }

    @Test
    fun keepsTheChannelsAroundTheOnePlaying() {
        val (list, at) = PlayerRestore.window(queue, 100)
        assertEquals(PlayerRestore.AROUND * 2 + 1, list.size)
        assertEquals("c100", list[at].id)
        val (start, atStart) = PlayerRestore.window(queue, 3)
        assertEquals("c0", start.first().id)
        assertEquals("c3", start[atStart].id)
        val (end, atEnd) = PlayerRestore.window(queue, 199)
        assertEquals("c199", end.last().id)
        assertEquals("c199", end[atEnd].id)
    }

    @Test
    fun savesAndReadsBack() {
        val series = Channel(id = "s1", name = "Show", group = "Drama", url = "", kind = Kind.SERIES, seriesId = "9")
        val b = Bundle()
        PlayerRestore.save(b, queue, 150, "pl1", series, 754_000L)
        val saved = PlayerRestore.read(b)!!
        assertEquals("c150", saved.queue[saved.index].id)
        assertEquals("pl1", saved.playlistId)
        assertEquals("s1", saved.series?.id)
        assertEquals(754_000L, saved.positionMs)
    }

    @Test
    fun nothingSavedIsNothing() {
        assertNull(PlayerRestore.read(null))
        assertNull(PlayerRestore.read(Bundle()))
        val b = Bundle()
        PlayerRestore.save(b, emptyList(), 0, null, null, -1L)
        assertNull(PlayerRestore.read(b))
        b.putString("restore.queue", "not json")
        assertNull(PlayerRestore.read(b))
    }
}
