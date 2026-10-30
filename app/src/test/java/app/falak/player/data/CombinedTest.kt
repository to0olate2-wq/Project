package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedTest {
    private val a = Playlist("pa", "Provider A", SourceType.XTREAM, "http://a.example:8080", "alice", "pw1", userAgent = "UA-A")
    private val b = Playlist("pb", "Provider B", SourceType.XTREAM, "http://b.example", "bob", "pw2")
    private val both = Playlist("pc", "Both", SourceType.M3U_FILE, "", sources = listOf("pa", "pb"))
    private val all = listOf(a, b, both)

    private fun ch(id: String, name: String, group: String, kind: Kind = Kind.LIVE, url: String = "http://x/$id", seriesId: String = "") =
        Channel(id = id, name = name, group = group, url = url, kind = kind, seriesId = seriesId)

    private val cacheA = PlaylistCache(
        listOf(
            ch("l1", "MBC 1", "Arabic"),
            ch("m7", "The Film", "Films", Kind.MOVIE),
            ch("s9", "The Show", "Series", Kind.SERIES, url = "", seriesId = "9"),
        ),
        epgUrl = "http://a.example/xmltv.php",
        serverTz = "Asia/Dubai",
        savedAt = 200,
    )
    private val cacheB = PlaylistCache(
        listOf(
            // The same number as A's channel, another channel.
            ch("l1", "MBC 2", "Arabic", url = "http://b/l1"),
            // The same film in the same group: A's shows.
            ch("m8", "The Film", "Films", Kind.MOVIE),
            // The very same stream as A's: once.
            ch("l5", "MBC 1 copy", "Arabic", url = "http://x/l1"),
        ),
        epgUrl = "http://b.example/xmltv.php",
        serverTz = "Europe/London",
        savedAt = 100,
    )

    @Test
    fun putsThemTogetherEachMarkedWithItsPlaylist() {
        val sources = Combined.sourcesOf(both, all)
        assertEquals(listOf("pa", "pb"), sources.map { it.first.id })
        val (ta, tb) = sources.map { it.second }
        assertNotEquals(ta, tb)
        val out = Combined.combine(listOf(Triple(a, ta, cacheA), Triple(b, tb, cacheB)))
        assertEquals(listOf("l1~$ta", "m7~$ta", "s9~$ta", "l1~$tb"), out.channels.map { it.id })
        // Each keeps what its playlist needs: its user agent, its series id with the mark.
        assertEquals("UA-A", out.channels[0].userAgent)
        assertEquals("9~$ta", out.channels[2].seriesId)
        assertEquals("http://a.example/xmltv.php\nhttp://b.example/xmltv.php", out.epgUrl)
        assertEquals(100L, out.savedAt)
        // Replays: each provider's own time zone.
        assertEquals("Asia/Dubai", Combined.zoneFor(out.serverTz, "l1~$ta"))
        assertEquals("Europe/London", Combined.zoneFor(out.serverTz, "l1~$tb"))
        assertEquals("Asia/Dubai", Combined.zoneFor("Asia/Dubai", "l1"))
    }

    @Test
    fun findsWhereAnItemCameFrom() {
        val (ta, tb) = Combined.sourcesOf(both, all).map { it.second }
        assertEquals("pa", Combined.sourceFor(both, "m7~$ta", all)?.id)
        assertEquals("pb", Combined.sourceFor(both, "9~$tb", all)?.id)
        // A copy listed twice, and a replay of a channel.
        assertEquals("pb", Combined.sourceFor(both, "l1~${tb}_2", all)?.id)
        assertEquals("pa", Combined.sourceFor(both, "cu:l1~$ta:1700000000000", all)?.id)
        assertEquals("7", Combined.untag("m7~$ta").removePrefix("m"))
        assertEquals("123", Combined.untag("123~${ta}_2"))
        assertNull(Combined.tagOf("m7"))
    }

    @Test
    fun theMarkIsTheSameOnEveryDevice() {
        // Another device knows the same login by another id: the same mark.
        assertEquals(Combined.tag(a), Combined.tag(a.copy(id = "other-id", url = "http://new-domain.example")))
        assertNotEquals(Combined.tag(a), Combined.tag(b))
    }

    @Test
    fun removedPlaylistsAndCombinedOnesAreLeftOut() {
        val withGone = both.copy(sources = listOf("pa", "gone", "pc", "pb"))
        assertEquals(listOf("pa", "pb"), Combined.sourcesOf(withGone, all).map { it.first.id })
        assertTrue(Combined.sourcesOf(both.copy(sources = listOf("gone")), all).isEmpty())
    }

    @Test
    fun episodesCarryTheMarkToo() {
        val info = SeriesInfo("", "", listOf(Season(1, listOf(ch("e1", "Ep 1", "Season 1", Kind.SERIES)))))
        val marked = Combined.markEpisodes(info, a, "k1")
        assertEquals("e1~k1", marked.seasons[0].episodes[0].id)
        assertEquals("UA-A", marked.seasons[0].episodes[0].userAgent)
    }

    @Test
    fun combinedPlaylistsSyncWithTheirPlaylistsMapped() {
        val t0 = 1_700_000_000_000L
        // A knows B's "pb" as "pb"; on B it's "xb" (set up separately, same login): B's alias says so.
        val devA = AppState(playlists = listOf(a, b, both), sync = SyncMeta(deviceId = "A", key = "k"))
        val bLocal = b.copy(id = "xb")
        val devB = AppState(
            playlists = listOf(a, bLocal),
            sync = SyncMeta(deviceId = "B", key = "k", alias = mapOf("xb" to "pb")),
        )
        val stamped = SyncMerge.touchAll(devA, t0)
        val b2 = SyncMerge.merge(devB, SyncMerge.snapshot(stamped, "A"), t0 + 1).first
        val got = b2.playlists.single { it.isCombined }
        assertEquals(listOf("pa", "xb"), got.sources)
    }
}
