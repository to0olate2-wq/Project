package app.falak.player.data

import kotlinx.serialization.Serializable

/** A team as the scores feed names it ("Arsenal FC", "Arsenal", "ARS"). */
@Serializable
data class FootballTeam(val name: String = "", val short: String = "", val tla: String = "")

/** A match from the scores feed (see stats/src/index.js, /v1/football). */
@Serializable
data class FootballMatch(
    val id: Long = 0,
    val competition: String = "",
    val code: String = "",
    /** Kick-off, epoch ms. */
    val time: Long = 0,
    /** SCHEDULED, TIMED, IN_PLAY, PAUSED (half-time), FINISHED, POSTPONED… */
    val status: String = "",
    val minute: Int? = null,
    val matchday: Int? = null,
    val home: FootballTeam = FootballTeam(),
    val away: FootballTeam = FootballTeam(),
    val homeScore: Int? = null,
    val awayScore: Int? = null,
) {
    val live: Boolean get() = status == "IN_PLAY" || status == "PAUSED"
    val finished: Boolean get() = status == "FINISHED" || status == "AWARDED"

    /** "2 – 1", or null before kick-off. */
    val score: String? get() = if (homeScore != null && awayScore != null && (live || finished)) "$homeScore – $awayScore" else null

    /** "67'", "HT", "FT", or null when not started. */
    val state: String?
        get() = when {
            status == "PAUSED" -> "HT"
            status == "IN_PLAY" -> minute?.let { "$it'" } ?: "LIVE"
            finished -> "FT"
            status == "POSTPONED" -> "Postponed"
            status == "SUSPENDED" -> "Suspended"
            status == "CANCELLED" -> "Cancelled"
            else -> null
        }
}

/** One row of the league table. */
@Serializable
data class TableRow(
    val position: Int = 0,
    val name: String = "",
    val short: String = "",
    val tla: String = "",
    val played: Int = 0,
    val won: Int = 0,
    val draw: Int = 0,
    val lost: Int = 0,
    val goalDifference: Int = 0,
    val points: Int = 0,
    val form: String = "",
)

@Serializable
data class LeagueRound(val matchday: Int? = null, val matches: List<FootballMatch> = emptyList(), val table: List<TableRow> = emptyList())

/** The Match centre's scores: today's matches and the Premier League. */
@Serializable
data class FootballFeed(
    val configured: Boolean = false,
    /** When the oldest part was fetched, epoch ms. */
    val at: Long = 0,
    val stale: Boolean = false,
    val error: String? = null,
    val today: List<FootballMatch> = emptyList(),
    val pl: LeagueRound = LeagueRound(),
)

/** A match the TV guide lists: on which channel, when, and (when the feed knows it) the score. */
data class TvMatch(
    val channel: Channel,
    val program: Program,
    val home: String,
    val away: String,
    /** How many other channels show it at the same time. */
    val alsoOn: Int,
    val score: FootballMatch? = null,
)

/**
 * Matches in the TV guide: programmes named "Team A v Team B" (also "vs", "×", "ضد", and "–"
 * on sports channels), after an optional "Premier League:" or "مباشر:" in front. Each match once,
 * on a favorite channel when one shows it; with its score when the scores feed has it.
 */
object Matches {
    private val SPORT_CHANNEL = Regex(
        "(?i)sport|bein|\\bssc\\b|\\bad sports|dubai racing|sky sports|espn|dazn|tnt sports|\\bbt sport|eurosport|arena|supersport|" +
            "alkass|al kass|\\bkass\\b|osn sports|\\bstarz.*sport|shahid|thmanyah|ثمانية|رياض|كأس|football|soccer|futbol|calcio|premier|liga|" +
            "serie a|bundesliga|ligue|uefa|fifa|\\bmatch\\b|ملاعب|الدوري",
    )
    private val LEAGUE_WORDS = Regex(
        "(?i)premier league|la ?liga|serie a|bundesliga|ligue 1|champions league|europa league|conference league|fa cup|" +
            "carabao|copa|world cup|roshn|saudi pro|uae pro|adnoc pro|دوري|كأس|نهائي|ديربي|كلاسيكو",
    )

    /** "A v B": both sides, from a title (null if it isn't one). [sports]: the channel is a sports channel. */
    fun teams(title: String, sports: Boolean): Pair<String, String>? {
        // "Premier League: A v B", "LIVE: A v B": the part after the last colon that holds the match.
        var t = title.trim()
        val parts = t.split(':', '|')
        if (parts.size > 1) {
            parts.lastOrNull { sepIn(it, sports) != null }?.let { t = it.trim() }
        }
        t = t.replace(Regex("(?i)\\s*[(\\[](live|مباشر|hd|fhd|4k)[)\\]]\\s*"), " ")
            .replace(Regex("(?i)^(live|مباشر|en vivo|direct)\\s*[-–:]?\\s+"), "")
            .trim()
        val sep = sepIn(t, sports) ?: return null
        val home = t.substring(0, sep.first).trim().trim('-', '–', ',', '.').trim()
        val away = t.substring(sep.second).trim()
            // "B - Matchday 9", "B (Round 3)" and the like.
            .replace(Regex("\\s+[-–(].*$"), "")
            .trim('-', '–', ',', '.')
            .trim()
        if (home.length < 2 || away.length < 2 || home.length > 40 || away.length > 40) return null
        if (home.count { it.isDigit() } > 3 || away.count { it.isDigit() } > 3) return null
        return home to away
    }

    /** Where the separator is in [t]: (its start, the start of what follows), or null. */
    private fun sepIn(t: String, sports: Boolean): Pair<Int, Int>? {
        val strong = Regex("\\s+(v|vs\\.?|VS\\.?|Vs\\.?|×|x|X|ضد)\\s+").find(t)
        if (strong != null) return strong.range.first to strong.range.last + 1
        if (!sports) return null
        // "A - B" only on sports channels (elsewhere it's "News - Evening edition").
        val dash = Regex("\\s+[-–]\\s+").findAll(t).toList()
        return if (dash.size == 1) dash[0].range.first to dash[0].range.last + 1 else null
    }

    /**
     * Matches in the guide from [from] to [until] on [channels] (those the user can see), with
     * scores from [feed] when it has them. Live ones first, then by kick-off.
     */
    fun inGuide(
        channels: List<Channel>,
        epg: Map<String, List<Program>>,
        from: Long,
        until: Long,
        favorites: Set<String> = emptySet(),
        feed: List<FootballMatch> = emptyList(),
    ): List<TvMatch> {
        class Found(var ch: Channel, val p: Program, val home: String, val away: String, var count: Int)
        val found = LinkedHashMap<String, Found>()
        for (ch in channels) {
            val programs = epg[ch.epgKey] ?: continue
            val sports = SPORT_CHANNEL.containsMatchIn(ch.name) || SPORT_CHANNEL.containsMatchIn(ch.group)
            for (p in programs) {
                if (p.stop <= from || p.start >= until) continue
                val (home, away) = teams(p.title, sports) ?: continue
                if (!sports && !LEAGUE_WORDS.containsMatchIn(p.title) && !LEAGUE_WORDS.containsMatchIn(p.desc.take(200))) continue
                val key = SearchText.normalize("$home|$away") + "|" + p.start
                val prev = found[key]
                if (prev == null) {
                    found[key] = Found(ch, p, home, away, 1)
                } else {
                    prev.count++
                    if (ch.id in favorites && prev.ch.id !in favorites) prev.ch = ch
                }
            }
        }
        return found.values.map { f ->
            TvMatch(f.ch, f.p, f.home, f.away, f.count - 1, scoreFor(f.home, f.away, f.p.start, feed))
        }.sortedWith(compareBy<TvMatch> { if (it.program.start <= from) 0 else 1 }.thenBy { it.program.start }.thenBy { it.home })
    }

    /** The feed's match for a guide listing: the same two teams, kicking off within 3 hours of it. */
    fun scoreFor(home: String, away: String, start: Long, feed: List<FootballMatch>): FootballMatch? =
        feed.firstOrNull { m ->
            kotlin.math.abs(m.time - start) <= 3 * 3_600_000L &&
                ((sameTeam(home, m.home) && sameTeam(away, m.away)) || (sameTeam(home, m.away) && sameTeam(away, m.home)))
        }

    /** "in 25 min", "in 2 h 10 min". */
    fun untilLabel(start: Long, now: Long): String {
        val min = ((start - now + 59_999) / 60_000).toInt()
        return when {
            min <= 0 -> "starting"
            min < 60 -> "in $min min"
            min % 60 == 0 -> "in ${min / 60} h"
            else -> "in ${min / 60} h ${min % 60} min"
        }
    }

    private val FILLER = setOf("fc", "afc", "cf", "sc", "ac", "as", "ss", "sv", "fk", "sk", "club", "de", "calcio", "the", "nadi", "نادي")

    /** "Arsenal FC" → "arsenal"; "FC Barcelona" → "barcelona"; Arabic letter forms unified. */
    private fun core(s: String): String =
        SearchText.normalize(s).split(' ').filter { it.isNotEmpty() && it !in FILLER }.joinToString(" ")

    /** Is the guide's name [guide] this team (its name, short name, three letters, or a common other name)? */
    fun sameTeam(guide: String, team: FootballTeam): Boolean {
        val g = core(guide)
        if (g.isEmpty()) return false
        val names = buildList {
            add(core(team.short))
            add(core(team.name))
            addAll(aliases(team).map { core(it) })
        }.filter { it.length >= 3 }.distinct()
        for (n in names) {
            if (g == n) return true
            // Whole words: "man city" in "man city women" no; "arsenal" in "arsenal london" yes.
            if (" $g ".contains(" $n ") || (g.length >= 4 && " $n ".contains(" $g "))) return true
        }
        return team.tla.length == 3 && g == team.tla.lowercase()
    }

    /** Other names people and guides use, in English and Arabic, keyed by the feed's short name. */
    private fun aliases(team: FootballTeam): List<String> {
        val key = core(team.short).ifEmpty { core(team.name) }
        return ALIASES.entries.firstOrNull { (k, _) -> core(k) == key || core(team.name).startsWith(core(k)) }?.value.orEmpty()
    }

    private val ALIASES: Map<String, List<String>> = mapOf(
        "Arsenal" to listOf("أرسنال", "آرسنال", "الأرسنال"),
        "Aston Villa" to listOf("Villa", "أستون فيلا"),
        "Bournemouth" to listOf("AFC Bournemouth", "بورنموث"),
        "Brentford" to listOf("برينتفورد"),
        "Brighton Hove" to listOf("Brighton", "Brighton & Hove Albion", "برايتون"),
        "Burnley" to listOf("بيرنلي"),
        "Chelsea" to listOf("تشيلسي", "تشلسي"),
        "Crystal Palace" to listOf("Palace", "كريستال بالاس"),
        "Everton" to listOf("إيفرتون", "ايفرتون"),
        "Fulham" to listOf("فولهام"),
        "Ipswich Town" to listOf("Ipswich", "إيبسويتش"),
        "Leeds United" to listOf("Leeds", "ليدز", "ليدز يونايتد"),
        "Leicester City" to listOf("Leicester", "ليستر", "ليستر سيتي"),
        "Liverpool" to listOf("ليفربول"),
        "Man City" to listOf("Manchester City", "Man. City", "مانشستر سيتي", "السيتي"),
        "Man United" to listOf("Manchester United", "Man Utd", "Man. United", "مانشستر يونايتد", "اليونايتد"),
        "Newcastle" to listOf("Newcastle United", "نيوكاسل", "نيوكاسل يونايتد"),
        "Nottingham" to listOf("Nottingham Forest", "Nott'm Forest", "Forest", "نوتنغهام فورست", "نوتينغهام فورست"),
        "Southampton" to listOf("ساوثهامبتون"),
        "Sunderland" to listOf("سندرلاند"),
        "Tottenham" to listOf("Tottenham Hotspur", "Spurs", "توتنهام"),
        "West Ham" to listOf("West Ham United", "وست هام"),
        "Wolverhampton" to listOf("Wolves", "Wolverhampton Wanderers", "وولفرهامبتون", "ولفرهامبتون"),
        "Real Madrid" to listOf("ريال مدريد", "الريال"),
        "Barça" to listOf("Barcelona", "Barca", "برشلونة"),
        "Atleti" to listOf("Atletico Madrid", "Atlético Madrid", "Atlético de Madrid", "أتلتيكو مدريد", "اتلتيكو مدريد"),
        "Bayern" to listOf("Bayern Munich", "Bayern München", "بايرن ميونخ", "بايرن"),
        "Dortmund" to listOf("Borussia Dortmund", "BVB", "بوروسيا دورتموند", "دورتموند"),
        "PSG" to listOf("Paris Saint-Germain", "Paris SG", "باريس سان جيرمان"),
        "Juventus" to listOf("Juve", "يوفنتوس"),
        "Inter" to listOf("Inter Milan", "Internazionale", "إنتر ميلان", "الإنتر"),
        "Milan" to listOf("AC Milan", "ميلان"),
        "Napoli" to listOf("نابولي"),
        "Roma" to listOf("AS Roma", "روما"),
    )
}
