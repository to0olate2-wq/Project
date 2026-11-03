package app.falak.player.record

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsTest {
    @Test
    fun picksTheBestVersionWhoseSoundIsInItsParts() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",URI="audio/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,AUDIO="aud",CODECS="avc1.640028,mp4a.40.2"
            hi/separate.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720
            mid/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=800000
            low/index.m3u8
        """.trimIndent()
        assertTrue(Hls.isPlaylist(master))
        assertTrue(Hls.isMaster(master))
        val m = Hls.parseMaster(master, "http://host/live/ch1/index.m3u8")
        assertEquals(3, m.variants.size)
        assertEquals(setOf("aud"), m.separateAudio)
        assertEquals("http://host/live/ch1/mid/index.m3u8", Hls.pick(m)?.uri)
    }

    @Test
    fun noVersionWhenAllHaveSeparateSound() {
        val master = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",URI=\"a.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO=\"a\"\nv.m3u8\n"
        assertNull(Hls.pick(Hls.parseMaster(master, "http://h/x.m3u8")))
    }

    @Test
    fun readsPartsKeysHeadersAndTheEnd() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:120
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6.006,
            seg120.m4s
            #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example/k?id=1,2",IV=0x000102030405060708090A0B0C0D0E0F
            #EXTINF:5.5,title
            /abs/seg121.m4s?token=x
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:6,
            http://other.example/seg122.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        val m = Hls.parseMedia(media, "http://cdn.example/ch/media.m3u8")
        assertEquals(6_000L, m.targetMs)
        assertTrue(m.ended)
        assertEquals(listOf(120L, 121L, 122L), m.segments.map { it.sequence })
        assertEquals("http://cdn.example/ch/seg120.m4s", m.segments[0].uri)
        assertEquals("http://cdn.example/abs/seg121.m4s?token=x", m.segments[1].uri)
        assertEquals("http://other.example/seg122.m4s", m.segments[2].uri)
        assertEquals("http://cdn.example/ch/init.mp4", m.segments[0].map)
        assertEquals(6_006L, m.segments[0].durationMs)
        assertNull(m.segments[0].key)
        val key = m.segments[1].key!!
        assertEquals("AES-128", key.method)
        assertEquals("https://keys.example/k?id=1,2", key.uri)
        assertArrayEquals(ByteArray(16) { it.toByte() }, Hls.iv(key, 121))
        assertNull(m.segments[2].key)
    }

    @Test
    fun ivFromThePartNumberWhenNoneIsGiven() {
        val key = Hls.Key("AES-128", "k", null)
        val iv = Hls.iv(key, 0x0102L)
        assertEquals(16, iv.size)
        assertEquals(0x01.toByte(), iv[14])
        assertEquals(0x02.toByte(), iv[15])
        assertEquals(0.toByte(), iv[0])
    }

    @Test
    fun attributesWithQuotedCommas() {
        val a = Hls.attributes("BANDWIDTH=1280000,CODECS=\"avc1.4d401f,mp4a.40.2\",RESOLUTION=640x360")
        assertEquals("1280000", a["BANDWIDTH"])
        assertEquals("avc1.4d401f,mp4a.40.2", a["CODECS"])
        assertEquals("640x360", a["RESOLUTION"])
    }
}
