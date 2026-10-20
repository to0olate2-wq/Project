package app.falak.player.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** XMLTV guide parser. Keeps only wanted channels and roughly the next day and a half. */
object Epg {

    fun parse(input: InputStream, wanted: Set<String>, now: Long): Map<String, List<Program>> {
        val buffered = BufferedInputStream(input, 64 * 1024)
        buffered.mark(4)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        val stream: InputStream =
            if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered, 64 * 1024) else buffered

        val from = now - 4 * 3_600_000L
        val to = now + 36 * 3_600_000L
        val out = HashMap<String, MutableList<Program>>()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(stream, null)
            var keep = false
            var channel = ""
            var start = 0L
            var stop = 0L
            var title = ""
            var desc = ""
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "programme" -> {
                            channel = parser.getAttributeValue(null, "channel")?.lowercase().orEmpty()
                            start = parseTime(parser.getAttributeValue(null, "start"))
                            stop = parseTime(parser.getAttributeValue(null, "stop"))
                            if (stop <= start) stop = start + 30 * 60_000L
                            keep = start > 0 && channel in wanted && stop > from && start < to
                            title = ""
                            desc = ""
                        }
                        "title" -> if (keep && title.isEmpty()) title = parser.nextText()
                        "desc" -> if (keep && desc.isEmpty()) desc = parser.nextText()
                    }
                } else if (event == XmlPullParser.END_TAG && parser.name == "programme") {
                    if (keep) out.getOrPut(channel) { ArrayList() }.add(Program(start, stop, title, desc))
                    keep = false
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            // Broken guide files are common; keep whatever was read.
        }
        out.values.forEach { list -> list.sortBy { it.start } }
        return out
    }

    fun current(list: List<Program>?, now: Long): Program? =
        list?.firstOrNull { now >= it.start && now < it.stop }

    fun next(list: List<Program>?, now: Long): Program? =
        list?.firstOrNull { it.start > now }

    /** XMLTV time like "20261020143000 +0400" → epoch millis (0 if invalid). */
    fun parseTime(s: String?): Long {
        if (s == null || s.length < 14) return 0
        return try {
            val y = s.substring(0, 4).toInt()
            val mo = s.substring(4, 6).toInt()
            val d = s.substring(6, 8).toInt()
            val h = s.substring(8, 10).toInt()
            val mi = s.substring(10, 12).toInt()
            val se = s.substring(12, 14).toInt()
            var t = ((daysFromCivil(y, mo, d) * 24 + h) * 60 + mi) * 60 + se
            val tz = s.substring(14).trim()
            if (tz.length >= 5 && (tz[0] == '+' || tz[0] == '-')) {
                val off = tz.substring(1, 3).toInt() * 3600 + tz.substring(3, 5).toInt() * 60
                t -= if (tz[0] == '+') off else -off
            }
            t * 1000
        } catch (e: Exception) {
            0
        }
    }

    private fun daysFromCivil(year: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468
    }
}
