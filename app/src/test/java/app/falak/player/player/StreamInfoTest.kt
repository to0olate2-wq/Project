package app.falak.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamInfoTest {
    @Test
    fun namesInPlainWords() {
        assertEquals("H.265 (HEVC)", StreamInfo.videoCodec("video/hevc"))
        assertEquals("Dolby Digital Plus", StreamInfo.audioCodec("audio/eac3"))
        assertEquals("4K", StreamInfo.quality(3840, 2160))
        assertEquals("1080p", StreamInfo.quality(1920, 1080))
        // A wide film (2.39:1) is still 1080p.
        assertEquals("1080p", StreamInfo.quality(1920, 800))
        assertEquals("720p", StreamInfo.quality(1280, 720))
        assertNull(StreamInfo.quality(0, 0))
        assertEquals("6.2 Mb/s", StreamInfo.rate(6_200_000))
        assertEquals("850 kb/s", StreamInfo.rate(850_000))
        assertEquals("5.1", StreamInfo.channels(6))
        assertEquals("HLS", StreamInfo.linkKind("http://x.tv/live/u/p/1.m3u8"))
        assertEquals("MPEG-TS", StreamInfo.linkKind("http://x.tv/live/u/p/1.ts"))
        assertEquals("Torrent, streamed", StreamInfo.linkKind("http://127.0.0.1:43211/video.mkv?t=abc"))
    }

    @Test
    fun onlyWhatIsKnown() {
        val all = StreamInfo.lines("ExoPlayer", 1920, 1080, 50f, "video/avc", 0, "audio/mp4a-latm", 2, 48000, "ar", 5_400_000, 12_300, 3, "HLS")
        assertEquals(
            listOf(
                "Picture" to "1920 × 1080 · 1080p · 50 fps · H.264",
                "Sound" to "AAC · stereo · 48 kHz · Arabic",
                "Coming in at" to "5.4 Mb/s",
                "Ready ahead" to "12.3 s",
                "Frames dropped" to "3",
                "Link" to "HLS",
                "Playing with" to "ExoPlayer",
            ),
            all,
        )
        // VLC knows less.
        val vlc = StreamInfo.lines("VLC", 1280, 720, 23.976f, null, 0, null, 0, 0, null, 0, 0, 0, "MPEG-TS")
        assertEquals(listOf("Picture" to "1280 × 720 · 720p · 23.98 fps", "Link" to "MPEG-TS", "Playing with" to "VLC"), vlc)
    }
}
