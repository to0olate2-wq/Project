package app.falak.player.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoPictureTest {
    private fun video(mime: String, codecs: String?, w: Int, h: Int) =
        Format.Builder().setSampleMimeType(mime).setCodecs(codecs).setWidth(w).setHeight(h).build()

    @Test
    fun kindsAreCodecProfileAndSize() {
        assertEquals("video/hevc|hvc1.2|4k", NoPicture.kindOf(video(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.B0", 3840, 2160)))
        assertEquals("video/hevc|hvc1.2", NoPicture.kindOf(video(MimeTypes.VIDEO_H265, "hvc1.2.4.L120.B0", 1920, 1080)))
        assertEquals("video/dolby-vision|dvhe.05|4k", NoPicture.kindOf(video(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.05.06", 3840, 1600)))
        assertEquals("video/hevc|", NoPicture.kindOf(video(MimeTypes.VIDEO_H265, null, 1280, 720)))
    }

    @Test
    fun h264AndSoundAreNeverNoted() {
        assertNull(NoPicture.kindOf(video(MimeTypes.VIDEO_H264, "avc1.640028", 1920, 1080)))
        assertNull(NoPicture.kindOf(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()))
        assertNull(NoPicture.kindOf(null))
    }

    @Test
    fun aNotedKindIsKnownAndTheTitleRemembered() {
        NoPicture.clearForTest()
        val hdr = video(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.B0", 3840, 2160)
        assertFalse(NoPicture.knownKind(hdr))
        NoPicture.note("vod:1", hdr)
        assertTrue(NoPicture.has("vod:1"))
        assertTrue(NoPicture.knownKind(video(MimeTypes.VIDEO_H265, "hvc1.2.4.L150.B0", 3840, 2160)))
        assertFalse(NoPicture.knownKind(video(MimeTypes.VIDEO_H265, "hvc1.1.6.L120.B0", 1920, 1080)))
        NoPicture.clearForTest()
    }
}
