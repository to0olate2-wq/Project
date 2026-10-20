package app.falak.player.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Links guide channels to playlist channels.
 *
 * Every guide channel gets a score for every playlist channel it could belong to: a hand-picked
 * guide beats a tvg-id match, which beats a name match; name matches from the same country beat
 * those from another country ("FR: beIN Sports 1" must not get the Arabic beIN 1 schedule).
 * Each playlist channel then takes programmes from its single best guide channel only, so two
 * similar guide channels can never be mixed into one timeline.
 */
class GuideMatcher(channels: List<Channel>, overrides: Map<String, String> = emptyMap()) {
    val keys: Set<String>
    private val byId = HashMap<String, MutableSet<String>>()
    private val byName = HashMap<String, MutableSet<String>>()
    private val byLoose = HashMap<String, MutableSet<String>>()
    private val countryOfKey = HashMap<String, String?>()
    /** guide id (lowercase) -> keys the user pointed at it */
    private val forced = HashMap<String, MutableSet<String>>()
    /** Keys with a hand-picked guide (or "no guide"): automatic matching leaves them alone. */
    private val pinned = HashSet<String>()
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
            val l = looseName(ch.name)
            if (l.length >= 3) byLoose.getOrPut(l) { HashSet() } += key
            if (key !in countryOfKey) countryOfKey[key] = countryOf(ch.name, ch.group)
            val past = if (ch.catchupDays > 0) ch.catchupDays.coerceAtMost(7) * 86_400_000L else 4 * 3_600_000L
            if (past > (pastMs[key] ?: 0L)) pastMs[key] = past
        }
        keys = all
        for ((key, guideId) in overrides) {
            if (key !in all) continue
            pinned += key
            if (guideId.isNotEmpty()) forced.getOrPut(guideId.lowercase()) { HashSet() } += key
        }
    }

    /** Scores of the playlist keys a guide channel could feed (higher is better). */
    fun candidates(guideId: String, displayNames: List<String>): Map<String, Int> {
        val out = HashMap<String, Int>()
        fun offer(key: String, score: Int) {
            if (key in pinned) return
            if (score > (out[key] ?: -1)) out[key] = score
        }
        val gid = guideId.lowercase()
        forced[gid]?.forEach { out[it] = SCORE_PICKED }
        byId[gid]?.forEach { offer(it, SCORE_ID) }
        val country = guideCountry(guideId)
        val strict = HashSet<String>()
        val loose = HashSet<String>()
        for (name in displayNames) {
            strict += normName(name)
            loose += looseName(name)
        }
        // ids like "MBC1.ae" or "bbc.one.uk" double as names
        strict += normName(guideId.substringBeforeLast('.'))
        loose += looseName(guideId.substringBeforeLast('.').replace('.', ' '))
        for (n in strict) byName[n]?.forEach { k -> offer(k, 1 + 2 * countryAgreement(countryOfKey[k], country)) }
        for (n in loose) {
            if (n.length < 3) continue
            byLoose[n]?.forEach { k ->
                val agree = countryAgreement(countryOfKey[k], country)
                if (agree > 0) offer(k, 2 * agree)
            }
        }
        return out
    }

    companion object {
        const val SCORE_PICKED = 100
        const val SCORE_ID = 50
    }
}

/**
 * Picks, for each playlist key, the one guide channel that feeds it. Guide channels are offered
 * in file order; a later one only takes a key over when it scores strictly higher.
 */
private class Assignment(private val matcher: GuideMatcher) {
    private val best = HashMap<String, Pair<String, Int>>() // key -> (guide id, score)

    fun offer(guideId: String, names: List<String>) {
        for ((key, score) in matcher.candidates(guideId, names)) {
            val cur = best[key]
            if (cur == null || score > cur.second) best[key] = guideId to score
        }
    }

    /** guide id -> keys it feeds */
    fun result(): HashMap<String, MutableSet<String>> {
        val out = HashMap<String, MutableSet<String>>()
        for ((key, v) in best) out.getOrPut(v.first) { HashSet() } += key
        return out
    }
}

/** Result of reading one guide source. */
class ParsedGuide(val programs: Map<String, MutableList<Program>>, val channels: List<GuideChannel>)

object Epg {

    /**
     * Streams an XMLTV file (plain or .gz) and returns programmes for matched channels plus the
     * list of every channel the file offers. [shiftMs] corrects guides with a wrong time zone.
     */
    fun parse(input: InputStream, matcher: GuideMatcher, now: Long, source: String = "", shiftMs: Long = 0): ParsedGuide {
        val buffered = BufferedInputStream(input, 64 * 1024)
        buffered.mark(4)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        val stream: InputStream =
            if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered, 64 * 1024) else buffered

        val future = now + 48 * 3_600_000L
        val out = HashMap<String, MutableList<Program>>()
        val names = LinkedHashMap<String, MutableList<String>>()
        var routes: HashMap<String, MutableSet<String>>? = null
        val late = HashMap<String, Set<String>>()
        var taken: HashSet<String>? = null
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
                        "channel" -> channelId = parser.getAttributeValue(null, "id")?.also { id ->
                            names.getOrPut(id) { ArrayList(2) }
                        }
                        "display-name" -> channelId?.let { id ->
                            names.getOrPut(id) { ArrayList(2) } += parser.nextText().trim()
                        }
                        "programme" -> {
                            channelId = null
                            // All <channel> entries come first: decide who feeds whom once, up front.
                            val r = routes ?: Assignment(matcher).also { a ->
                                for ((id, n) in names) a.offer(id, n)
                            }.result().also { routes = it }
                            val id = parser.getAttributeValue(null, "channel").orEmpty()
                            // Some files have programmes for channels they never declared.
                            val keys = r[id] ?: if (id in names) emptySet() else late.getOrPut(id) {
                                if (taken == null) taken = r.values.flatMapTo(HashSet()) { it }
                                val t = taken!!
                                matcher.candidates(id, emptyList()).keys.filterTo(HashSet()) { it !in t }.also { t += it }
                            }
                            start = Epg.parseTime(parser.getAttributeValue(null, "start"))
                            stop = Epg.parseTime(parser.getAttributeValue(null, "stop"))
                            if (stop <= start) stop = start + 30 * 60_000L
                            start += shiftMs
                            stop += shiftMs
                            targets = if (keys.isNotEmpty() && start > shiftMs && start < future) {
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
        for (list in out.values) {
            list.sortBy { it.start }
            // Drop overlaps some guides contain (repeated or stacked entries).
            var i = 1
            while (i < list.size) {
                if (list[i].start < list[i - 1].stop && list[i].start == list[i - 1].start) list.removeAt(i) else i++
            }
        }
        val listed = names.map { (id, n) -> GuideChannel(id, n.firstOrNull { it.isNotBlank() } ?: id, source) }
        return ParsedGuide(out, listed)
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
