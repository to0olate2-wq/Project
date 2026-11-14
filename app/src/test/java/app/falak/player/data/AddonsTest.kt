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

    // As Cinemeta describes its lists: "Popular" can be narrowed to a genre, "New" has to be given its year.
    private val lists = """
        {"id":"com.example.lists","name":"Lists","types":["movie","series"],"resources":["catalog","meta"],
         "catalogs":[
           {"type":"movie","id":"top","name":"Popular","extra":[{"name":"genre","isRequired":false,"options":["Action","Comedy","Sci-Fi"],"optionsLimit":1},
                                                                {"name":"search","isRequired":false,"options":[]},{"name":"skip","isRequired":false,"options":[]}]},
           {"type":"movie","id":"year","name":"New","extra":[{"name":"genre","isRequired":true,"options":["2026","2025","2024"]},{"name":"skip"}]},
           {"type":"series","id":"old","name":"Old way","genres":["Drama","Crime"],"extraSupported":["genre","skip"],"extraRequired":[]},
           {"type":"series","id":"last-videos","name":"Last videos","extra":[{"name":"lastVideosIds","isRequired":true}]},
           {"type":"movie","id":"pickless","name":"No picks","extra":[{"name":"genre","isRequired":true}]},
           {"type":"movie","id":"plain","name":"Plain"}]}
    """.trimIndent()

    @Test
    fun listsWithPicksAndPages() {
        val c = Addons.parseManifest("https://x.com", lists).catalogs.associateBy { it.id }
        val top = c.getValue("top")
        assertEquals(listOf("Action", "Comedy", "Sci-Fi"), top.genres)
        assertFalse(top.genreRequired)
        assertFalse(top.needsExtra)
        assertTrue(top.paged)
        assertTrue(top.searchable)
        // "New" needs its year: shown all the same, with the first one.
        val new = c.getValue("year")
        assertTrue(new.genreRequired)
        assertFalse(new.needsExtra)
        assertEquals("2026", new.genres.first())
        // The older way of writing the same.
        val old = c.getValue("old")
        assertEquals(listOf("Drama", "Crime"), old.genres)
        assertTrue(old.paged)
        assertFalse(old.needsExtra)
        // Lists that only answer something else, or need a pick they don't offer: not shown.
        assertTrue(c.getValue("last-videos").needsExtra)
        assertTrue(c.getValue("pickless").needsExtra)
        val plain = c.getValue("plain")
        assertFalse(plain.needsExtra)
        assertFalse(plain.paged)
        assertTrue(plain.genres.isEmpty())

        assertEquals("catalog/movie/year/genre=2026.json", Addons.catalogPath(new, genre = "2026"))
        assertEquals("catalog/movie/top/genre=Sci-Fi&skip=100.json", Addons.catalogPath(top, skip = 100, genre = "Sci-Fi"))
        assertEquals("catalog/movie/top/genre=Science%20Fiction.json", Addons.catalogPath(top, genre = "Science Fiction"))
    }

    @Test
    fun continueWatching() {
        val film = AddonWatch("movie", "tt1", "A Film", videoId = "tt1")
        val ep2 = AddonWatch("series", "tt2", "A Show", videoId = "tt2:1:2", label = "S1 E2")
        // Started: first in the row. Closed at once: it was only looked at.
        var list = Addons.noteStart(emptyList(), film, now = 10)
        assertEquals(listOf("tt1"), list.map { it.videoId })
        assertTrue(Addons.noteProgress(list, "tt1", pos = 5_000, dur = 6_000_000, finished = false, now = 11).isEmpty())
        // Watched a while: where it was left is kept, and is still there when it is started again.
        list = Addons.noteProgress(list, "tt1", pos = 1_200_000, dur = 6_000_000, finished = false, now = 12)
        assertEquals(1_200_000, list.single().pos)
        list = Addons.noteStart(list, film, now = 13)
        assertEquals(1_200_000, list.single().pos)
        // (Starting again and closing at once doesn't lose the place.)
        assertEquals(1_200_000, Addons.noteProgress(list, "tt1", pos = 2_000, dur = 6_000_000, finished = false, now = 14).single().pos)
        // An episode goes first; the series is one card, whichever episode is on.
        list = Addons.noteStart(list, ep2, now = 20)
        list = Addons.noteProgress(list, "tt2:1:2", pos = 600_000, dur = 2_700_000, finished = false, now = 21)
        assertEquals(listOf("tt2:1:2", "tt1"), list.map { it.videoId })
        assertEquals(1, list.first().season)
        list = Addons.noteStart(list, ep2.copy(videoId = "tt2:1:3", label = "S1 E3"), now = 22)
        assertEquals(listOf("tt2:1:3", "tt1"), list.map { it.videoId })
        assertEquals(0, list.first().pos)
        // Watched to the end: the film leaves the row, the series stays to pick the next episode.
        list = Addons.noteProgress(list, "tt2:1:3", pos = 2_690_000, dur = 2_700_000, finished = true, now = 23)
        assertTrue(list.first().done)
        list = Addons.noteProgress(list, "tt1", pos = 5_990_000, dur = 6_000_000, finished = true, now = 24)
        assertEquals(listOf("tt2:1:3"), list.map { it.videoId })
        // Something the row doesn't know: nothing changes.
        assertTrue(Addons.noteProgress(list, "tt9", 1, 2, false, 25) === list)
        assertNull(film.season)
    }

    private fun link(addon: String, name: String, torrent: Boolean = false, about: String = "") = AddonStream(
        addonName = addon, url = if (torrent) "" else "https://x.com/$name/${about.hashCode()}", name = name, description = about, userAgent = "", referrer = "",
        torrent = if (torrent) app.falak.player.torrent.TorrentSource("0123456789abcdef0123456789abcdef01234567", null, emptyList(), "") else null,
    )

    @Test
    fun oneTapPicksTheKindChosenLast() {
        val links = listOf(
            link("Torrentio", "Torrentio\n4k", torrent = true),
            link("Torrentio", "Torrentio\n1080p", torrent = true),
            link("Direct", "1080p"),
            link("Direct", "720p", about = "Film.720p.WEB.mp4"),
            link("Torrentio", "Torrentio\n720p", torrent = true),
        )
        assertEquals(listOf("2160p", "1080p", "1080p", "720p", "720p"), links.map { Addons.quality(it) })
        // Nothing picked before: the first (add-ons put their best first).
        assertTrue(Addons.pickLink(links, "") === links[0])
        // The same quality from the same add-on, as a torrent.
        assertTrue(Addons.pickLink(links, Addons.linkKind(links[4])) === links[4])
        assertTrue(Addons.pickLink(links, "Direct|1080p|h") === links[2])
        // That add-on has nothing in that quality here: the quality still counts most.
        assertTrue(Addons.pickLink(links, "Other|720p|h") === links[3])
        // A quality that isn't offered: the same add-on and kind, its first.
        assertTrue(Addons.pickLink(links, "Direct|480p|h") === links[2])
        assertNull(Addons.pickLink(emptyList(), "Direct|480p|h"))
    }

    @Test
    fun subtitlesFromAddOns() {
        val subs = Addons.parseSubtitles(
            "Subs",
            """{"subtitles":[{"id":"1","url":"https://s.example/1.srt","lang":"eng"},{"id":"2","url":"https://s.example/2.srt","lang":"ara"},
               {"id":"3","url":"stremio://nope","lang":"fre"},{"id":"4","url":"https://s.example/4.vtt","lang":"fre"},{"id":"5","url":"https://s.example/5.srt","lang":"Klingon"}]}""",
        )
        assertEquals(4, subs.size)
        assertEquals("subtitles/series/tt1:1:2.json", Addons.subtitlesPath("series", "tt1:1:2"))
        assertEquals(listOf("en", "ar", "fr", "klingon"), subs.map { Addons.langCode(it.lang) })
        assertEquals("Arabic", Addons.langName("ara"))
        assertEquals("French", Addons.langName("fre"))
        assertEquals("Klingon", Addons.langName("Klingon"))
        assertEquals("ar", Addons.langCode("Arabic"))
        // Your language first, then English, then the rest.
        assertEquals(listOf("ara", "eng", "fre", "Klingon"), Addons.sortSubs(subs, "ar").map { it.lang })
        // A link's own subtitles are read with it.
        val st = Addons.parseStreams("X", """{"streams":[{"url":"https://v.example/a.mp4","name":"1080p","subtitles":[{"url":"https://s.example/a.srt","lang":"ara"}]}]}""")
        assertEquals("https://s.example/a.srt", st.playable.single().subs.single().url)
    }

    @Test
    fun subtitleFilesBecomeReadableText() {
        val arabic = "مرحبا"
        assertEquals(arabic, Addons.subtitleText(arabic.toByteArray(Charsets.UTF_8), "ara"))
        assertEquals(arabic, Addons.subtitleText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + arabic.toByteArray(Charsets.UTF_8), "ara"))
        // An old Windows file in Arabic, in Russian: read as what its language is written in.
        assertEquals(arabic, Addons.subtitleText(arabic.toByteArray(java.nio.charset.Charset.forName("windows-1256")), "ara"))
        assertEquals("Привет", Addons.subtitleText("Привет".toByteArray(java.nio.charset.Charset.forName("windows-1251")), "rus"))
        assertEquals("café", Addons.subtitleText("café".toByteArray(Charsets.ISO_8859_1), "fre"))
        assertEquals("hi", Addons.subtitleText("hi".toByteArray(Charsets.UTF_16), "eng"))
    }

    @Test
    fun whichAddonsFindLinks() {
        val both = Addons.parseManifest("https://x.com", manifest)
        assertTrue(Addons.hasLinks(both))
        assertFalse(Addons.hasLinks(Addons.parseManifest("https://x.com", lists)))
    }

    @Test
    fun rowNames() {
        val a = Addons.parseManifest("https://x.com", lists)
        val names = a.catalogs.filter { !it.needsExtra }.map { app.falak.player.ui.AddonsModel.Row(a, it).title }
        assertEquals(listOf("Popular · Films", "New · Films · 2026", "Old way · Series", "Plain · Films"), names)
        val said = AddonCatalog("movie", "m", "Top movies", searchable = false, needsExtra = false)
        assertEquals("Top movies", app.falak.player.ui.AddonsModel.Row(a, said).title)
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
                {"name":"4K","title":"Film.2160p.mkv\n12 sharing","infoHash":"ABCDEF0123456789ABCDEF0123456789ABCDEF01","fileIdx":3,
                 "sources":["tracker:udp://t.example:80/announce","dht:abcdef0123456789abcdef0123456789abcdef01"],
                 "behaviorHints":{"filename":"Film.2160p.mkv"}},
                {"name":"magnet","url":"magnet:?xt=urn:btih:1234567890123456789012345678901234567890&tr=http%3A%2F%2Ft%2Fa"},
                {"name":"broken","infoHash":"abc"},
                {"ytId":"xyz"},{"externalUrl":"https://site/watch"}]}""",
        )
        assertEquals(1, s.playable.size)
        assertEquals("UA/1", s.playable[0].userAgent)
        assertEquals("https://site", s.playable[0].referrer)
        assertEquals("file.mkv\n2.1 GB", s.playable[0].description)
        // Torrent links (8.16): read with everything needed to play them; a broken one is left out.
        assertEquals(2, s.torrents)
        assertEquals(2, s.torrentLinks.size)
        val t = s.torrentLinks[0].torrent!!
        assertEquals("abcdef0123456789abcdef0123456789abcdef01", t.infoHash)
        assertEquals(3, t.fileIdx)
        assertEquals(listOf("udp://t.example:80/announce"), t.trackers)
        assertEquals("Film.2160p.mkv", t.fileName)
        assertEquals("", s.torrentLinks[0].url)
        assertEquals("1234567890123456789012345678901234567890", s.torrentLinks[1].torrent!!.infoHash)
        assertEquals(listOf("http://t/a"), s.torrentLinks[1].torrent!!.trackers)
        assertEquals(3, s.others)
    }
}
