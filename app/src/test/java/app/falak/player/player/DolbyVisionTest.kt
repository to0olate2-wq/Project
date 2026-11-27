package app.falak.player.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DolbyVisionTest {
    private fun video(mime: String, codecs: String?) = Format.Builder().setSampleMimeType(mime).setCodecs(codecs).build()

    @Test
    fun profileFiveIsDolbyOnly() {
        assertTrue(DolbyVision.onlyDolby(video(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.05.06")))
        assertTrue(DolbyVision.onlyDolby(video(MimeTypes.VIDEO_DOLBY_VISION, "dvh1.05.09")))
        assertFalse(DolbyVision.onlyDolby(video(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.07.06")))
        assertFalse(DolbyVision.onlyDolby(video(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.08.06")))
        assertFalse(DolbyVision.onlyDolby(video(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.B0")))
        assertFalse(DolbyVision.onlyDolby(null))
    }
}
