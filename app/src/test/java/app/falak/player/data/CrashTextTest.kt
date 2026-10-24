package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashTextTest {
    private val crash = """
        The Player 6.8 · Android 14 (SDK 34) · Google Pixel 8

        java.lang.RuntimeException: Unable to start activity ComponentInfo{app.falak.player/app.falak.player.ui.PlayerActivity}
        	at android.app.ActivityThread.performLaunchActivity(ActivityThread.java:3782)
        	at android.app.ActivityThread.handleLaunchActivity(ActivityThread.java:3922)
        Caused by: androidx.media3.datasource.HttpDataSource${'$'}InvalidResponseCodeException: Response code: 403 for http://my.provider.tv:8080/live/ahmed/s3cret/1234.ts?token=abc
        	at androidx.media3.datasource.DefaultHttpDataSource.open(DefaultHttpDataSource.java:412)
        	at app.falak.player.ui.PlayerActivity${'$'}play${'$'}1.invoke(PlayerActivity.kt:775)
        	at app.falak.player.ui.PlayerActivity.play(PlayerActivity.kt:770)
        	... 12 more

        Last steps:
        12:01:02  app started
        12:01:09  play live with exo
    """.trimIndent()

    @Test
    fun personalDetailsAreRemoved() {
        val out = CrashText.scrub(
            crash + "\nUnable to resolve host \"secret.example\": No address\nuser ahmed@mail.com at 192.168.1.20:8000 " +
                "and fe80:0:0:0:200:f8ff:fe21:67cf\nfile /storage/emulated/0/Download/my list.m3u " +
                "username=ahmed&password=pw\ntoken a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8 at provider.example.com:25461",
        )
        for (secret in listOf("my.provider.tv", "ahmed", "s3cret", "1234.ts", "token=abc", "secret.example", "mail.com",
            "192.168", "fe80", "Download", "password=pw", "a1b2c3d4e5f6", "provider.example.com", "25461")) {
            assertFalse("still has $secret:\n$out", out.contains(secret))
        }
        // What's useful stays: the error, the app's code and line numbers, the steps, the device.
        for (kept in listOf("InvalidResponseCodeException: Response code: 403", "PlayerActivity.kt:775", "ActivityThread.java:3782",
            "play live with exo", "Google Pixel 8", "Android 14 (SDK 34)", "The Player 6.8")) {
            assertTrue("lost $kept:\n$out", out.contains(kept))
        }
    }

    @Test
    fun crashSignatureNamesTheRootCauseAndAppCode() {
        assertEquals("crash", CrashText.type(crash))
        assertEquals(
            "HttpDataSource\$InvalidResponseCodeException in PlayerActivity\$play.invoke (PlayerActivity.kt)",
            CrashText.signature("crash", crash),
        )
    }

    @Test
    fun otherKindsOfStop() {
        val anr = "The Player 6.8\n\nStopped responding, so Android closed it (ANR)\n\"main\" prio=5 tid=1 Blocked\n" +
            "  at java.lang.Object.wait(Native method)\n  at app.falak.player.data.Epg.load(Epg.kt:88)\n"
        assertEquals("anr", CrashText.type(anr))
        assertEquals("Not responding in Epg.load (Epg.kt)", CrashText.signature("anr", anr))

        val freeze = "Screen froze for 9+ s (still frozen)\nLast steps:\n12:00:01  app started\n\nWhere it was stuck (after 4 s):\n" +
            "  android.os.MessageQueue.nativePollOnce(Native Method)\n  app.falak.player.ui.LibraryScreenKt${'$'}LibraryScreen${'$'}2.invoke(LibraryScreen.kt:170)\n"
        assertEquals("freeze", CrashText.type(freeze))
        assertEquals("Froze in LibraryScreenKt\$LibraryScreen.invoke (LibraryScreen.kt)", CrashText.signature("freeze", freeze))

        val native = "Crashed inside the video engine or Android's media code (native crash)\nCrash details:\nSIGSEGV\n/data/app/x/lib/arm64/libvlc.so\n"
        assertEquals("native", CrashText.type(native))
        assertEquals("Engine crash SIGSEGV in libvlc.so", CrashText.signature("native", native))

        assertEquals("memory", CrashText.type("Closed by Android to free memory while on screen"))
        assertEquals("other", CrashText.type("Stopped by Android (signal 9)"))
        assertEquals("Stopped by Android (signal 9)", CrashText.signature("other", "x\nStopped by Android (signal 9)\n"))
    }
}
