package app.falak.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "In time with the speech": the speech engine's word times turned into the moments the words are
 * heard. The numbers are the engine's own (sherpa-onnx with the Kroko model, run on its test
 * recording): it works in 1.28-second steps, and after a pause its new line starts at the end of
 * the last step (5 steps: 6.4 seconds in), with word times counted from there.
 */
class SyncedCaptionsTest {
    /** Sound in 20 ms pieces, the first heard at 5 s in the audio output's timeline. */
    private class Feeder(val clock: WordClock, val startUs: Long = 5_000_000) {
        var fed = 0.0
        fun feed(seconds: Double, known: Boolean = true) {
            repeat((seconds / 0.02 + 0.5).toInt()) {
                clock.fed(0.02, if (known) startUs + (fed * 1e6 + 0.5).toLong() else Word.NOW)
                fed += 0.02
            }
        }
    }

    private fun near(expectedUs: Long, actualUs: Long) =
        assertTrue("expected $expectedUs, got $actualUs", kotlin.math.abs(expectedUs - actualUs) <= 1_000)

    @Test
    fun wordsAreHeardWhenTheirFirstPieceIs() {
        val clock = WordClock(SpeechModel.CHUNK_SECONDS)
        Feeder(clock).feed(2.0)
        clock.stepped()
        val words = clock.words(arrayOf(" A", "s", "k", " not", ",", " what"), floatArrayOf(0.34f, 0.42f, 0.54f, 0.78f, 0.86f, 0.9f))
        assertEquals(listOf("Ask", "not,", "what"), words.map { it.text })
        near(5_340_000, words[0].atUs)
        near(5_780_000, words[1].atUs)
        near(5_900_000, words[2].atUs)
        assertEquals("Ask not, what", CaptionText.join(words))
    }

    @Test
    fun aNewLineStartsAfterTheStepsWorkedOn() {
        val clock = WordClock(SpeechModel.CHUNK_SECONDS)
        val feeder = Feeder(clock)
        feeder.feed(6.9)
        repeat(5) { clock.stepped() }
        clock.lineEnded()
        feeder.feed(3.0)
        clock.stepped()
        // The engine said this line started at 6.4 s; its first word 1.28 s into it.
        val words = clock.words(arrayOf(" Ask", " not"), floatArrayOf(1.28f, 1.72f))
        near(5_000_000 + 7_680_000, words[0].atUs)
        near(5_000_000 + 8_120_000, words[1].atUs)
    }

    @Test
    fun soundOfUnknownTimeShowsAtOnce() {
        val clock = WordClock(SpeechModel.CHUNK_SECONDS)
        val feeder = Feeder(clock)
        feeder.feed(1.0, known = false)
        feeder.feed(1.0)
        val words = clock.words(arrayOf(" Good", " evening"), floatArrayOf(0.5f, 1.5f))
        assertEquals(Word.NOW, words[0].atUs)
        near(6_500_000, words[1].atUs)
        // Starting afresh (a new channel) forgets the old times.
        clock.restart()
        assertEquals(Word.NOW, clock.words(arrayOf(" Hi"), floatArrayOf(0.1f))[0].atUs)
    }

    @Test
    fun showingTheWordsHeardByNow() {
        val words = listOf(Word("Good", 1_000_000), Word("evening,", 1_400_000), Word("and", 2_100_000), Word("welcome.", 2_300_000))
        // Not in time with the speech: all of them, nothing to wait for.
        assertEquals(words to null, CaptionText.showing(words, null))
        val (heard, next) = CaptionText.showing(words, 1_500_000)
        assertEquals(listOf("Good", "evening,"), heard.map { it.text })
        assertEquals(2_100_000L, next)
        assertEquals(emptyList<Word>() to 1_000_000L, CaptionText.showing(words, 900_000))
        assertNull(CaptionText.showing(words, 5_000_000).second)
        // Words without a time show at once.
        val (now, later) = CaptionText.showing(listOf(Word("Hi"), Word("there", 3_000_000)), 1_000_000)
        assertEquals(listOf("Hi"), now.map { it.text })
        assertEquals(3_000_000L, later)
    }

    @Test
    fun theLineBeforeStaysAboveTheNextOne() {
        val t = CaptionText()
        t.finish(listOf(Word("Good", 1), Word("evening.", 2)))
        assertEquals(listOf("Good", "evening.", "Tonight"), t.partial(listOf(Word("Tonight", 3)))!!.map { it.text })
        t.clear()
        assertNull(t.partial(emptyList()))
    }
}
