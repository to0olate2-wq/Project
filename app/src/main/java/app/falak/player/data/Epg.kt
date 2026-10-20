package app.falak.player.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Links guide channels to playlist channels: first by tvg-id, then by cleaned-up name
 * ("AR: MBC 1 HD" ≈ "MBC1"), which fixes most "no guide" channels.
 */
class GuideMatcher(channels: List<Channel>) {
    val keys: Set<String>
    private val byId = HashMap<String, MutableSet<String>>()
    private val byName = HashMap<String, MutableSet<String>>()
    /** How far back to keep programmes per key: longer for channels with replay. */
    val pastMs = HashMap<String, Long>()

    init {
        val all = HashSet<String>()
        for (ch in channels) {
            if (ch.kind != Kind.LIVE) continue
            val key = ch.epgKey
            all += key
            if (ch.tvgId.isNotBlank()) byId.getOrPut(ch.tvgId.lowercase()) { HashSet() } += key
            val n = normName(ch.name)
            if (n.length >= 2) byName.getOrPut(n) { HashSet() } += key
            val past = if (ch.catchupDays > 0) ch.catchupDays.coerceAtMost(7) * 86_400_000L else 4 * 3_600_000L
            if (past > (pastMs[key] ?: 0L)) pastMs[key] = past
        }
        keys = all
    }

    /** Playlist keys for a guide channel id and its display names. */
    fun resolve(guideId: String, displayNames: List<String>): Set<String> {
        byId[guideId.lowercase()]?.let { return it }
        val out = HashSet<String>()
        for (name in displayNames) byName[normName(name)]?.let { out += it }
        if (out.isEmpty()) {
            // ids like "MBC1.ae" or "bbc.one.uk"
            byName[normName(guideId.substringBeforeLast('.'))]?.let { out += it }
            byName[normName(guideId)]?.let { out += it }
        }
        return out
    }
}

object Epg {

    /**
     * Streams an XMLTV file (plain or .gz) and returns programmes for matched channels.
     * [shiftMs] corrects guides published with a wrong time zone.
     */
    fun parse(input: InputStream, matcher: GuideMatcher, now: Long, shiftMs: Long = 0): Map<String, MutableList<Program>> {
        val buffered = BufferedInputStream(input, 64 * 1024)
        buffered.mark(4)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        val stream: InputStream =
            if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered, 64 * 1024) else buffered

        val future = now + 48 * 3_600_000L
        val out = HashMap<String, MutableList<Program>>()
        val names = HashMap<String, MutableList<String>>()
        val resolved = HashMap<String, Set<String>>()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(stream, null)
            var channelId: String? = null
            var targets: Set<String> = emptySet()
            var start = 0L
            var stop = 0L
            var title = ""
            var desc = ""
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "channel" -> channelId = parser.getAttributeValue(null, "id")
                        "display-name" -> channelId?.let { id ->
                            names.getOrPut(id) { ArrayList(2) } += parser.nextText()
                        }
                        "programme" -> {
                            channelId = null
                            val id = parser.getAttributeValue(null, "channel").orEmpty()
                            val keys = resolved.getOrPut(id) { matcher.resolve(id, names[id].orEmpty()) }
                            start = parseTime(parser.getAttributeValue(null, "start"))
                            stop = parseTime(parser.getAttributeValue(null, "stop"))
                            if (stop <= start) stop = start + 30 * 60_000L
                            start += shiftMs
                            stop += shiftMs
                            targets = if (start > shiftMs && start < future) {
                                keys.filterTo(HashSet()) { k -> stop > now - (matcher.pastMs[k] ?: 0L) }
                            } else {
                                emptySet()
                            }
                            title = ""
                            desc = ""
                        }
                        "title" -> if (targets.isNotEmpty() && title.isEmpty()) title = parser.nextText()
                        "desc" -> if (targets.isNotEmpty() && desc.isEmpty()) desc = parser.nextText().take(400)
                    }
                } else if (event == XmlPullParser.END_TAG && parser.name == "programme") {
                    if (targets.isNotEmpty()) {
                        val prog = Program(start, stop, title, desc)
                        for (k in targets) out.getOrPut(k) { ArrayList() }.add(prog)
                    }
                    targets = emptySet()
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
