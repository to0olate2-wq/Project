package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks the real introdb.app, as the player does, and reads its answers with [SkipSegments.parse]:
 * the check that the app and the list still speak the same language. Only when asked for
 * (LIVE_CHECKS is set by a "[only …]" run on CI): the everyday tests must not depend on a site
 * being up.
 */
class SkipSegmentsLiveTest {
    private fun ask(videoId: String): Pair<Int, String> {
        val c = URL(SkipSegments.url(videoId)).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "ThePlayer/test")
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        return code to body
    }

    @Test
    fun theRealListAnswersAsTheAppReadsIt() {
        assumeTrue("Only when asked for", System.getenv("LIVE_CHECKS") != null)
        // Well-known series (first episodes), and a film.
        val asked = listOf("tt0903747:1:1", "tt0944947:1:1", "tt4574334:1:1", "tt0108778:1:2", "tt0371746")
        var intros = 0
        var parts = 0
        for (id in asked) {
            val (code, body) = ask(id)
            val read = SkipSegments.parse(body)
            println("LIVE $id -> HTTP $code")
            println("LIVE   answer: ${body.take(600)}")
            println("LIVE   read as: $read")
            assertEquals("HTTP for $id", 200, code)
            parts += read.size
            if (read.any { it.kind == SkipSegments.Kind.INTRO }) intros++
            // What was read is what the answer says (the first intro, to the millisecond).
            Regex("\"intro\"\\s*:\\s*\\{[^}]*\"start_ms\"\\s*:\\s*(\\d+)[^}]*\"end_ms\"\\s*:\\s*(\\d+)").find(body)?.let { m ->
                val intro = read.first { it.kind == SkipSegments.Kind.INTRO }
                assertEquals(m.groupValues[1].toLong(), intro.start)
                assertEquals(m.groupValues[2].toLong(), intro.end)
            }
            Thread.sleep(1_200)
        }
        println("LIVE total: $parts parts, $intros of ${asked.size} with an intro")
        assertTrue("None of these well-known series has an intro in the answers: the format has changed?", intros >= 1)
    }
}
