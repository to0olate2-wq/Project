package app.falak.player.sync

import androidx.test.core.app.ApplicationProvider
import app.falak.player.FalakApp
import app.falak.player.data.FavList
import app.falak.player.data.Playlist
import app.falak.player.data.SourceType
import app.falak.player.data.SyncBundle
import app.falak.player.data.SyncPlaylist
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The sharing server for real, over a local socket: page, code check, copy, add, merge, rename, remove. */
@RunWith(RobolectricTestRunner::class)
class SyncTest {

    @Test
    fun shareCopyAndMerge() {
        val repo = ApplicationProvider.getApplicationContext<FalakApp>().repo
        repo.update {
            it.copy(
                playlists = listOf(Playlist("a", "Home", SourceType.M3U_URL, "http://example.com/a.m3u")),
                favorites = mapOf("a" to listOf("ch1")),
                favLists = mapOf("a" to listOf(FavList("l1", "Sports", listOf("ch2")))),
            )
        }
        val server = SyncServer(repo, "Test TV") {}
        val port = server.start()
        val http = OkHttpClient()
        val base = "http://127.0.0.1:$port"
        val jsonType = "application/json".toMediaType()
        try {
            val page = http.newCall(Request.Builder().url("$base/").build()).execute().use { it.body!!.string() }
            assertTrue("page served", page.contains("The Player") && page.contains("Test TV"))

            val wrong = http.newCall(Request.Builder().url("$base/api/playlists").header("X-Code", "nope").build()).execute()
            assertEquals(403, wrong.code)
            wrong.close()

            // Another device copies everything from this one.
            val bundle = runBlocking { SyncClient.fetch(http, "127.0.0.1:$port", server.code) }
            assertEquals(listOf("Home"), bundle.playlists.map { it.playlist.name })
            assertEquals(listOf("ch1"), bundle.favorites["a"])

            // A phone browser adds a playlist.
            val add = """{"type":"m3u","name":"From phone","url":"http://example.com/b.m3u"}""".toRequestBody(jsonType)
            http.newCall(Request.Builder().url("$base/api/playlists").header("X-Code", server.code).post(add).build()).execute().use {
                assertEquals(200, it.code)
            }
            assertEquals(listOf("Home", "From phone"), repo.state.value.playlists.map { it.name })

            // Another device sends its playlists: the same one is merged, a new one is added.
            val incoming = SyncBundle(
                from = "Phone",
                playlists = listOf(
                    SyncPlaylist(Playlist("zz", "Home renamed", SourceType.M3U_URL, "http://example.com/a.m3u/")),
                    SyncPlaylist(Playlist("c", "Films", SourceType.M3U_FILE, "/elsewhere/c.m3u"), m3u = "#EXTM3U\n#EXTINF:-1,One\nhttp://x/1\n"),
                ),
                favorites = mapOf("zz" to listOf("ch9")),
                favLists = mapOf("zz" to listOf(FavList("other", "sports", listOf("ch3")))),
            )
            val message = runBlocking { SyncClient.send(http, "127.0.0.1:$port", server.code, incoming) }
            assertEquals("1 playlist added, 1 updated", message)
            val state = repo.state.value
            assertEquals(listOf("Home renamed", "From phone", "Films"), state.playlists.map { it.name })
            assertEquals(listOf("ch1", "ch9"), state.favorites["a"])
            assertEquals(listOf("ch2", "ch3"), state.favLists["a"]!!.single().ids)
            val films = state.playlists.first { it.name == "Films" }
            assertTrue("file saved on this device", java.io.File(films.url).readText().contains("#EXTINF"))

            // Rename and remove from the browser.
            val id = state.playlists.first { it.name == "From phone" }.id
            http.newCall(
                Request.Builder().url("$base/api/playlists/rename").header("X-Code", server.code)
                    .post("""{"id":"$id","name":"Kitchen"}""".toRequestBody(jsonType)).build(),
            ).execute().close()
            http.newCall(
                Request.Builder().url("$base/api/playlists/delete").header("X-Code", server.code)
                    .post("""{"id":"${films.id}"}""".toRequestBody(jsonType)).build(),
            ).execute().close()
            assertEquals(listOf("Home renamed", "Kitchen"), repo.state.value.playlists.map { it.name })

            // Show & hide groups from the browser: listed from the saved playlist, hidden by name.
            val ctx = ApplicationProvider.getApplicationContext<FalakApp>()
            app.falak.player.data.Store.writePlaylist(
                java.io.File(java.io.File(ctx.filesDir, "cache").apply { mkdirs() }, "a.bin"),
                app.falak.player.data.PlaylistCache(
                    listOf(
                        app.falak.player.data.Channel(id = "1", name = "One", group = "News", url = "u"),
                        app.falak.player.data.Channel(id = "2", name = "Two", group = "Sports", url = "u"),
                        app.falak.player.data.Channel(id = "3", name = "F", group = "Drama", url = "u", kind = app.falak.player.data.Kind.MOVIE),
                    ),
                ),
            )
            val groups = http.newCall(Request.Builder().url("$base/api/groups?id=a").header("X-Code", server.code).build())
                .execute().use { it.body!!.string() }
            assertTrue(groups, groups.contains("\"ready\":true") && groups.contains("\"name\":\"Sports\"") && groups.contains("\"label\":\"Films\""))
            http.newCall(
                Request.Builder().url("$base/api/groups").header("X-Code", server.code)
                    .post("""{"id":"a","groups":["Sports","Drama"],"hidden":true}""".toRequestBody(jsonType)).build(),
            ).execute().use { assertTrue(it.body!!.string().contains("Sports")) }
            assertEquals(listOf("Sports", "Drama"), repo.state.value.settings.hiddenGroups["a"])
            http.newCall(
                Request.Builder().url("$base/api/groups").header("X-Code", server.code)
                    .post("""{"id":"a","groups":["Sports"],"hidden":false}""".toRequestBody(jsonType)).build(),
            ).execute().close()
            assertEquals(listOf("Drama"), repo.state.value.settings.hiddenGroups["a"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun bigM3uFileFromThePhonePage() {
        val repo = ApplicationProvider.getApplicationContext<FalakApp>().repo
        val server = SyncServer(repo, "Test TV") {}
        val port = server.start()
        val http = OkHttpClient()
        val base = "http://127.0.0.1:$port"
        val before = repo.state.value.playlists.size
        try {
            // Over the old 40 MB limit: sent as it is and written straight to storage.
            var entries = 0
            val big = buildString {
                append("\uFEFF#EXTM3U\n")
                while (length < 42 * 1024 * 1024) {
                    append("#EXTINF:-1 tvg-id=\"c$entries\" group-title=\"Group ${entries % 40}\",Channel $entries\nhttp://example.com/live/u/p/$entries.ts\n")
                    entries++
                }
            }
            val bytes = big.toByteArray()
            http.newCall(
                Request.Builder().url("$base/api/playlists/upload?name=Big%20one&epg=http%3A%2F%2Fexample.com%2Fepg.xml")
                    .header("X-Code", server.code).post(bytes.toRequestBody("application/octet-stream".toMediaType())).build(),
            ).execute().use { assertEquals(200, it.code) }
            assertEquals(before + 1, repo.state.value.playlists.size)
            val added = repo.state.value.playlists.first { it.name == "Big one" }
            assertEquals(SourceType.M3U_FILE, added.type)
            assertEquals("http://example.com/epg.xml", added.epgUrl)
            val saved = java.io.File(added.url)
            assertEquals(bytes.size.toLong(), saved.length())
            assertEquals(entries, app.falak.player.data.M3uParser.parse(saved.bufferedReader()).channels.size)

            // Not a playlist: refused, nothing kept.
            http.newCall(
                Request.Builder().url("$base/api/playlists/upload").header("X-Code", server.code)
                    .post("hello".toRequestBody("text/plain".toMediaType())).build(),
            ).execute().use {
                assertEquals(400, it.code)
                assertTrue(it.body!!.string().contains("isn't an M3U playlist"))
            }
            assertEquals(before + 1, repo.state.value.playlists.size)

            // Wrong code: refused before anything is written.
            http.newCall(
                Request.Builder().url("$base/api/playlists/upload").header("X-Code", "nope")
                    .post("#EXTM3U\n".toRequestBody("text/plain".toMediaType())).build(),
            ).execute().use { assertEquals(403, it.code) }
            assertEquals(before + 1, repo.state.value.playlists.size)
            repo.removePlaylist(added.id)
        } finally {
            server.stop()
        }
    }
}
