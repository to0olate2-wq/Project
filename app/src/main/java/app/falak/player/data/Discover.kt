package app.falak.player.data

import java.util.Locale

/**
 * Ways to show what a playlist has (8.34, the owner picked them from what paid apps do). The pieces
 * here need no network and no Android, so they are tested on their own (DiscoverTest).
 */

/**
 * One poster per film or series: providers list the same title several times ("EN - Dune (2021)",
 * "4K - Dune (2021)", "MULTI-SUB | Dune (2021)"). Copies share their cleaned name and year (a title
 * without a year only goes with others without one). The lists of everything (All films, Home's rows)
 * show one; its page offers the others as versions; a group's own list keeps its copies.
 */
object Versions {
    private val LEAD = Regex("^\\s*([A-Za-z0-9][A-Za-z0-9+/_ -]{0,14}?)\\s*[|:–—-]\\s+")
    private val TAGS = setOf(
        "MULTI", "SUB", "SUBS", "VIP", "4K", "8K", "3D", "UHD", "FHD", "HD", "SD", "HEVC", "DUB", "DUBBED", "VF", "VO", "VOST", "VOSTFR",
        "NF", "AMZ", "LAT", "ENG", "ARA", "ARB", "TUR", "HDR", "IMAX", "CAM", "TS", "WEB", "BLURAY", "NEW",
    )

    /** "MULTI-SUB | Dune (2021)", "4K-AR - Dune" → "Dune (2021)", "Dune": a provider's tags in front, gone. */
    fun withoutTags(name: String): String {
        // (8.49) The pattern only where a mark followed by a space is there at all: most names have none.
        if (!markThenSpace(name)) return name
        val m = LEAD.find(name) ?: return name
        val words = m.groupValues[1].split('-', '+', '/', '_', ' ').filter { it.isNotEmpty() }
        val tags = words.isNotEmpty() && words.all { w -> w.uppercase(Locale.ROOT) in TAGS || (w.length in 2..3 && w.all { it.isUpperCase() }) }
        return if (tags && m.range.last + 1 < name.length) name.substring(m.range.last + 1) else name
    }

    /** One of [LEAD]'s marks followed by a space (or a no-break space) somewhere in [s]. */
    internal fun markThenSpace(s: String): Boolean {
        for (i in 0 until s.length - 1) {
            val c = s[i]
            if (c == '|' || c == ':' || c == '-' || c == '–' || c == '—') {
                val d = s[i + 1]
                if (d.isWhitespace() || Character.isSpaceChar(d)) return true
            }
        }
        return false
    }

    /** A title's cleaned name and year. */
    fun title(name: String, thisYear: Int = TitleMatch.currentYear()): Pair<String, Int?> = TitleMatch.clean(withoutTags(name), thisYear)

    /** What copies of one title share; null for what can't have copies (live channels, episodes, short names). */
    fun key(ch: Channel, thisYear: Int = TitleMatch.currentYear()): String? {
        val tag = when {
            ch.kind == Kind.MOVIE && ch.url.isNotEmpty() -> "m"
            ch.kind == Kind.SERIES && ch.isSeriesFolder -> "s"
            else -> return null
        }
        val (name, year) = title(ch.name, thisYear)
        val n = TitleMatch.norm(name)
        if (n.length < 2) return null
        return "$tag|$n|${year ?: ""}"
    }

    /** Each title with copies: id → its key (titles listed once aren't in it). */
    fun keys(list: List<Channel>, thisYear: Int = TitleMatch.currentYear()): Map<String, String> {
        val byKey = HashMap<String, MutableList<String>>()
        for (ch in list) {
            val k = key(ch, thisYear) ?: continue
            byKey.getOrPut(k) { ArrayList(1) } += ch.id
        }
        val out = HashMap<String, String>()
        for ((k, ids) in byKey) if (ids.size >= 2) for (id in ids) out[id] = k
        return out
    }

    /** [list] with each title once (its first copy), in the same order. */
    fun oneEach(list: List<Channel>, keys: Map<String, String>): List<Channel> {
        if (keys.isEmpty()) return list
        val seen = HashSet<String>()
        return list.filter { ch -> keys[ch.id]?.let { seen.add(it) } ?: true }
    }

    /**
     * What tells a copy apart, from the words its name has besides the title and the year
     * ("EN - Dune (2021) 4K" → "EN · 4K"), else its group; made unique among [copies].
     */
    fun labels(copies: List<Channel>, thisYear: Int = TitleMatch.currentYear()): List<String> {
        val raw = copies.map { ch ->
            val (name, year) = title(ch.name, thisYear)
            val titleWords = TitleMatch.norm(name).split(' ').filter { it.isNotEmpty() }.toSet()
            ch.name.split(Regex("[^\\p{L}\\p{N}+]+"))
                .filter { it.isNotEmpty() && TitleMatch.norm(it) !in titleWords && it != year?.toString() }
                .distinctBy { it.uppercase(Locale.ROOT) }
                .joinToString(" · ")
                .ifEmpty { ch.group.ifBlank { "Version" } }
        }
        return raw.mapIndexed { i, label ->
            if (raw.count { it == label } > 1 && copies[i].group.isNotBlank() && label != copies[i].group) "$label · ${copies[i].group}" else label
        }.mapIndexed { i, label -> if (raw.count { it == label } > 1) "$label ${i + 1}" else label }
    }
}

/**
 * Collections of films, found in their names (no internet, so Arabic names too): "Toy Story",
 * "Toy Story 2", "Toy Story 3"; "John Wick: Chapter 4"; "The Godfather Part II"; "Avengers: Endgame".
 * A collection has two films or more, at least one of them named as a sequel or a part.
 */
object Collections {
    // A sequel's mark at the end: "2", "II", "Part 2", "Chapter Three"… (after a space, ":" or "-").
    private val MARK = Regex(
        "(?i)[\\s:–—-]+(?:(?:part|chapter|episode|vol(?:ume)?\\.?|الجزء|جزء)\\s*)?" +
            "(?:\\d{1,2}|[ivx]{1,5}|one|two|three|four|five|six|seven|eight|nine|ten|الأول|الثاني|الثالث|الرابع|الخامس)\\s*$",
    )

    private val SUBTITLE_DASH = Regex("\\s[-–—]\\s")

    /** "John Wick: Chapter 3 - Parabellum" → ("John Wick", true); "Heat" → ("Heat", false). */
    fun stem(title: String): Pair<String, Boolean> {
        var s = title.trim()
        var marked = false
        // A subtitle after " - ".
        val dash = SUBTITLE_DASH.find(s)
        if (dash != null && dash.range.first >= 3) {
            s = s.substring(0, dash.range.first).trim()
            marked = true
        }
        MARK.find(s)?.let { m ->
            val left = s.substring(0, m.range.first).trim()
            if (TitleMatch.norm(left).length >= 3) {
                s = left
                marked = true
            }
        }
        // A subtitle after ":" ("Avengers: Endgame"): kept apart only when what's before it is a name of its own.
        val colon = s.indexOf(':')
        if (colon > 0) {
            val left = s.substring(0, colon).trim()
            if (TitleMatch.norm(left).replace(" ", "").length >= 4) {
                s = left
                marked = true
            }
        }
        return s.trim().trimEnd(':', '-', '–', '—', ',').trim() to marked
    }

    class Collection(val name: String, val films: List<Channel>)

    /**
     * The collections in [movies] (each title once: pass the list after [Versions.oneEach]), the
     * biggest first; their films oldest first.
     */
    fun find(movies: List<Channel>, thisYear: Int = TitleMatch.currentYear()): List<Collection> {
        class Film(val ch: Channel, val title: String, val year: Int?, val marked: Boolean)
        val byStem = LinkedHashMap<String, MutableList<Film>>()
        for (ch in movies) {
            if (ch.kind != Kind.MOVIE) continue
            val (title, year) = Versions.title(ch.name, thisYear)
            val (stem, marked) = stem(title)
            val key = TitleMatch.norm(stem)
            if (key.replace(" ", "").length < 3) continue
            byStem.getOrPut(key) { ArrayList(2) } += Film(ch, title, year, marked)
        }
        val out = ArrayList<Collection>()
        for (films in byStem.values) {
            val distinct = films.distinctBy { TitleMatch.norm(it.title) + "|" + (it.year ?: "") }
            if (distinct.size < 2 || distinct.none { it.marked }) continue
            val sorted = distinct.sortedWith(compareBy<Film>({ it.year ?: Int.MAX_VALUE }, { sequelNumber(it.title) }, { it.title.length }))
            out += Collection(commonName(sorted.map { it.title }), sorted.map { it.ch })
        }
        return out.sortedWith(compareByDescending<Collection> { it.films.size }.thenBy { it.name })
    }

    /** The words the films' names start with ("Mission: Impossible" for the Mission: Impossible films). */
    internal fun commonName(titles: List<String>): String {
        val split = titles.map { it.split(' ').filter { w -> w.isNotEmpty() } }
        val first = split.first()
        var n = 0
        while (n < first.size && split.all { it.size > n && TitleMatch.norm(it[n]) == TitleMatch.norm(first[n]) }) n++
        val name = first.take(n).joinToString(" ").trimEnd(':', '-', '–', '—', ',', ' ')
        return name.ifBlank { stem(titles.first()).first }
    }

    private fun sequelNumber(title: String): Int {
        val m = Regex("(?i)(\\d{1,2}|\\b[ivx]{1,5})\\s*$").find(title.trim())?.value?.trim()?.lowercase(Locale.ROOT) ?: return 1
        return m.toIntOrNull() ?: ROMAN[m] ?: 1
    }

    private val ROMAN = mapOf("i" to 1, "ii" to 2, "iii" to 3, "iv" to 4, "v" to 5, "vi" to 6, "vii" to 7, "viii" to 8, "ix" to 9, "x" to 10)
}

/**
 * The sports hub (8.34): matches and races on now and later today, found in the TV guide of the
 * sports channels ("Real Madrid v Barcelona", "Formula 1: Abu Dhabi Grand Prix", "LIVE: …"). The
 * same match on several channels is one, with all of them. Only as good as the provider's guide.
 */
object Sports {
    private val SPORTS = Regex(
        "(?i)(sport|bein|\\bssc\\b|espn|dazn|eurosport|arena|supersport|setanta|premier|laliga|la liga|bundesliga|serie a|ligue 1|" +
            "champions|football|soccer|futbol|fútbol|\\bnba\\b|\\bnfl\\b|\\bnhl\\b|\\bmlb\\b|\\bufc\\b|\\bwwe\\b|boxing|\\bf1\\b|formula|motogp|" +
            "tennis|golf|cricket|rugby|racing|رياض|كورة|كرة|ملاعب|الدوري)",
    )
    private val VERSUS = Regex("(?i)\\S\\s+(?:v|vs\\.?|x|×|ضد)\\s+\\S")
    private val EVENT = Regex("(?i)(grand prix|\\bgp\\b|\\bufc\\s?\\d|fight night|\\bfinal\\b|derby|cl[aá]sico|qualifying|\\brace\\b|نهائي|ديربي|كلاسيكو)")
    private val LIVE = Regex("(?i)^\\s*(?:live|en vivo|en direct|مباشر|\\(l\\)|\\[live\\])(?:\\s|:|-|$)")
    private val NOT_LIVE = Regex(
        "(?i)(highlight|replay|review|preview|magazine|classic|rewind|best of|\\bgoals\\b|countdown|build-?up|\\btalk|\\bnews\\b|studio|" +
            "documentary|ملخص|إعادة|اعادة|استوديو|تحليل)",
    )
    private val LIVE_WORD = Regex("(?i)^\\s*(?:live|en vivo|en direct|مباشر|\\(l\\)|\\[live\\])\\s*[:\\-–|]?\\s*")

    fun isSportsChannel(ch: Channel): Boolean = SPORTS.containsMatchIn(ch.group) || SPORTS.containsMatchIn(ch.name)

    /** A programme that is a match or a race, not talk about one. */
    fun isMatch(title: String): Boolean {
        val t = title.trim()
        if (t.length < 5 || t.length > 120 || NOT_LIVE.containsMatchIn(t)) return false
        return VERSUS.containsMatchIn(t) || EVENT.containsMatchIn(t) || LIVE.containsMatchIn(t)
    }

    /** The name to show ("LIVE: Arsenal v Chelsea" → "Arsenal v Chelsea"). */
    fun display(title: String): String = LIVE_WORD.replace(title.trim(), "").trim().ifEmpty { title.trim() }

    class Match(val title: String, val start: Long, val stop: Long, val channels: List<Channel>, val program: Program) {
        fun live(now: Long) = now in start until stop
    }

    /** On now, then the next [aheadMs] (18 hours), soonest first; [limit] at most. */
    fun find(live: List<Channel>, epg: Map<String, List<Program>>, now: Long, aheadMs: Long = 18 * 3_600_000L, limit: Int = 60): List<Match> {
        if (epg.isEmpty()) return emptyList()
        val byKey = LinkedHashMap<String, MutableList<Pair<Channel, Program>>>()
        val until = now + aheadMs
        for (ch in live) {
            if (ch.kind != Kind.LIVE || !isSportsChannel(ch)) continue
            val progs = epg[ch.epgKey] ?: continue
            for (p in progs) {
                if (p.start > until) break
                if (p.stop <= now || !isMatch(p.title)) continue
                val key = TitleMatch.norm(display(p.title)) + "|" + (p.start / 900_000)
                byKey.getOrPut(key) { ArrayList(2) } += ch to p
            }
        }
        return byKey.values.map { l ->
            val first = l.first().second
            Match(display(first.title), l.minOf { it.second.start }, l.maxOf { it.second.stop }, l.map { it.first }.distinctBy { it.id }, first)
        }.sortedWith(compareBy<Match>({ !it.live(now) }, { it.start }, { it.title })).take(limit)
    }
}

/**
 * Films on now (8.44; the owner picked "Films" of "On now by type"): live channels showing a film at
 * this moment. A film = the guide says so (its <category>, see [Program.film]) or the channel is a film
 * channel by its group or name ("UK | Movies", "Cinema 1", "أفلام"), when the guide gives no types.
 */
object FilmsOnNow {
    private val CATEGORY = Regex("(?i)\\b(movies?|films?|feature film|spielfilm|pel[ií]culas?|filmes?|cin[eé]ma)\\b|فيلم|أفلام|افلام|سينما")
    private val CHANNEL = Regex("(?i)(movies?|\\bfilms?|film\\d|\\bcin[eé]ma|cinemax|\\bkino|pel[ií]culas|filmes|فيلم|أفلام|افلام|سينما)")
    /** Not a film even on a film channel: trailers, promos, the shop. */
    private val NOT_FILM = Regex(
        "(?i)(trailer|making of|behind the scenes|coming soon|coming up|promo|teleshopping|infomercial|\\bclosedown\\b|" +
            "off air|إعلان|اعلان|برومو)",
    )

    /** Less than this left: nearly over, not worth starting. */
    const val MIN_LEFT_MS = 10 * 60_000L

    fun isFilmCategory(text: String): Boolean = CATEGORY.containsMatchIn(text)

    fun isFilmChannel(ch: Channel): Boolean = CHANNEL.containsMatchIn(ch.group) || CHANNEL.containsMatchIn(ch.name)

    /** On now, the most left to watch first; the same film on several channels is one card. */
    fun find(live: List<Channel>, epg: Map<String, List<Program>>, now: Long, limit: Int = 30): List<Sports.Match> {
        if (epg.isEmpty()) return emptyList()
        val byTitle = LinkedHashMap<String, MutableList<Pair<Channel, Program>>>()
        for (ch in live) {
            if (ch.kind != Kind.LIVE) continue
            val p = Epg.current(epg[ch.epgKey], now) ?: continue
            if (!p.film && !isFilmChannel(ch)) continue
            val title = p.title.trim()
            if (title.length < 2 || p.stop - now < MIN_LEFT_MS || NOT_FILM.containsMatchIn(title)) continue
            byTitle.getOrPut(TitleMatch.norm(title).ifEmpty { title.lowercase(Locale.ROOT) }) { ArrayList(2) } += ch to p
        }
        return byTitle.values.map { l ->
            val first = l.first().second
            Sports.Match(first.title.trim(), l.minOf { it.second.start }, l.maxOf { it.second.stop }, l.map { it.first }.distinctBy { it.id }, first)
        }.sortedWith(compareByDescending<Sports.Match> { it.stop }.thenBy { it.title }).take(limit)
    }
}
