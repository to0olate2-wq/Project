package app.falak.player.data

import android.content.Context
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sunrise and sunset where the device is, worked out from its time zone (no location permission):
 * every time zone has a home city in the public tz database (Asia/Dubai → Dubai, 25.3° N 55.3° E),
 * close enough for "light by day, dark from sunset". Uses the classic almanac formula (about a
 * minute of error), so the automatic theme switches at the real sunset each day of the year.
 */
object SunTimes {
    /** Where the times are for: the time zone's city and its coordinates. */
    data class Place(val zone: String, val city: String, val lat: Double, val lon: Double, val guessed: Boolean)

    /** Minutes after local midnight; null on a polar day or night. */
    data class Day(val sunrise: Int?, val sunset: Int?)

    @Volatile private var table: Map<String, Pair<Double, Double>>? = null

    private fun table(context: Context): Map<String, Pair<Double, Double>> = table ?: synchronized(this) {
        table ?: try {
            context.applicationContext.assets.open("zones.txt").bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { l ->
                    val p = l.split(' ')
                    if (p.size < 3) null else p[0] to (p[1].toDouble() to p[2].toDouble())
                }.toMap()
            }
        } catch (e: Exception) {
            emptyMap()
        }.also { table = it }
    }

    fun place(context: Context, zone: TimeZone = TimeZone.getDefault()): Place {
        val id = zone.id
        val known = table(context)[id]
        if (known != null) {
            val city = id.substringAfterLast('/').replace('_', ' ')
            return Place(id, city, known.first, known.second, guessed = false)
        }
        // Unknown or plain offsets (UTC, GMT+4…): the longitude follows the offset; mid latitude.
        val hours = zone.getOffset(System.currentTimeMillis()) / 3_600_000.0
        return Place(id, zone.getDisplayName(false, TimeZone.SHORT), 25.0, hours * 15.0, guessed = true)
    }

    fun day(place: Place, cal: Calendar = Calendar.getInstance()): Day {
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
        val offsetMin = (cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / 60_000
        fun local(ut: Double?): Int? = ut?.let { ((it * 60).toInt() + offsetMin).mod(24 * 60) }
        return Day(local(utHours(dayOfYear, place.lat, place.lon, rising = true)), local(utHours(dayOfYear, place.lat, place.lon, rising = false)))
    }

    /** True from sunset until sunrise (polar nights are dark; polar days light). */
    fun isDark(context: Context, cal: Calendar = Calendar.getInstance()): Boolean {
        val d = day(place(context, cal.timeZone), cal)
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val rise = d.sunrise
        val set = d.sunset
        if (rise == null || set == null) {
            // No sunrise or sunset today: dark in a polar winter, light in a polar summer.
            val summer = (cal.get(Calendar.MONTH) in Calendar.APRIL..Calendar.SEPTEMBER) == (place(context, cal.timeZone).lat >= 0)
            return !summer
        }
        return if (rise < set) now < rise || now >= set else now in set until rise
    }

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun norm(v: Double, max: Double): Double {
        var x = v % max
        if (x < 0) x += max
        return x
    }

    /** Almanac for Computers (1990) sunrise/sunset, official zenith 90°50'. Universal time in hours. */
    private fun utHours(dayOfYear: Int, lat: Double, lon: Double, rising: Boolean): Double? {
        val lngHour = lon / 15.0
        val t = dayOfYear + ((if (rising) 6.0 else 18.0) - lngHour) / 24.0
        val m = 0.9856 * t - 3.289
        val l = norm(m + 1.916 * sin(rad(m)) + 0.020 * sin(rad(2 * m)) + 282.634, 360.0)
        var ra = norm(deg(atan(0.91764 * tan(rad(l)))), 360.0)
        ra += floor(l / 90.0) * 90.0 - floor(ra / 90.0) * 90.0
        ra /= 15.0
        val sinDec = 0.39782 * sin(rad(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(rad(90.833)) - sinDec * sin(rad(lat))) / (cosDec * cos(rad(lat)))
        if (cosH > 1 || cosH < -1) return null
        val h = (if (rising) 360.0 - deg(acos(cosH)) else deg(acos(cosH))) / 15.0
        val localMean = h + ra - 0.06571 * t - 6.622
        return norm(localMean - lngHour, 24.0)
    }
}
