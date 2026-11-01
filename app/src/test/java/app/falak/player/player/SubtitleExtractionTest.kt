package app.falak.player.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Films' own subtitles, read the way the player reads them (its extractors, on small films made
 * with ffmpeg): each one comes out as subtitles the player can show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class SubtitleExtractionTest {
    private class Track(val type: Int) : TrackOutput {
        var format: Format? = null
        var samples = 0

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val buffer = ByteArray(length)
            val read = input.read(buffer, 0, length)
            return if (read == C.RESULT_END_OF_INPUT && allowEndOfInput) C.RESULT_END_OF_INPUT else read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            samples++
        }
    }

    private class Output : ExtractorOutput {
        val tracks = LinkedHashMap<Int, Track>()
        override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { Track(type) }
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) {}
        fun text(): Track? = tracks.values.firstOrNull { it.type == C.TRACK_TYPE_TEXT }
    }

    private fun bytes(name: String): ByteArray =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("subs/$name")) { "missing $name" }.use { it.readBytes() }

    private fun inputAt(data: ByteArray, position: Long): ExtractorInput {
        val source = ByteArrayDataSource(data)
        source.open(DataSpec.Builder().setUri(Uri.EMPTY).setPosition(position).build())
        return DefaultExtractorInput(source, position, data.size.toLong())
    }

    /** The whole file through the first extractor that recognises it. */
    private fun extract(name: String, factory: ExtractorsFactory): Output {
        val data = bytes(name)
        var input = inputAt(data, 0)
        val extractor: Extractor = factory.createExtractors().first { e ->
            val yes = try {
                e.sniff(input)
            } catch (x: Exception) {
                false
            }
            input.resetPeekPosition()
            yes
        }
        val output = Output()
        extractor.init(output)
        val seek = PositionHolder()
        var guard = 0
        while (guard++ < 100_000) {
            when (extractor.read(input, seek)) {
                Extractor.RESULT_END_OF_INPUT -> return output
                Extractor.RESULT_SEEK -> input = inputAt(data, seek.position)
            }
        }
        error("$name never ended")
    }

    private fun appFactory() = SniffingExtractorsFactory(CaptionSniffer {})

    private fun assertShowable(track: Track?, original: String) {
        assertNotNull("no subtitle track", track)
        val f = track!!.format!!
        // Prepared on the way in: the form the player shows.
        assertEquals(MimeTypes.APPLICATION_MEDIA3_CUES, f.sampleMimeType)
        assertEquals(original, f.codecs)
        assertTrue("no subtitle lines", track.samples > 0)
    }

    @Test
    fun srtInMkv() = assertShowable(extract("srt.mkv", appFactory()).text(), MimeTypes.APPLICATION_SUBRIP)

    @Test
    fun assInMkv() = assertShowable(extract("ass.mkv", appFactory()).text(), MimeTypes.TEXT_SSA)

    @Test
    fun movTextInMp4() = assertShowable(extract("movtext.mp4", appFactory()).text(), MimeTypes.APPLICATION_TX3G)

    @Test
    fun olderSsaNameInMkv() {
        // Media3 on its own skips these subtitles (VLC shows them)...
        assertNull(extract("ssa.mkv", DefaultExtractorsFactory()).text())
        // ...the player reads them.
        assertShowable(extract("ssa.mkv", appFactory()).text(), MimeTypes.TEXT_SSA)
    }

    @Test
    fun oldNamesSwappedForSameLengthOnes() {
        val ascii = "xxS_TEXT/ASCIIyy".toByteArray()
        MatroskaSubtitles.fix(ascii, 2, 12)
        assertArrayEquals("xxS_TEXT/UTF8\u0000yy".toByteArray(), ascii)
        val ssa = "S_TEXT/SSA".toByteArray()
        MatroskaSubtitles.fix(ssa, 0, 10)
        assertArrayEquals("S_TEXT/ASS".toByteArray(), ssa)
        // Anything else is left as it is.
        val other = "S_TEXT/UTF8".toByteArray()
        MatroskaSubtitles.fix(other, 0, 11)
        assertArrayEquals("S_TEXT/UTF8".toByteArray(), other)
        val video = "V_MPEG4/ISO".toByteArray()
        MatroskaSubtitles.fix(video, 0, 11)
        assertArrayEquals("V_MPEG4/ISO".toByteArray(), video)
    }
}
