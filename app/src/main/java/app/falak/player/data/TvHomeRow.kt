package app.falak.player.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import app.falak.player.R
import app.falak.player.ui.MainActivity
import java.util.concurrent.Executors

/**
 * Android TV home screen (8.35): a row of the app's own, "The Player", with your favorite live
 * channels and then the ones watched lately; picking one plays it. Written when the app goes to
 * the background, so the row matches what you just did. Kids mode, locked, hidden and adult
 * groups never show there; with kids mode on the row is emptied. Nothing on phones or on TVs older
 * than Android 8 (the same check as [WatchNext]).
 */
object TvHomeRow {
    /** Intent extra: "playlistId|channelId" to play. */
    const val EXTRA_PLAY = "app.falak.player.PLAY_CHANNEL"
    private const val MAX = 12
    private const val TAG = "TvHomeRow"
    private const val PREFS = "tv_home_row"

    private val worker = Executors.newSingleThreadExecutor()

    /** One channel for the row: what the launcher shows. */
    data class Item(val key: String, val name: String, val logo: String, val now: String)

    /**
     * Favorites first, then recents; live channels only, each once, none from [excluded] groups
     * or adult-looking groups, at most [MAX]. (No Android in it: tested.)
     */
    fun pick(favorites: List<Channel>, recents: List<Channel>, excluded: Set<String>): List<Channel> {
        val seen = HashSet<String>()
        return (favorites + recents).filter {
            it.kind == Kind.LIVE && it.group !in excluded && !ADULT_GROUP.containsMatchIn(it.group) && seen.add(it.id)
        }.take(MAX)
    }

    /** Puts [items] in the row (an empty list empties it). Off the main thread; errors are only logged. */
    fun update(context: Context, items: List<Item>) {
        if (!WatchNext.supported(context)) return
        val app = context.applicationContext
        worker.execute {
            try {
                write(app, items)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't update the home screen channel", e)
            }
        }
    }

    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.O)
    private fun write(context: Context, items: List<Item>) {
        val helper = PreviewChannelHelper(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var id = prefs.getLong("channel", -1L)
        // Removed by the system (app data kept, launcher reset…): make it again.
        if (id >= 0 && runCatching { helper.getPreviewChannel(id) }.getOrNull() == null) id = -1L
        if (id < 0) {
            if (items.isEmpty()) return
            val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val logo = ContextCompat.getDrawable(context, R.drawable.ic_launcher)?.toBitmap(160, 160) ?: return
            val channel = PreviewChannel.Builder()
                .setDisplayName("The Player")
                .setDescription("Your favorite and recent channels")
                .setAppLinkIntentUri(Uri.parse(open.toUri(Intent.URI_INTENT_SCHEME)))
                .setInternalProviderId("channels")
                .setLogo(logo)
                .build()
            // The app's first channel is shown on the home screen without asking (Android 8+).
            id = helper.publishDefaultChannel(channel)
            if (id < 0) return
            prefs.edit().putLong("channel", id).apply()
        }
        // Drop what's there, then write the new list in order (the launcher keeps that order).
        val uri = TvContractCompat.buildPreviewProgramsUriForChannel(id)
        val old = ArrayList<Long>()
        context.contentResolver.query(uri, arrayOf(TvContractCompat.PreviewPrograms._ID), null, null, null)?.use { c ->
            while (c.moveToNext()) old += c.getLong(0)
        }
        old.forEach { helper.deletePreviewProgram(it) }
        items.forEachIndexed { i, item ->
            val play = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(EXTRA_PLAY, item.key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val b = PreviewProgram.Builder()
                .setChannelId(id)
                .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                .setTitle(item.name)
                .setInternalProviderId(item.key)
                .setWeight(MAX - i)
                .setIntentUri(Uri.parse(play.toUri(Intent.URI_INTENT_SCHEME)))
                .setLive(true)
            if (item.now.isNotBlank()) b.setDescription(item.now)
            if (item.logo.startsWith("http")) {
                b.setPosterArtUri(Uri.parse(item.logo))
                    .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_1_1)
            }
            helper.publishPreviewProgram(b.build())
        }
    }
}
