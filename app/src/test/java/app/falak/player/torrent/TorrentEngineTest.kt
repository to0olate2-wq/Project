package app.falak.player.torrent

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentInfo

/**
 * The real thing, on the build machine (Linux): one libtorrent session shares a made-up 12 MB
 * "film", and the app's engine fetches it from a link (a hash and nothing else), picks the film
 * out of it and feeds it to a player through the local server while it arrives: the end first,
 * a jump to the middle, then the whole film. Nothing leaves the machine (no trackers, no DHT).
 */
class TorrentEngineTest {
    private val linux = System.getProperty("os.name").orEmpty().lowercase().contains("linux")

    /** The library's Linux part comes as a file inside a jar: taken out and pointed to. */
    private fun loadNative(): Boolean {
        if (System.getProperty("libtorrent4j.jni.path").orEmpty().isNotEmpty()) return true
        val out = File.createTempFile("libtorrent4j", ".so").apply { deleteOnExit() }
        val loader = javaClass.classLoader!!
        for (name in listOf("lib/x86_64/libtorrent4j.so", "lib/libtorrent4j.so")) {
            loader.getResourceAsStream(name)?.use { i ->
                out.outputStream().use { o -> i.copyTo(o) }
                System.setProperty("libtorrent4j.jni.path", out.absolutePath)
                return true
            }
        }
        for (entry in System.getProperty("java.class.path").orEmpty().split(File.pathSeparator)) {
            if (!entry.contains("libtorrent4j-linux") || !File(entry).isFile) continue
            java.util.zip.ZipFile(entry).use { zip ->
                val so = zip.entries().asSequence().firstOrNull { it.name.endsWith("libtorrent4j.so") }
                if (so != null) {
                    zip.getInputStream(so).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                    System.setProperty("libtorrent4j.jni.path", out.absolutePath)
                    return true
                }
            }
        }
        return false
    }

    private fun <T : Any> await(seconds: Int, check: () -> T?): T? {
        val until = System.nanoTime() + seconds * 1_000_000_000L
        while (System.nanoTime() < until) {
            check()?.let { return it }
            Thread.sleep(100)
        }
        return null
    }

    /** What a folder really takes on the disk (a film's file has holes where nothing was fetched). */
    private fun onDisk(folder: File): Long {
        val p = ProcessBuilder("du", "-sk", folder.absolutePath).redirectErrorStream(true).start()
        val kb = p.inputStream.bufferedReader().readText().trim().substringBefore('\t').substringBefore(' ').toLong()
        p.waitFor()
        return kb * 1024
    }

    private fun get(url: String, range: String?): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 60_000
        if (range != null) c.setRequestProperty("Range", range)
        return c.inputStream.use { it.readBytes() }
    }

    @Test
    fun fetchesAFilmFromALinkAndFeedsThePlayer() {
        assumeTrue("Runs on the build machine (Linux)", linux)
        assertTrue("libtorrent4j's Linux library is on the test classpath", loadNative())
        // (Several times over when hunting something that only happens now and then: see build.yml.)
        repeat(System.getenv("TORRENT_TEST_REPEAT")?.toIntOrNull() ?: 1) { once() }
    }

    private fun once() {
        val root = Files.createTempDirectory("torrent-test").toFile()
        val seedDir = File(root, "seed").apply { mkdirs() }
        val folder = File(seedDir, "Film.2024.1080p").apply { mkdirs() }
        val data = ByteArray(12 * 1024 * 1024).also { java.util.Random(7).nextBytes(it) }
        // A torrent as they come: the film, a small sample and a text file.
        File(folder, "Film.2024.1080p.mkv").writeBytes(data)
        File(folder, "sample.mkv").writeBytes(ByteArray(300_000) { 1 })
        File(folder, "readme.txt").writeText("hello")
        val info = TorrentInfo.bdecode(TorrentBuilder().path(folder).pieceSize(64 * 1024).flags(TorrentBuilder.V1_ONLY).generate().entry().bencode())

        val seeder = SessionManager(false)
        val engine = TorrentEngine(File(root, "fetched"))
        try {
            val sp = SettingsPack().listenInterfaces("127.0.0.1:46881")
            sp.setEnableDht(false)
            sp.setEnableLsd(false)
            seeder.start(SessionParams(sp))
            seeder.download(info, seedDir)
            assertNotNull("the sharing side has the film", await(40) { seeder.find(info.infoHash())?.takeIf { it.status().isSeeding } })

            engine.start(listen = "127.0.0.1:46882", findPeers = false)
            val hash = info.infoHash().toHex()
            val file = engine.open(TorrentSource(hash), timeoutMs = 60_000, peer = TcpEndpoint("127.0.0.1", 46881))
            // The film is picked, not the sample or the text file.
            assertEquals("Film.2024.1080p.mkv", file.name)
            assertEquals(data.size.toLong(), file.size)

            TorrentServer(file).use { server ->
                val size = data.size
                assertArrayEquals("the end", data.copyOfRange(size - 70_000, size), get(server.url, "bytes=${size - 70_000}-"))
                assertArrayEquals("a jump to the middle", data.copyOfRange(5_000_000, 6_000_000), get(server.url, "bytes=5000000-5999999"))
                assertArrayEquals("the whole film", data, get(server.url, null))
            }
            assertTrue(file.startReady())
            assertTrue(file.endReady())
            assertEquals(1f, file.startProgress(), 0.001f)
            assertEquals("room for all of it: never started afresh", 0, file.generation)
            engine.close(file)
            assertNotNull("deleted when closed", await(20) { if (File(root, "fetched").walkTopDown().none { it.isFile && it.length() > 100_000 }) true else null })

            // A film bigger than the room for it (3 MB here): it still plays from end to end, the
            // older parts making room as it goes, and a jump back fetches that part again.
            val tight = engine.open(TorrentSource(hash), maxBytes = 3L * 1024 * 1024, timeoutMs = 60_000, peer = TcpEndpoint("127.0.0.1", 46881))
            // Left alone, it fetches the start and the end and stops there (not the whole film).
            Thread.sleep(1_500)
            val atRest = onDisk(File(root, "fetched"))
            assertTrue("only a stretch is fetched before the player asks (it is $atRest bytes)", atRest <= 3L * 1024 * 1024)
            TorrentServer(tight).use { server ->
                assertArrayEquals("the whole film through a small room", data, get(server.url, null))
                assertTrue("it was started afresh to make room (${tight.generation} times)", tight.generation >= 2)
                assertArrayEquals("a jump back", data.copyOfRange(1_000_000, 1_500_000), get(server.url, "bytes=1000000-1499999"))
                val kept = onDisk(File(root, "fetched"))
                assertTrue("never more on the device than the room (it is $kept bytes)", kept <= 4L * 1024 * 1024)
            }
            engine.close(tight)

            // Asked for by name (a season pack names the episode's file).
            val sample = engine.open(TorrentSource(hash, fileName = "sample.mkv"), timeoutMs = 60_000, peer = TcpEndpoint("127.0.0.1", 46881))
            assertEquals("sample.mkv", sample.name)
            TorrentServer(sample).use { server ->
                assertArrayEquals("a small file plays too", ByteArray(300_000) { 1 }, get(server.url, null))
            }
            engine.close(sample)
        } finally {
            engine.stop()
            seeder.stop()
            root.deleteRecursively()
        }
    }
}
