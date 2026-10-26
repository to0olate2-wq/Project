package app.falak.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.falak.player.data.PrayerTimes
import app.falak.player.data.Repository
import app.falak.player.data.SunTimes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar

/**
 * Prayer time reminders while the app is open (Settings → Prayer times, off by default): at each
 * prayer a banner shows for a couple of minutes, and the player can pause for it.
 */
object PrayerAlerts {
    /** A prayer that has just come in: its name and time ("Maghrib", "5:45 PM"). */
    data class Alert(val name: String, val time: String, val at: Long)

    private val _current = MutableStateFlow<Alert?>(null)
    val current: StateFlow<Alert?> = _current.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var next: Runnable? = null
    private val clear = Runnable { _current.value = null }

    /** (Re)plans the next reminder; nothing when they are off. Main thread. */
    fun schedule(context: Context, repo: Repository, visible: Boolean) {
        next?.let { main.removeCallbacks(it) }
        next = null
        val s = repo.state.value.settings
        if (!visible || s.prayerMode == "off") {
            dismiss()
            return
        }
        val (at, prayer) = nextPrayer(context, repo) ?: return
        val r = Runnable {
            val fmt = android.text.format.DateFormat.getTimeFormat(context)
            _current.value = Alert(prayer.name, PrayerTimes.label(prayer.minute, fmt), at)
            main.removeCallbacks(clear)
            main.postDelayed(clear, SHOW_MS)
            schedule(context, repo, visible = true)
        }
        next = r
        main.postDelayed(r, (at - System.currentTimeMillis()).coerceAtLeast(0))
    }

    /** Screenshot tests: a reminder on screen. */
    @androidx.annotation.VisibleForTesting
    fun showForTest(alert: Alert?) {
        _current.value = alert
    }

    fun dismiss() {
        main.removeCallbacks(clear)
        _current.value = null
    }

    /** Today's five prayers where the user is, with the chosen (or local) method. */
    fun today(context: Context, repo: Repository, day: Calendar = Calendar.getInstance()): List<PrayerTimes.Prayer> {
        val s = repo.state.value.settings
        val place = SunTimes.place(context, day.timeZone)
        val method = PrayerTimes.methodFor(s.prayerMethod, day.timeZone)
        val hanafi = when (s.prayerAsr) {
            "hanafi" -> true
            "std" -> false
            else -> PrayerTimes.defaultHanafi(day.timeZone.id)
        }
        return PrayerTimes.forDay(day, place.lat, place.lon, method, hanafi)
    }

    private fun nextPrayer(context: Context, repo: Repository): Pair<Long, PrayerTimes.Prayer>? {
        val now = System.currentTimeMillis()
        for (offset in 0..1) {
            val day = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }
            for (p in today(context, repo, day)) {
                val at = (day.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, p.minute / 60)
                    set(Calendar.MINUTE, p.minute % 60)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                if (at > now) return at to p
            }
        }
        return null
    }

    private const val SHOW_MS = 2 * 60_000L
}
