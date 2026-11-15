package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private val t0 = 1_700_000_000_000L

    private fun phone(): AppState {
        val p = Playlist("p1", "Home TV", SourceType.XTREAM, "http://host:8080", username = "ahmed", password = "s3cret")
        return AppState(
            playlists = listOf(p),
            favorites = mapOf("p1" to listOf("c1", "c2")),
            settings = Settings(addons = listOf("https://torrentio.example/manifest.json"), audioLang = "ar"),
        )
    }

    @Test
    fun everythingComesBackOnANewPhone() {
        val text = Backup.make(phone(), "Pixel 8", "8.28", t0)
        val b = Backup.read(text)
        assertEquals("Pixel 8", b.device)
        assertEquals("1 playlist, 2 favorites, 1 add-on", Backup.summary(b))
        val (fresh, removed) = Backup.restore(AppState(), b, t0 + 60_000)
        assertTrue(removed.isEmpty())
        val p = fresh.playlists.single()
        assertEquals("Home TV", p.name)
        // The login comes back too (the file says so where it's made).
        assertEquals("s3cret", p.password)
        assertEquals(listOf("c1", "c2"), fresh.favorites[p.id])
        assertEquals(listOf("https://torrentio.example/manifest.json"), fresh.settings.addons)
        assertEquals("ar", fresh.settings.audioLang)
        // Not a linked device.
        assertTrue(fresh.sync.peers.isEmpty())
    }

    @Test
    fun whatChangedHereSinceStays() {
        val b = Backup.read(Backup.make(phone(), "Pixel 8", "8.28", t0))
        val (once, _) = Backup.restore(AppState(), b, t0 + 1_000)
        // A favorite added after the backup, then the backup restored again: the newer list stays.
        val later = once.copy(
            favorites = mapOf(once.playlists.single().id to listOf("c1", "c2", "c3")),
            sync = once.sync.copy(stamps = once.sync.stamps + ("${SyncMerge.FAV}|${once.playlists.single().id}" to t0 + 5_000)),
        )
        val (again, _) = Backup.restore(later, b, t0 + 6_000)
        assertEquals(listOf("c1", "c2", "c3"), again.favorites[again.playlists.single().id])
        assertEquals(1, again.playlists.size)
    }

    @Test
    fun notABackup() {
        for (text in listOf("", "{}", "hello", """{"app":"Other","kind":"backup"}""", """{"app":"The Player","kind":"backup","v":9}""")) {
            val failed = try {
                Backup.read(text)
                false
            } catch (e: IllegalArgumentException) {
                true
            }
            assertTrue(text, failed)
        }
        assertEquals("ThePlayer-backup-2023-11-14.json", Backup.fileName(t0))
    }
}
