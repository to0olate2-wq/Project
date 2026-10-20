package app.falak.player.data

private const val EPG_SHARE = "https://epgshare01.online/epgshare01/epg_ripper_"

/**
 * A free, daily-updated XMLTV guide (epgshare01). [countries] are the channel countries it
 * covers; [keywords] catch channel families that are not tied to one country.
 */
class GuideSource(val name: String, val url: String, val countries: Set<String> = emptySet(), val keywords: List<String> = emptyList())

val GUIDE_SOURCES = listOf(
    GuideSource("UAE & Arabic channels", "${EPG_SHARE}AE1.xml.gz", setOf("ae", "ar", "qa", "kw", "lb", "jo", "iq", "om", "bh", "sy", "ps")),
    GuideSource("Saudi Arabia", "${EPG_SHARE}SA1.xml.gz", setOf("sa")),
    GuideSource("Egypt", "${EPG_SHARE}EG1.xml.gz", setOf("eg")),
    GuideSource("beIN Sports", "${EPG_SHARE}BEIN1.xml.gz", keywords = listOf("bein")),
    GuideSource("Al Jazeera", "${EPG_SHARE}ALJAZEERA1.xml.gz", keywords = listOf("jazeera")),
    GuideSource("United Kingdom", "${EPG_SHARE}UK1.xml.gz", setOf("uk")),
    GuideSource("Ireland", "${EPG_SHARE}IE1.xml.gz", setOf("ie")),
    GuideSource("United States", "${EPG_SHARE}US2.xml.gz", setOf("us")),
    GuideSource("US sports", "${EPG_SHARE}US_SPORTS1.xml.gz", keywords = listOf("espn", "nfl", "nba", "nhl", "mlb", "fox sports", "nbcsn")),
    GuideSource("Canada", "${EPG_SHARE}CA2.xml.gz", setOf("ca")),
    GuideSource("India", "${EPG_SHARE}IN1.xml.gz", setOf("in")),
    GuideSource("Pakistan", "${EPG_SHARE}PK1.xml.gz", setOf("pk")),
    GuideSource("Turkey", "${EPG_SHARE}TR1.xml.gz", setOf("tr")),
    GuideSource("France", "${EPG_SHARE}FR1.xml.gz", setOf("fr")),
    GuideSource("Germany", "${EPG_SHARE}DE1.xml.gz", setOf("de")),
    GuideSource("Austria", "${EPG_SHARE}AT1.xml.gz", setOf("at")),
    GuideSource("Switzerland", "${EPG_SHARE}CH1.xml.gz", setOf("ch")),
    GuideSource("Netherlands", "${EPG_SHARE}NL1.xml.gz", setOf("nl")),
    GuideSource("Belgium", "${EPG_SHARE}BE2.xml.gz", setOf("be")),
    GuideSource("Spain", "${EPG_SHARE}ES1.xml.gz", setOf("es")),
    GuideSource("Portugal", "${EPG_SHARE}PT1.xml.gz", setOf("pt")),
    GuideSource("Italy", "${EPG_SHARE}IT1.xml.gz", setOf("it")),
    GuideSource("Greece", "${EPG_SHARE}GR1.xml.gz", setOf("gr")),
    GuideSource("Poland", "${EPG_SHARE}PL1.xml.gz", setOf("pl")),
    GuideSource("Romania", "${EPG_SHARE}RO1.xml.gz", setOf("ro")),
    GuideSource("Sweden", "${EPG_SHARE}SE1.xml.gz", setOf("se")),
    GuideSource("Norway", "${EPG_SHARE}NO1.xml.gz", setOf("no")),
    GuideSource("Denmark", "${EPG_SHARE}DK1.xml.gz", setOf("dk")),
    GuideSource("Brazil", "${EPG_SHARE}BR1.xml.gz", setOf("br")),
    GuideSource("Mexico & Latin", "${EPG_SHARE}MX1.xml.gz", setOf("mx")),
    GuideSource("Australia", "${EPG_SHARE}AU1.xml.gz", setOf("au")),
    GuideSource("New Zealand", "${EPG_SHARE}NZ1.xml.gz", setOf("nz")),
    GuideSource("South Africa", "${EPG_SHARE}ZA1.xml.gz", setOf("za")),
    GuideSource("Philippines", "${EPG_SHARE}PH1.xml.gz", setOf("ph")),
)

/** A guide source worth adding, with how many of the playlist's live channels it is likely to cover. */
class GuideSuggestion(val source: GuideSource, val channels: Int)

/**
 * Looks at where the playlist's live channels come from ("UK: …", "AR | …", group "ARABIC" …) and
 * suggests the free guides for those countries, biggest first, skipping ones already added.
 */
fun suggestGuides(live: List<Channel>, existing: List<String>): List<GuideSuggestion> {
    if (live.isEmpty()) return emptyList()
    val byCountry = HashMap<String, Int>()
    val names = ArrayList<String>(live.size)
    for (ch in live) {
        countryOf(ch.name, ch.group)?.let { byCountry[it] = (byCountry[it] ?: 0) + 1 }
        names += ch.name.lowercase()
    }
    val minimum = maxOf(5, live.size / 200)
    return GUIDE_SOURCES.asSequence()
        .filter { it.url !in existing }
        .map { src ->
            val n = src.countries.sumOf { byCountry[it] ?: 0 } +
                if (src.keywords.isEmpty()) 0 else names.count { name -> src.keywords.any { name.contains(it) } }
            GuideSuggestion(src, n)
        }
        .filter { it.channels >= minimum }
        .sortedByDescending { it.channels }
        .take(6)
        .toList()
}

/** Friendly name for a guide link: the source's name if it's one of ours. */
fun guideSourceName(url: String): String =
    GUIDE_SOURCES.firstOrNull { it.url == url }?.name ?: "Your guide link · ${maskHost(url)}"
