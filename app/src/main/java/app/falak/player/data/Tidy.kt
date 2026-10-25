package app.falak.player.data

/**
 * Tidy channel list (Settings → Playlists & TV guide, off unless chosen):
 * - clean names: "AR: MBC 1 FHD ᴴᴰ" shows as "MBC 1", its quality as a small mark (the guide,
 *   favorites and everything else work as before: only the name on screen changes; live
 *   channels only, film and series titles stay as they are);
 * - merge duplicates: a group that lists the same channel in several qualities shows it once;
 *   the best one plays, and the others stay ready as a fall-back (see [Result.versions]).
 * Channels with different words in brackets ("(English)", "(Arabic)") or in their names are
 * never merged; "(Backup)" and quality tags don't count.
 */
object Tidy {
    class Result(
        /** What the lists show, in the playlist's order. */
        val shown: List<Channel>,
        /** Every channel, tidied (merged-away ones too, for favorites and recents that point at them). */
        val all: List<Channel>,
        /** A merged channel's versions, best quality first (itself included), by the shown one's id. */
        val versions: Map<String, List<Channel>>,
    )

    fun apply(list: List<Channel>, clean: Boolean, merge: Boolean): Result {
        // Channel names only: film and series titles stay exactly as the provider wrote them.
        val tidied = if (!clean) list else list.map { ch ->
            if (ch.kind != Kind.LIVE) return@map ch
            val display = cleanName(ch.name)
            if (display == ch.name) ch else ch.tidied(display, qualityLabel(ch.name))
        }
        if (!merge) return Result(tidied, tidied, emptyMap())
        val byKey = LinkedHashMap<String, MutableList<Int>>()
        list.forEachIndexed { i, ch ->
            if (ch.kind != Kind.LIVE || ch.url.isBlank()) return@forEachIndexed
            val key = mergeKey(ch) ?: return@forEachIndexed
            byKey.getOrPut(key) { ArrayList(2) } += i
        }
        val hidden = HashSet<Int>()
        val shownAt = HashMap<Int, Int>()
        val versions = HashMap<String, List<Channel>>()
        for (at in byKey.values) {
            if (at.size < 2) continue
            val best = at.sortedWith(compareByDescending<Int> { qualityRank(list[it]) }.thenBy { it })
            // The best version takes the place of the first one the playlist lists.
            shownAt[at.first()] = best.first()
            for (i in at) if (i != at.first()) hidden += i
            versions[tidied[best.first()].id] = best.map { tidied[it] }
        }
        val shown = ArrayList<Channel>(list.size - hidden.size)
        for (i in tidied.indices) {
            if (i in hidden) continue
            shown += tidied[shownAt[i] ?: i]
        }
        return Result(shown, tidied, versions)
    }

    /** Same group, same channel (country and name without quality tags), same words in brackets. */
    internal fun mergeKey(ch: Channel): String? {
        val name = normName(ch.name)
        if (name.isEmpty()) return null
        val brackets = BRACKETED.findAll(ch.name)
            .map { m -> nameWords(m.value.trim('(', ')', '[', ']', ' ')).joinToString(" ") }
            .filter { it.isNotEmpty() }
            .sorted()
            .toList()
        return ch.group + "|" + sameChannelKey(ch) + "|" + brackets.joinToString(",")
    }

    private val BRACKETED = Regex("[\\[(][^\\])]*[\\])]")

    // Provider labels in front ("AR: ", "|AR| ", "[AE] ", "AR - "): capitals only, so "Up: The Movie" stays.
    private val PREFIX = Regex("^\\s*(?:[\\[(|]\\s*[A-Z]{2,3}(?:[ /-][A-Z]{2,3})?\\s*[\\])|]|[A-Z]{2,3}(?:[ /-][A-Z]{2,3})?\\s*(?:[|:▎┃•]|\\s[-–—]))\\s*[|:▎┃•\\-–—]*\\s*")
    private val QUALITY_WORD = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(fhd|uhd|hd|sd|4k|8k|hevc|h\\.?26[45]|1080[pi]?|720p|2160p|50 ?fps|60 ?fps|hq|lq|hdr|full ?hd|ultra ?hd|backup)(?![\\p{L}\\p{N}])",
    )
    private val TAG_BRACKET = Regex("(?i)[\\[(]\\s*(?:(?:fhd|uhd|hd|sd|4k|8k|hevc|h\\.?26[45]|1080[pi]?|720p|2160p|50 ?fps|60 ?fps|hq|lq|hdr|backup|multi[ -]?sub[s]?|vip)[\\s/,+-]*)+[\\])]")
    private val SUPERSCRIPT = Regex("[\\u1D00-\\u1DBF\\u2070-\\u209F\\u24B6-\\u24E9]+")
    private val DECOR = Regex("[★☆●◉○◆◇■□▶►▪▫♦✦✪❤|]+")

    /** "AR: MBC 1 FHD ᴴᴰ" → "MBC 1"; "|UK| BBC One (Backup)" → "BBC One". Never empty. */
    fun cleanName(raw: String): String {
        var t = PREFIX.replace(raw, "")
        t = SUPERSCRIPT.replace(t, " ")
        t = TAG_BRACKET.replace(t, " ")
        t = QUALITY_WORD.replace(t, " ")
        t = DECOR.replace(t, " ")
        t = t.replace(EMPTY_BRACKETS, " ")
            .replace(SPACES, " ")
            .trim()
            .trim('-', '–', '—', ':', '·', '|', ',', '.', '/')
            .trim()
        return t.ifEmpty { raw.trim() }
    }

    /** "4K", "FHD", "HD", "SD" when the provider's name says so. */
    fun qualityLabel(raw: String): String? {
        val s = SUPERSCRIPT.replace(raw) { m -> java.text.Normalizer.normalize(m.value, java.text.Normalizer.Form.NFKD) }
        return when (qualityRank(Channel(id = "", name = s, group = "", url = ""))) {
            4 -> "4K"
            3 -> "FHD"
            1 -> "SD"
            else -> if (HD_WORD.containsMatchIn(s)) "HD" else null
        }
    }

    // Made once: a big playlist runs every channel name through these.
    private val EMPTY_BRACKETS = Regex("\\(\\s*\\)|\\[\\s*]")
    private val SPACES = Regex("\\s+")
    private val HD_WORD = Regex("(?i)(?<![\\p{L}\\p{N}])(hd|720p)(?![\\p{L}\\p{N}])")
}
