package app.falak.player

import android.app.Application
import android.content.Context
import android.os.Build
import app.falak.player.data.Repository
import java.io.File

class FalakApp : Application() {
    lateinit var repo: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        repo = Repository(this)

        // Save any crash so the next launch can show it (there is no other way to see it on a phone).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val header = "Falak ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} " +
                    "(SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n\n"
                File(filesDir, Repository.CRASH_FILE).writeText(header + error.stackTraceToString().take(8000))
            } catch (t: Throwable) {
                // nothing else we can do
            }
            previous?.uncaughtException(thread, error)
        }
    }
}

val Context.repo: Repository
    get() = (applicationContext as FalakApp).repo
