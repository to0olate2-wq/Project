package app.falak.player.player

import androidx.media3.common.text.Cue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where captions go and how big they are: the same rules for the stream's own and auto captions. */
class CaptionLayoutTest {
    @Test
    fun keptOnTheVisiblePartOfThePicture() {
        // Fit: the picture's box is inside the view, nothing to keep in.
        assertArrayEquals(intArrayOf(0, 0, 0, 0), CaptionLayout.padding(120, 0, 1800, 1080, 1920, 1080))
        // Zoom on a 20:9 phone (2400 × 1080): a 16:9 picture 1350 high spills 135 px past the top and the bottom.
        assertArrayEquals(intArrayOf(0, 135, 0, 135), CaptionLayout.padding(0, -135, 2400, 1215, 2400, 1080))
        // Zoom with the phone upright (1080 × 1920): a 16:9 picture 3413 wide spills past both sides.
        assertArrayEquals(intArrayOf(1166, 0, 1167, 0), CaptionLayout.padding(-1166, 0, 2247, 1920, 1080, 1920))
    }

    @Test
    fun sizedByTheHeightButNeverHuge() {
        // The players' usual size: 0.0533 of the height.
        assertEquals(57.56f, CaptionLayout.textPx(1920, 1080, 1f), 0.01f)
        assertEquals(86.35f, CaptionLayout.textPx(1920, 1080, 1.5f), 0.01f)
        // 4:3 counts in full; taller than that, 3/4 of the width.
        assertEquals(CaptionLayout.textPx(1440, 1080, 1f), CaptionLayout.SIZE_FRACTION * 1080, 0.01f)
        assertEquals(CaptionLayout.SIZE_FRACTION * 810, CaptionLayout.textPx(1080, 1920, 1f), 0.01f)
    }

    @Test
    fun lineWidthLeavesThePaintersMargins() {
        // 1/8 of the text size (rounded) each side.
        assertEquals(1920f - 2 * 7, CaptionLayout.lineWidth(1920, 57.56f), 0f)
    }

    @Test
    fun places() {
        assertNull(CaptionLayout.line("bottom"))
        assertEquals(CaptionLayout.RAISED_LINE to Cue.ANCHOR_TYPE_END, CaptionLayout.line("raised"))
        assertEquals(CaptionLayout.TOP_LINE to Cue.ANCHOR_TYPE_START, CaptionLayout.line("top"))
        assertEquals("bottom", CaptionLayout.position("sideways"))
        assertEquals(3, CaptionLayout.lines(7))
        assertEquals(1, CaptionLayout.lines(0))
    }

    @Test
    fun linesFitTheWidthAndTheNumberChosen() {
        val text = CaptionLayout.SAMPLE
        // Each line as wide as fits (here 30 letters), the last ones kept.
        assertEquals(
            listOf("stadium, where the teams are", "coming out for the second", "half."),
            CaptionText.rollUp(text, 3) { it.length <= 30 }!!.split('\n'),
        )
        // One line: just the newest words.
        assertEquals("half.", CaptionText.rollUp(text, 1) { it.length <= 30 })
        // The same as the old letter count.
        assertEquals(CaptionText.rollUp(text, 42, 2), CaptionText.rollUp(text, 2) { it.length <= 42 })
        // Lines already broken are wrapped again.
        assertEquals("one two three four", CaptionText.rollUp("one two\nthree four", 1) { it.length <= 40 })
        // A word wider than a line still shows.
        assertEquals("Supercalifragilistic", CaptionText.rollUp("Supercalifragilistic", 2) { it.length <= 5 })
        assertNull(CaptionText.rollUp(" \n ", 2) { true })
    }
}
