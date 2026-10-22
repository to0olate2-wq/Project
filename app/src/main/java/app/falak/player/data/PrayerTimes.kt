package app.falak.player.data

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * The five daily prayer times, worked out on the device (no internet) from where the user is
 * (the time zone's city, as for the sunrise theme) with the usual astronomical method
 * (praytimes.org): the sun's position for the day, Fajr and Isha at a sun angle below the horizon
 * (or Isha a set time after Maghrib), Asr by the shadow length.
 */
object PrayerTimes {
    /** A calculation method: Fajr angle, and Isha as an angle or minutes after Maghrib. */
    enum class Method(val key: String, val label: String, val fajr: Double, val isha: Double = 0.0, val ishaMinutes: Int = 0) {
        UAE("uae", "UAE (Awqaf)", 18.2, isha = 18.2),
        UMM_AL_QURA("uqura", "Umm al-Qura (Makkah)", 18.5, ishaMinutes = 90),
        MWL("mwl", "Muslim World League", 18.0, isha = 17.0),
        EGYPT("egypt", "Egyptian Authority", 19.5, isha = 17.5),
        KARACHI("karachi", "Karachi", 18.0, isha = 18.0),
        ISNA("isna", "North America (ISNA)", 15.0, isha = 15.0),
        QATAR("qatar", "Qatar", 18.0, ishaMinutes = 90),
        KUWAIT("kuwait", "Kuwait", 18.0, isha = 17.5),
        TURKEY("turkey", "Turkey (Diyanet)", 18.0, isha = 17.0),
        ;

        companion object {
            fun of(key: String): Method? = entries.firstOrNull { it.key == key }
        }
    }

    /** One prayer at [minute] minutes after local midnight. */
    data class Prayer(val name: String, val minute: Int)

    val NAMES = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")

    /** The method most used where the time zone is (Settings can change it). */
    fun defaultMethod(zone: String): Method = when (zone) {
        "Asia/Dubai", "Asia/Muscat" -> Method.UAE
        "Asia/Riyadh", "Asia/Aden" -> Method.UMM_AL_QURA
        "Asia/Qatar", "Asia/Bahrain" -> Method.QATAR
        "Asia/Kuwait" -> Method.KUWAIT
        "Africa/Cairo" -> Method.EGYPT
        "Asia/Karachi", "Asia/Kolkata", "Asia/Calcutta", "Asia/Dhaka" -> Method.KARACHI
        "Europe/Istanbul" -> Method.TURKEY
        else -> if (zone.startsWith("America/")) Method.ISNA else Method.MWL
    }

    /** Hanafi Asr (shadow twice the length) is the custom in South Asia. */
    fun defaultHanafi(zone: String): Boolean = zone in setOf("Asia/Karachi", "Asia/Kolkata", "Asia/Calcutta", "Asia/Dhaka")

    /** The five prayers for [day] (local date), in order. */
    fun forDay(day: Calendar, lat: Double, lon: Double, method: Method, hanafi: Boolean): List<Prayer> {
        val y = day.get(Calendar.YEAR)
        val m = day.get(Calendar.MONTH) + 1
        val d = day.get(Calendar.DAY_OF_MONTH)
        val noon = (day.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
        }
        val tz = day.timeZone.getOffset(noon.timeInMillis) / 3_600_000.0
        val jd = julian(y, m, d) - lon / (15 * 24)

        fun sun(t: Double): Pair<Double, Double> = sunPosition(jd + t)
        fun midDay(t: Double) = fixHour(12 - sun(t).second)
        fun angleTime(angle: Double, t: Double, before: Boolean): Double {
            val decl = sun(t).first
            val noonT = midDay(t)
            val x = (-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))
            val h = darccos(x.coerceIn(-1.0, 1.0)) / 15
            return if (before) noonT - h else noonT + h
        }
        fun asrTime(t: Double): Double {
            val decl = sun(t).first
            val angle = -darccot((if (hanafi) 2.0 else 1.0) + dtan(abs(lat - decl)))
            return angleTime(angle, t, before = false)
        }

        val shift = tz - lon / 15
        val fajr = angleTime(method.fajr, 5 / 24.0, before = true) + shift
        val dhuhr = midDay(12 / 24.0) + shift + 1 / 60.0 // a minute after noon, as timetables do
        val asr = asrTime(13 / 24.0) + shift
        val maghrib = angleTime(0.833, 18 / 24.0, before = false) + shift
        val isha = if (method.ishaMinutes > 0) maghrib + method.ishaMinutes / 60.0 else angleTime(method.isha, 18 / 24.0, before = false) + shift
        return listOf(fajr, dhuhr, asr, maghrib, isha).mapIndexed { i, h -> Prayer(NAMES[i], (h * 60).roundToInt().mod(24 * 60)) }
    }

    /** "5:45 PM" style label for minutes after midnight, in the device's 12/24-hour format. */
    fun label(minute: Int, format: java.text.DateFormat): String {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minute / 60)
            set(Calendar.MINUTE, minute % 60)
        }
        return format.format(c.time)
    }

    // ---- astronomy (degrees) ----

    private fun dsin(d: Double) = sin(Math.toRadians(d))
    private fun dcos(d: Double) = cos(Math.toRadians(d))
    private fun dtan(d: Double) = tan(Math.toRadians(d))
    private fun darcsin(x: Double) = Math.toDegrees(asin(x))
    private fun darccos(x: Double) = Math.toDegrees(acos(x))
    private fun darctan2(y: Double, x: Double) = Math.toDegrees(atan2(y, x))
    private fun darccot(x: Double) = Math.toDegrees(atan(1 / x))
    private fun fix(a: Double, b: Double): Double {
        val r = a - b * floor(a / b)
        return if (r < 0) r + b else r
    }
    private fun fixHour(h: Double) = fix(h, 24.0)

    private fun julian(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    /** The sun's declination and the equation of time (hours) for a Julian date. */
    private fun sunPosition(jd: Double): Pair<Double, Double> {
        val d = jd - 2451545.0
        val g = fix(357.529 + 0.98560028 * d, 360.0)
        val q = fix(280.459 + 0.98564736 * d, 360.0)
        val l = fix(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g), 360.0)
        val e = 23.439 - 0.00000036 * d
        val ra = darctan2(dcos(e) * dsin(l), dcos(l)) / 15
        val eqt = q / 15 - fixHour(ra)
        val decl = darcsin(dsin(e) * dsin(l))
        return decl to eqt
    }

    /** The zone's default when no method is chosen. */
    fun methodFor(key: String, zone: TimeZone = TimeZone.getDefault()): Method = Method.of(key) ?: defaultMethod(zone.id)
}
