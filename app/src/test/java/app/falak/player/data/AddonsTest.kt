package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stremio-style add-ons: links people paste, and what add-ons answer. */
class AddonsTest {
    @Test
    fun linksAsPeoplePasteThem() {
        assertEquals("https://v3-cinemeta.strem.io", Addons.normalize("https://v3-cinemeta.strem.io/manifest.json"))
        assertEquals("https://addon.example.com/abc123", Addons.normalize("stremio://addon.example.com/abc123/manifest.json"))
        assertEquals("https://addon.example.com", Addons.normalize(" addon.example.com/ "))
        assertEquals("http://192.168.1.5:7000", Addons.normalize("http://192.168.1.5:7000/manifest.json"))
        assertNull(Addons.normalize(""))
        assertNull(Addons.normalize("not a link"))
        assertNull(Addons.normalize("ftp://x.com/manifest.json"))
        assertNull(Addons.normalize("nodots"))
    }

    private val manifest = """
        {"id":"org.example.films","version":"1.0.0","name":"Example","description":"Films",
         "types":["movie","series"],"idPrefixes":["tt"],
         "resources":["catalog",{"name":"meta","types":["movie","series"],"idPrefixes":["tt"]},
                      {"name":"stream","types":["movie","series"],"idPrefixes":["tt","kitsu"]}],
         "catalogs":[{"type":"movie","id":"top","name":"Popular","extra":[{"name":"search"},{"name":"skip"}]},
                     {"type":"series","id":"search-only","extra":[{"name":"search","isRequired":true}]}],
         "behaviorHints":{"configurable":true}}
    """.trimIndent()

    @Test
    fun manifest() {
        val a = Addons.parseManifest("https://x.com", manifest)
        assertEquals("Example", a.name)
        assertEquals(2, a.catalogs.size)
        assertTrue(a.catalogs[0].searchable)
        assertFalse(a.catalogs[0].needsExtra)
        assertTrue(a.catalogs[1].needsExtra)
        assertEquals("search-only", a.catalogs[1].name)
        assertTrue(a.supports("stream", "series", "tt123:1:2"))
        assertTrue(a.supports("stream", "movie", "kitsu:5"))
        assertFalse(a.supports("meta", "movie", "kitsu:5"))
        assertFalse(a.supports("subtitles", "movie", "tt1"))
        assertTrue(a.supports("meta", "series", "tt9"))
        assertFalse(a.configurationRequired)
    }

    @Test
    fun catalogPaths() {
        val c = AddonCatalog("movie", "top", "Popular", searchable = true, needsExtra = false)
        assertEquals("catalog/movie/top.json", Addons.catalogPath(c))
        assertEquals("catalog/movie/top/search=the%20matrix.json", Addons.catalogPath(c, "the matrix"))
        assertEquals("catalog/movie/top/skip=100.json", Addons.catalogPath(c, skip = 100))
        assertEquals("stream/series/tt1:1:2.json", Addons.streamPath("series", "tt1:1:2"))
    }

    @Test
    fun catalogAndMeta() {
        val items = Addons.parseCatalog(
            "https://x.com",
            """{"metas":[{"id":"tt1","type":"movie","name":"One","poster":"https://p/1.jpg","releaseInfo":"2020","imdbRating":"7.1"},
                        {"id":"tt1","type":"movie","name":"One again"},{"type":"movie","name":"No id"}]}""",
        )
        assertEquals(1, items.size)
        assertEquals("2020", items[0].year)
        val meta = Addons.parseMeta(
            """{"meta":{"id":"tt2","type":"series","name":"Show","genres":["Drama"],"releaseInfo":"2019–",
                "videos":[{"id":"tt2:2:1","title":"B","season":2,"episode":1},{"id":"tt2:0:1","name":"Extra","season":0,"number":1},
                          {"id":"tt2:1:2","title":"A2","season":1,"episode":2},{"id":"tt2:1:1","title":"A1","season":1,"episode":1}]}}""",
        )!!
        assertEquals(listOf("tt2:1:1", "tt2:1:2", "tt2:2:1", "tt2:0:1"), meta.videos.map { it.id })
        assertEquals(listOf("Drama"), meta.genres)
        assertNull(Addons.parseMeta("""{"metas":[]}"""))
    }

    @Test
    fun onlyLinksThePlayerCanOpen() {
        val s = Addons.parseStreams(
            "Example",
            """{"streams":[
                {"name":"1080p","description":"file.mkv\n2.1 GB","url":"https://cdn.example.com/a.mkv",
                 "behaviorHints":{"proxyHeaders":{"request":{"user-agent":"UA/1","Referer":"https://site"}}}},
                {"name":"4K","infoHash":"abc","fileIdx":0},
                {"name":"magnet","url":"magnet:?xt=urn:btih:abc"},
                {"ytId":"xyz"},{"externalUrl":"https://site/watch"}]}""",
        )
        assertEquals(1, s.playable.size)
        assertEquals("UA/1", s.playable[0].userAgent)
        assertEquals("https://site", s.playable[0].referrer)
        assertEquals("file.mkv\n2.1 GB", s.playable[0].description)
        assertEquals(2, s.torrents)
        assertEquals(2, s.others)
    }
}
