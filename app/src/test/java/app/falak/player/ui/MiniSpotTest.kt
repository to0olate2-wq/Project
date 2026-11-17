package app.falak.player.ui

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The mini player stays where it was put, and never leaves the screen. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MiniSpotTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun startsBottomRightAndKeepsItsPlace() {
        assertEquals(1f to 1f, MiniSpot.read(context))
        MiniSpot.save(context, 0.25f, 0f)
        assertEquals(0.25f to 0f, MiniSpot.read(context))
        MiniSpot.save(context, 3f, -1f)
        assertEquals(1f to 0f, MiniSpot.read(context))
    }

    @Test
    fun movesInsideTheScreen() {
        assertEquals(0.5f, MiniSpot.moved(0f, 50f, 100f), 0.0001f)
        assertEquals(1f, MiniSpot.moved(0.9f, 500f, 100f), 0.0001f)
        assertEquals(0f, MiniSpot.moved(0.1f, -500f, 100f), 0.0001f)
        // No room to move in (a screen as narrow as the mini player): it stays.
        assertEquals(0.7f, MiniSpot.moved(0.7f, 40f, 0f), 0.0001f)
    }
}
