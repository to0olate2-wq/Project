package app.falak.player.screens

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import androidx.media3.common.text.Cue
import androidx.media3.ui.SubtitleView
import app.falak.player.player.SubtitleStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws subtitles the way the player does (ExoPlayer's subtitle view with the look from Settings)
 * and checks every kind still shows: plain text, text with its own colours (as in SRT/ASS files
 * and closed captions), and picture subtitles (DVB, PGS). Saves the frames as PNGs to look at.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubtitleRenderTest {
    private val w = 1280
    private val h = 720

    private data class Look(val name: String, val size: Int, val color: String, val edge: String)

    private val looks = listOf(
        Look("as-it-comes", 1, "white", "box"),
        Look("large-yellow-outline", 3, "yellow", "outline"),
        Look("small-white-box", 0, "white", "box"),
        Look("extra-large-white-box", 3, "white", "box"),
    )

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun everyKindOfSubtitleShowsInEveryLook() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        for (look in looks) {
            val view = SubtitleView(activity)
            SubtitleStyle.applyTo(view, look.size, look.color, look.edge)
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
            view.setCues(cues())
            val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frame)
            canvas.drawColor(0xFF2F4A6B.toInt()) // the "video"
            view.draw(canvas)
            save(frame, "subs-${look.name}")

            // Plain text: the letters' colour shows up.
            val letters = count(frame) { r, g, b ->
                if (look.color == "yellow") r > 200 && g > 180 && b < 140 else r > 200 && g > 200 && b > 200
            }
            assertTrue("${look.name}: no plain subtitle text drawn ($letters px)", letters > 250)
            // The subtitle's own colour (cyan) is kept.
            val styled = count(frame) { r, g, b -> r < 90 && g > 190 && b > 190 }
            assertTrue("${look.name}: styled subtitle text missing ($styled px)", styled > 100)
            // Picture subtitles (green block) are drawn as sent.
            val picture = count(frame) { r, g, b -> r < 60 && g > 200 && b < 60 }
            assertTrue("${look.name}: picture subtitle missing ($picture px)", picture > 2_000)
        }
    }

    @Test
    fun defaultLookChangesNothingForVlc() {
        assertEquals(emptyList<String>(), SubtitleStyle.vlcOptions(1, "white", "box"))
        assertTrue(SubtitleStyle.vlcOptions(3, "yellow", "outline").isNotEmpty())
    }

    private fun cues(): List<Cue> {
        val styledText = SpannableString("Styled line from the file").apply {
            setSpan(ForegroundColorSpan(Color.CYAN), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.ITALIC), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val block = Bitmap.createBitmap(240, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF00E040.toInt()) }
        return listOf(
            Cue.Builder().setText("Plain subtitle line").build(),
            Cue.Builder().setText(styledText).setLine(0.15f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START).build(),
            Cue.Builder()
                .setBitmap(block)
                .setPosition(0.5f)
                .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                .setLine(0.45f, Cue.LINE_TYPE_FRACTION)
                .setLineAnchor(Cue.ANCHOR_TYPE_START)
                .setSize(0.3f)
                .setBitmapHeight(0.08f)
                .build(),
        )
    }

    private fun count(bmp: Bitmap, match: (Int, Int, Int) -> Boolean): Int {
        var n = 0
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        for (c in px) if (match(Color.red(c), Color.green(c), Color.blue(c))) n++
        return n
    }

    private fun save(bmp: Bitmap, name: String) {
        val dir = File(System.getProperty("screens.dir") ?: "build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
