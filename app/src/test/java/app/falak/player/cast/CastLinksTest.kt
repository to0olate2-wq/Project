package app.falak.player.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastLinksTest {
    @Test
    fun whatAChromecastCanPlay() {
        // Xtream live: as HLS.
        assertEquals("http://x.tv:8080/live/u/p/123.m3u8", CastLinks.castable("http://x.tv:8080/live/u/p/123.ts"))
        assertEquals("http://x.tv/live/u/p/1.m3u8?token=a", CastLinks.castable("http://x.tv/live/u/p/1.ts?token=a"))
        // Films and series as they are.
        assertEquals("http://x.tv/movie/u/p/9.mkv", CastLinks.castable("http://x.tv/movie/u/p/9.mkv"))
        // The app's own links: the TV can't reach them.
        assertNull(CastLinks.castable("http://127.0.0.1:41234/video.mkv?t=x"))
        assertNull(CastLinks.castable("http://localhost:8080/a.m3u8"))
        assertNull(CastLinks.castable("file:///data/x.mp4"))
        assertNull(CastLinks.castable("rtmp://x.tv/live"))
        assertEquals("application/x-mpegurl", CastLinks.contentType("http://x.tv/live/u/p/1.m3u8"))
        assertEquals("video/x-matroska", CastLinks.contentType("http://x.tv/movie/u/p/9.mkv?x=1"))
        assertEquals("video/mp4", CastLinks.contentType("http://x.tv/movie/u/p/9"))
    }
}
