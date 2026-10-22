package app.falak.player.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import app.falak.player.ui.MainActivity
import java.util.concurrent.Executors

/**
 * Android TV home screen: part-watched films and episodes appear in the system's
 * "Continue watching" / "Play next" row, and picking one there resumes it in the app.
 * Nothing happens on phones or on TVs older than Android 8.
 */
object WatchNext {
    /** Intent extra: the progress key ("playlistId|itemId") to resume. */
    const val EXTRA_RESUME = "app.falak.player.RESUME"
    private const val MAX_ITEMS = 12
    private const val TAG = "WatchNext"

    // One at a time and in order, off the main thread.
    private val worker = Executors.newSingleThreadExecutor()

    fun supported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /**
     * Puts [item] (a film or an episode, with its name and artwork) in the row at [pos] of [dur];
     * pos = 0 (finished or barely started) or [allowed] = false (a hidden or locked group, kids
     * mode) takes it out.
     */
    fun update(context: Context, key: String, item: Channel, pos: Long, dur: Long, allowed: Boolean) {
        if (!supported(context)) return
        val app = context.applicationContext
        worker.execute {
            try {
                if (pos <= 0 || !allowed) remove(app, key) else upsert(app, key, item, pos, dur)
            } catch (e: Exception) {
                // The home screen is a nicety: never let it disturb playback.
                Log.w(TAG, "Couldn't update the home screen row", e)
            }
        }
    }

    /** Empties the row of everything from this app (kids mode switched on, playlist removed…). */
    fun clear(context: Context, playlistId: String? = null) {
        if (!supported(context)) return
        val app = context.applicationContext
        worker.execute {
            try {
                for (p in mine(app)) {
                    val key = p.internalProviderId.orEmpty()
                    if (playlistId == null || key.startsWith("$playlistId|")) delete(app, p.id)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't clear the home screen row", e)
            }
        }
    }

    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.O)
    private fun mine(context: Context): List<WatchNextProgram> {
        val out = ArrayList<WatchNextProgram>()
        context.contentResolver.query(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            WatchNextProgram.PROJECTION,
            null,
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) out += WatchNextProgram.fromCursor(c)
        }
        return out
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun delete(context: Context, id: Long) {
        context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun remove(context: Context, key: String) {
        mine(context).filter { it.internalProviderId == key }.forEach { delete(context, it.id) }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun upsert(context: Context, key: String, item: Channel, pos: Long, dur: Long) {
        val all = mine(context)
        val existing = all.firstOrNull { it.internalProviderId == key }
        // Taken off the row by the user: leave it off.
        if (existing != null && !existing.isBrowsable) return
        val open = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_RESUME, key)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val builder = WatchNextProgram.Builder()
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
            .setType(
                if (item.kind == Kind.SERIES) TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE
                else TvContractCompat.WatchNextPrograms.TYPE_MOVIE,
            )
            .setTitle(item.name)
            .setLastPlaybackPositionMillis(pos.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .setDurationMillis(dur.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .setInternalProviderId(key)
            .setIntentUri(Uri.parse(open.toUri(Intent.URI_INTENT_SCHEME)))
        if (item.logo.isNotBlank()) {
            builder.setPosterArtUri(Uri.parse(item.logo))
                .setPosterArtAspectRatio(TvContractCompat.WatchNextPrograms.ASPECT_RATIO_MOVIE_POSTER)
        }
        val values = builder.build().toContentValues()
        if (existing != null) {
            context.contentResolver.update(TvContractCompat.buildWatchNextProgramUri(existing.id), values, null, null)
        } else {
            context.contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)
            // Keep the row short: drop the ones watched longest ago.
            val extra = all.size + 1 - MAX_ITEMS
            if (extra > 0) all.sortedBy { it.lastEngagementTimeUtcMillis }.take(extra).forEach { delete(context, it.id) }
        }
    }
}
