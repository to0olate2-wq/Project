package app.falak.player.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.falak.player.data.Handoff
import app.falak.player.data.LinkAnswer
import app.falak.player.data.LinkAsk
import app.falak.player.data.Repository
import app.falak.player.data.SyncMerge

/**
 * What a linked device asks this one (8.42), on AutoSync's thread: what plays here ("now", for the
 * phone's second screen), play something handed over ("play"), press a key ("key", the second
 * screen's remote), or close the player ("stop": the phone took it over). Answers at once; playing happens on the main thread.
 */
object LinkCommands {
    fun answer(context: Context, repo: Repository, ask: LinkAsk): LinkAnswer = when (ask.op) {
        "now" -> LinkAnswer(true, now = repo.handNow)
        "play" -> play(context, repo, ask)
        "key" -> {
            val code = RemoteServer.KEY_CODES[ask.key]
            if (code == null || ask.key !in Handoff.KEYS) {
                LinkAnswer(false, "Unknown key")
            } else {
                Thread({ RemoteServer.pressKey(code) }, "link-key").start()
                LinkAnswer(true)
            }
        }
        "stop" -> {
            // Taken over by the phone ("Play it on this phone"): the player here closes.
            Handler(Looper.getMainLooper()).post {
                (RemoteBridge.activity?.get() as? app.falak.player.ui.PlayerActivity)?.let { if (!it.isFinishing) it.finish() }
            }
            LinkAnswer(true)
        }
        else -> LinkAnswer(false, "Update The Player on both devices")
    }

    private fun play(context: Context, repo: Repository, ask: LinkAsk): LinkAnswer {
        val item = ask.item ?: return LinkAnswer(false, "Nothing to play")
        val h = RemoteBridge.handler ?: return LinkAnswer(false, "Open The Player there first")
        val s = repo.state.value
        // The same playlist here (its id may differ: linked devices know it by its sync id).
        val pl = s.playlists.firstOrNull { SyncMerge.syncId(s, it.id) == item.playlist }
            ?: return LinkAnswer(false, "That playlist isn't on ${deviceName(context)} yet: sync first")
        Handler(Looper.getMainLooper()).post { h.handOff(pl.id, item) }
        return LinkAnswer(true, "Playing")
    }
}
