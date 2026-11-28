package app.falak.player

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.falak.player.data.Playlist
import app.falak.player.data.SourceType
import app.falak.player.ui.MainActivity

/**
 * Device checks in CI (the "bench" build only): adds the test server's Xtream playlist, marks this
 * version as seen (no welcome, guide or "what's new" over the pages being checked) and opens the app.
 *
 *   adb shell am start -n app.falak.player/.BenchSeedActivity --es server http://10.0.2.2:8080 --es user u --es pass p
 */
class BenchSeedActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = (application as FalakApp).repo
        val server = intent.getStringExtra("server").orEmpty()
        val user = intent.getStringExtra("user").orEmpty()
        val pass = intent.getStringExtra("pass").orEmpty()
        val look = intent.getStringExtra("look")
        if (server.isNotBlank() && repo.state.value.playlists.none { it.url == server }) {
            repo.addPlaylist(Playlist("bench", "Test server", SourceType.XTREAM, server, user, pass, "", ""))
        }
        repo.updateSettings { s ->
            s.copy(seenVersion = BuildConfig.VERSION_NAME).let { if (look != null) it.copy(appLook = look) else it }
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
