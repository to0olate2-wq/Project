package app.falak.player

import android.app.Application
import android.content.Context
import android.os.Build
import android.app.ActivityManager
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import app.falak.player.data.Repository
import app.falak.player.sync.AutoSync
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File

class FalakApp : Application(), ImageLoaderFactory {
    lateinit var repo: Repository
        private set

    /** Sync with linked devices; runs only while the app is on screen and sync is on. */
    val autoSync: AutoSync by lazy { AutoSync(this, repo) }
    private var visible = 0
    private val main = Handler(Looper.getMainLooper())
    private val stopSync = Runnable { refreshSync() }

    override fun newImageLoader(): ImageLoader = imageLoaderFor(this)

    override fun onCreate() {
        super.onCreate()
        repo = Repository(this)
        watchForSync()
        FreezeWatch.start(filesDir)
        FreezeWatch.note("app started")

        // Save any crash so the next launch can show it (there is no other way to see it on a phone).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val header = "The Player ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} " +
                    "(SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n\n"
                File(filesDir, Repository.CRASH_FILE).writeText(
                    header + error.stackTraceToString().take(8000) + "\n\nLast steps:\n" + FreezeWatch.recent(),
                )
            } catch (t: Throwable) {
                // nothing else we can do
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun watchForSync() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                visible++
                main.removeCallbacks(stopSync)
                refreshSync()
            }

            override fun onActivityStopped(activity: Activity) {
                visible = (visible - 1).coerceAtLeast(0)
                // Not straight away: going from the lists to the player stops one screen as the next starts.
                if (visible == 0) main.postDelayed(stopSync, 30_000)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        // Switched on or off, linked or unlinked.
        MainScope().launch {
            repo.state.map { it.settings.syncOn to it.sync.key }.distinctUntilChanged().collect { refreshSync() }
        }
    }

    private fun refreshSync() {
        val s = repo.state.value
        if (visible > 0 && s.settings.syncOn && s.sync.key.isNotEmpty()) {
            autoSync.start(s.sync.key)
        } else if (autoSync.runningKey != null) {
            autoSync.stop()
        }
    }
}

/**
 * Pictures (logos, posters) are the app's biggest memory user. Keep the in-memory cache small and
 * fixed (TV boxes have little RAM shared with video decoding), keep a disk cache, and cache logos
 * even when the provider says not to, so lists don't re-download them on every scroll.
 */
private fun imageLoaderFor(app: Application): ImageLoader {
    val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val share = if (am.isLowRamDevice) 0.10 else 0.15
    val bytes = (am.memoryClass * 1024.0 * 1024.0 * share).toLong().coerceIn(16L shl 20, 64L shl 20).toInt()
    return ImageLoader.Builder(app)
        .memoryCache { MemoryCache.Builder(app).maxSizeBytes(bytes).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(app.cacheDir.resolve("images"))
                .maxSizeBytes(250L * 1024 * 1024)
                .build()
        }
        .allowRgb565(true)
        .respectCacheHeaders(false)
        .build()
}

val Context.repo: Repository
    get() = (applicationContext as FalakApp).repo

val Context.autoSync: AutoSync
    get() = (applicationContext as FalakApp).autoSync
