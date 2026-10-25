package app.falak.player.data

import java.text.Normalizer

/**
 * Search text as people type it, not as providers write it: case, accents and Arabic diacritics
 * don't matter, Arabic letter forms count as one (أ إ آ ا, ة ه, ى ي, ک ك, ی ي), Arabic and Persian
 * digits are digits, "ᴴᴰ" is "hd", and punctuation is just a space ("Spider-Man" = "spider man").
 */
object SearchText {
    /** "AR: Spider-Man (2024)" → "ar spider man 2024": lowercase words separated by one space. */
    fun normalize(raw: String): String {
        // Plain Latin and Arabic text is handled letter by letter (fast on 100,000 names); anything
        // else (accents, "ᴴᴰ", ligatures) is first taken apart into base letters and marks.
        var simple = true
        for (c in raw) {
            if (c >= '\u0080' && c !in '؀'..'ۿ') {
                simple = false
                break
            }
        }
        val src = if (simple) raw else Normalizer.normalize(raw, Normalizer.Form.NFKD)
        val sb = StringBuilder(src.length)
        var spaced = true
        for (c0 in src) {
            val c = when {
                c0 in 'A'..'Z' -> c0 + 32
                c0 < '\u0080' -> c0
                else -> Character.toLowerCase(c0)
            }
            if (c >= '̀' && isMark(c)) continue // accents, Arabic short vowels and hamza marks
            val m = when (c) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ؤ' -> 'و'
                'ئ' -> 'ي'
                'ة' -> 'ه'
                'ى', 'ی' -> 'ي'
                'ک' -> 'ك'
                'ı' -> 'i'
                // Joined, not split: "Ocean's" = "oceans"; Arabic stretching (tatweel) is decoration.
                '\'', '’', '‘', '`', 'ʼ', 'ـ' -> continue
                in '٠'..'٩' -> '0' + (c - '٠')
                in '۰'..'۹' -> '0' + (c - '۰')
                else -> c
            }
            if (Character.isLetterOrDigit(m)) {
                sb.append(m)
                spaced = false
            } else if (!spaced) {
                sb.append(' ')
                spaced = true
            }
        }
        if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') sb.setLength(sb.length - 1)
        return sb.toString()
    }

    private fun isMark(c: Char): Boolean {
        val t = Character.getType(c)
        return t == Character.NON_SPACING_MARK.toInt() || t == Character.ENCLOSING_MARK.toInt() ||
            t == Character.COMBINING_SPACING_MARK.toInt()
    }

    /** Provider labels that aren't part of a title ("4K", "FHD", "[MULTI-SUB]", "(Dubbed)"). */
    private val TAG_WORDS = hashSetOf(
        "4k", "8k", "uhd", "fhd", "hd", "sd", "hq", "hdr", "hevc", "h264", "h265", "x264", "x265",
        "2160p", "1080p", "720p", "480p", "multi", "sub", "subs", "dub", "dubbed", "vip",
    )

    /**
     * Where a provider's language or country label ends ("AR: ", "|EN| ", "FR - "), or 0: up to
     * six capital letters at the very start, then a separator.
     */
    private fun prefixEnd(raw: String): Int {
        val limit = minOf(raw.length, 12)
        var letters = 0
        for (i in 0 until limit) {
            val c = raw[i]
            when {
                c in 'A'..'Z' -> letters++
                c == ' ' -> Unit
                (c == '|' || c == '[' || c == '(') && letters == 0 -> Unit
                (c == '|' || c == ':' || c == ']' || c == ')' || c == '-') && letters in 2..6 -> return i + 1
                else -> return 0
            }
        }
        return 0
    }

    /**
     * The title itself, normalized, without the provider's labels and a trailing year:
     * "AR: Harbour (2024) 4K" → "harbour". Used to put exact titles first.
     */
    fun core(raw: String): String {
        val words = normalize(raw.substring(prefixEnd(raw))).split(' ').filter { it.isNotEmpty() && it !in TAG_WORDS }
        val trimmed = if (words.size > 1 && isYear(words.last())) words.dropLast(1) else words
        return trimmed.joinToString(" ")
    }

    private fun isYear(w: String): Boolean = w.length == 4 && (w.startsWith("19") || w.startsWith("20")) && w.all { it in '0'..'9' }

    /** True when [a] and the [len] letters of [b] from [start] differ by at most one letter added, dropped, changed or swapped. */
    fun withinOneEdit(a: String, b: String, start: Int = 0, len: Int = b.length - start): Boolean {
        val la = a.length
        if (kotlin.math.abs(la - len) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < la && j < len) {
            if (a[i] == b[start + j]) {
                i++
                j++
                continue
            }
            if (++edits > 1) return false
            when {
                la > len -> i++
                la < len -> j++
                i + 1 < la && j + 1 < len && a[i] == b[start + j + 1] && a[i + 1] == b[start + j] -> {
                    i += 2
                    j += 2
                }
                else -> {
                    i++
                    j++
                }
            }
        }
        edits += (la - i) + (len - j)
        return edits <= 1
    }
}

/**
 * What was typed, ready to test names against. Every word must match a word in the name, in any
 * order (the start of a word is enough: "spi" finds "Spider-Man"); words of 3+ letters also match
 * inside words and across spaces ("spiderman" finds "Spider-Man"); the Arabic "ال" may be there
 * or not; and words of 5+ letters forgive one wrong letter.
 */
class SearchQuery(raw: String) {
    val text: String = SearchText.normalize(raw)
    private val words: List<String> = text.split(' ').filter { it.isNotEmpty() }
    private val joined: String = words.joinToString("")

    /** For each word: " word", and for Arabic also with and without "ال". */
    private val needles: List<Array<String>> = words.map { w ->
        val arabic = w[0] in '؀'..'ۿ'
        when {
            arabic && w.startsWith("ال") && w.length > 3 -> arrayOf(" $w", " " + w.substring(2))
            arabic -> arrayOf(" $w", " ال$w")
            else -> arrayOf(" $w")
        }
    }
    private val phrase = " $text"

    val isEmpty: Boolean get() = words.isEmpty()

    /** The same search for names written in the other script (Arabic / Latin), when it can be compared. */
    val cross: CrossScript? by lazy { CrossScript.of(text) }

    /**
     * How well a name matches, 0 = not at all: 1000 the exact title, 800 the title starts with
     * what was typed, 600 the words in that order, 400 every word at the start of a word,
     * 300 inside words, 100 with one wrong letter. [padded] is the normalized name with a space
     * each side; [core] gives the bare title (see [SearchText.core]) when it's needed.
     */
    fun score(padded: String, core: () -> String): Int {
        if (words.isEmpty()) return 0
        var level = 400
        for (k in words.indices) {
            val forms = needles[k]
            var found = false
            for (f in forms) {
                if (padded.contains(f)) {
                    found = true
                    break
                }
            }
            if (found) continue
            val w = words[k]
            if (w.length >= 3 && containsIgnoringSpaces(padded, w)) {
                level = minOf(level, 300)
                continue
            }
            if (w.length >= 5 && typo(padded, w)) {
                level = minOf(level, 100)
                continue
            }
            // The whole search typed as one word, or split differently ("spider man" / "spiderman").
            if (words.size > 1 && joined.length >= 5 && containsIgnoringSpaces(padded, joined)) return 300
            return 0
        }
        if (level < 400) return level
        val full = padded.substring(1, padded.length - 1)
        if (full == text) return 1000
        val bare = core()
        return when {
            bare == text -> 1000
            full.startsWith(text) || bare.startsWith(text) -> 800
            padded.contains(phrase) -> 600
            else -> 400
        }
    }

    /** Score for any text (a programme title): normalizes it first. */
    fun score(title: String): Int {
        if (words.isEmpty()) return 0
        val n = SearchText.normalize(title)
        return score(" $n ") { SearchText.core(title) }
    }

    /** [score], or else how it sounds against a title in the other script (see [CrossScript]). */
    fun scoreAcross(title: String): Int {
        val s = score(title)
        if (s > 0) return s
        val c = cross ?: return 0
        if (!c.wanted(Sounds.scripts(title))) return 0
        return c.match(Sounds.of(title))
    }

    private fun containsIgnoringSpaces(padded: String, w: String): Boolean {
        val first = w[0]
        var i = padded.indexOf(first)
        while (i >= 0) {
            var j = 0
            var k = i
            while (k < padded.length && j < w.length) {
                val c = padded[k]
                if (c == ' ') {
                    // A space can be skipped only between letters of the word, not before it.
                    k++
                    continue
                }
                if (c != w[j]) break
                j++
                k++
            }
            if (j == w.length) return true
            i = padded.indexOf(first, i + 1)
        }
        return false
    }

    /** One wrong letter in a word of the name, or at the start of a longer one (still typing). */
    private fun typo(padded: String, w: String): Boolean {
        var start = 1
        while (start < padded.length) {
            val end = padded.indexOf(' ', start)
            if (end < 0) break
            val len = end - start
            if (len >= w.length - 1) {
                if (SearchText.withinOneEdit(w, padded, start, len)) return true
                if (len > w.length + 1) {
                    for (cut in w.length - 1..w.length + 1) {
                        if (SearchText.withinOneEdit(w, padded, start, cut)) return true
                    }
                }
            }
            start = end + 1
        }
        return false
    }
}

/**
 * Finding things by name in a whole playlist, fast enough to run as you type on 100,000+ titles:
 * every name is prepared once (lower case, letter forms unified), then each search is a quick
 * pass over them. Best matches first (see [SearchQuery.score]), then shorter names, then the
 * playlist's own order.
 */
class SearchIndex(private val items: List<Channel>) {
    private val names: Array<String> = Array(items.size) { " " + SearchText.normalize(items[it].name) + " " }

    // Bare titles, worked out only for names that match cleanly and kept for later searches.
    // Racing threads just compute the same string.
    private val cores = arrayOfNulls<String>(items.size)

    // Names by sound (see Sounds), for searches typed in the other script; made when first needed.
    private val sounds = arrayOfNulls<String>(items.size)

    private fun soundOf(i: Int): String = sounds[i] ?: Sounds.of(items[i].name, names[i].trim()).also { sounds[i] = it }

    /** Works every name's sound out ahead (while the search box opens), so the first search in the other script is quick. */
    fun warmSounds() {
        for (i in items.indices) soundOf(i)
    }

    val size: Int get() = items.size

    /** Matching items, best first; [accept] keeps out other kinds, hidden groups and the like. */
    fun find(query: String, limit: Int = Int.MAX_VALUE, accept: (Channel) -> Boolean = { true }): List<Channel> {
        val q = SearchQuery(query)
        if (q.isEmpty) return emptyList()
        val cross = q.cross
        var keys = LongArray(64)
        var n = 0
        for (i in names.indices) {
            val padded = names[i]
            var score = q.score(padded) { cores[i] ?: SearchText.core(items[i].name).also { cores[i] = it } }
            // Typed in Arabic, written in Latin letters (or the other way round): by sound.
            if (score == 0 && cross != null && cross.wanted(Sounds.scripts(padded))) score = cross.match(soundOf(i))
            if (score == 0 || !accept(items[i])) continue
            if (n == keys.size) keys = keys.copyOf(n * 2)
            // Sorted as one number: score, then shorter names, then playlist order.
            val shortness = 1023 - minOf(padded.length, 1023)
            keys[n++] = (score.toLong() shl 34) or (shortness.toLong() shl 24) or (0xFFFFFF - minOf(i, 0xFFFFFF)).toLong()
        }
        java.util.Arrays.sort(keys, 0, n)
        val out = ArrayList<Channel>(minOf(n, limit))
        var k = n - 1
        while (k >= 0 && out.size < limit) {
            out += items[0xFFFFFF - (keys[k] and 0xFFFFFF).toInt()]
            k--
        }
        return out
    }

    companion object {
        /** Ranks a short list (favorites, recently watched) without keeping an index. */
        fun rank(list: List<Channel>, query: String, accept: (Channel) -> Boolean = { true }): List<Channel> =
            SearchIndex(list).find(query, accept = accept)
    }
}

/**
 * Names across scripts: "الجزيرة" and "Al Jazeera", "ام بي سي" and "MBC", "سبايدر مان" and
 * "Spider-Man" sound alike, so both are spelled out the same rough way, by their consonants only
 * ("jzr", "mbs", "sbdr mn"), and compared there. Arabic doesn't write most vowels and Latin
 * spellings of Arabic names disagree on them, so leaving every vowel out is what makes the two
 * meet. It's rough on purpose: such matches rank after every match in the script typed.
 */
object Sounds {
    /** Arabic letter names, as spelled-out names write them ("ام بي سي" = MBC). */
    private val LETTER_NAMES: Map<Char, String> = mapOf(
        'a' to "اي", 'b' to "بي", 'c' to "سي", 'd' to "دي", 'e' to "اي", 'f' to "اف", 'g' to "جي",
        'h' to "اتش", 'i' to "اي", 'j' to "جي", 'k' to "كي", 'l' to "ال", 'm' to "ام", 'n' to "ان",
        'o' to "او", 'p' to "بي", 'q' to "كيو", 'r' to "ار", 's' to "اس", 't' to "تي", 'u' to "يو",
        'v' to "في", 'w' to "دبليو", 'x' to "اكس", 'y' to "واي", 'z' to "زد",
    )

    /** Words that spell a letter (with the other usual spellings): a run of two or more is an abbreviation. */
    private val SPELLED: Set<String> = LETTER_NAMES.values.toHashSet() + setOf("ايه", "زي", "ايتش", "جاي", "كاي", "اكس")

    private const val VOWELS = "aeiou"

    /** Bit for a text with Arabic letters, and for one with Latin letters. */
    const val ARABIC = 1
    const val LATIN = 2

    fun scripts(text: CharSequence): Int {
        var s = 0
        for (c in text) {
            if (c in 'a'..'z' || c in 'A'..'Z') s = s or LATIN else if (c in 'ء'..'ي' || c in 'ٱ'..'ۿ') s = s or ARABIC
            if (s == ARABIC or LATIN) break
        }
        return s
    }

    /**
     * [raw]'s words by sound, ready for [CrossScript.match]: " jzr/jzrt mbk/mbs 2 " (each word's
     * readings separated by "/", words by spaces, a space each end). Empty when nothing is left.
     */
    fun of(raw: String, normalized: String = SearchText.normalize(raw)): String {
        val acronyms = capitalRuns(raw)
        val out = StringBuilder(" ")
        for (alts in words(normalized, acronyms)) {
            out.append(alts.joinToString("/")).append(' ')
        }
        return if (out.length == 1) "" else out.toString()
    }

    /**
     * Each word's readings, first the usual one. Arabic letter names in a row ("ام بي سي") become
     * one word; a short Latin word may be letters said one by one ("mbc", or "MBC" in [acronyms]).
     */
    internal fun words(normalized: String, acronyms: Set<String> = emptySet()): List<List<String>> {
        val raw = normalized.split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<List<String>>(raw.size)
        var i = 0
        while (i < raw.size) {
            val w = raw[i]
            // A run of spelled letters: one word made of the letters' sounds.
            if (w in SPELLED) {
                var j = i
                while (j < raw.size && raw[j] in SPELLED) j++
                if (j - i >= 2) {
                    val joined = squeeze(raw.subList(i, j).joinToString("") { arabic(it).first() })
                    if (joined.isNotEmpty()) out += listOf(joined)
                    i = j
                    continue
                }
            }
            val alts = when {
                w.all { it in '0'..'9' } -> listOf(w)
                w.any { it in 'a'..'z' } -> latin(w, acronym = w in acronyms || (w.length in 2..4 && w.none { it in VOWELS }))
                else -> arabic(w)
            }.filter { it.isNotEmpty() }.distinct()
            // "al" and "el" on their own are the article ("Al Jazeera").
            if (alts.isNotEmpty() && w != "al" && w != "el") out += alts
            i++
        }
        return out
    }

    /** All-capital Latin words of 2–4 letters in [raw], in lower case ("MBC", "OSN"): likely abbreviations. */
    private fun capitalRuns(raw: String): Set<String> {
        var out: HashSet<String>? = null
        var start = -1
        for (k in 0..raw.length) {
            val c = if (k < raw.length) raw[k] else ' '
            if (c in 'A'..'Z') {
                if (start < 0) start = k
                continue
            }
            if (start >= 0) {
                val len = k - start
                val wordEnds = !(c in 'a'..'z')
                val wordStarts = start == 0 || raw[start - 1] !in 'a'..'z'
                if (len in 2..4 && wordEnds && wordStarts) (out ?: HashSet<String>().also { out = it }) += raw.substring(start, k).lowercase()
                start = -1
            }
        }
        return out ?: emptySet()
    }

    /** One Arabic word by sound: the usual reading first, then the others (an "and" in front left off, a final ة said as t). */
    internal fun arabic(word: String): List<String> {
        var s = word
        // The article ("الجزيره" → "جزيره"); not in short words.
        if (s.length > 3 && s.startsWith("ال")) s = s.substring(2)
        // A final ه is mostly the ة of a feminine ending ("جزيره" = Jazeera), said as t before another
        // word ("الحياه" = Al Hayat): left off, or a t.
        val feminine = s.length > 1 && s.endsWith("ه")
        if (feminine) s = s.dropLast(1)
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val first = i == 0
            val code: Char? = when (c) {
                'ب', 'پ' -> 'b'
                'ت' -> if (s.getOrNull(i + 1) == 'ش') { i++; 'x' } else 't'
                'ط', 'ث' -> 't'
                'ج', 'چ', 'گ' -> 'j'
                'ح', 'خ', 'ه' -> 'h'
                'د', 'ض' -> 'd'
                'ذ', 'ز', 'ظ', 'س', 'ص' -> 's'
                'ر' -> 'r'
                'ش' -> 'x'
                // غ too: Arabic writes the hard g of other languages with ج or غ ("جيوغرافيك").
                'غ' -> 'j'
                'ف', 'ڤ' -> 'f'
                'ق', 'ك', 'ک' -> 'k'
                'ل' -> 'l'
                'م' -> 'm'
                'ن' -> 'n'
                'و' -> if (first) 'w' else null
                'ي' -> if (first) 'y' else null
                in '0'..'9' -> c
                else -> null // ا ء ع: vowel sounds
            }
            if (code != null && (sb.isEmpty() || sb[sb.length - 1] != code)) sb.append(code)
            i++
        }
        val main = sb.toString()
        val alts = ArrayList<String>(3)
        alts += main
        if (feminine) alts += squeeze(main + "t")
        // A و in front may be "and", or the w of a borrowed word ("ون" = One).
        if (main.startsWith('w') && main.length > 1) alts += main.substring(1)
        return alts
    }

    /** One Latin word by sound; with [acronym] also its letters said one by one, as Arabic spells them. */
    internal fun latin(word: String, acronym: Boolean = false): List<String> {
        var s = word
        if (s.length >= 6 && (s.startsWith("al") || s.startsWith("el"))) s = s.substring(2)
        val sb = StringBuilder()
        var i = 0
        fun add(code: String) {
            for (c in code) if (sb.isEmpty() || sb[sb.length - 1] != c) sb.append(c)
        }
        while (i < s.length) {
            val c = s[i]
            val next = s.getOrNull(i + 1)
            val first = i == 0
            val code: String? = when (c) {
                'a', 'e', 'i', 'o', 'u' -> null
                'y' -> if (first) "y" else null
                'w' -> if (first) "w" else null
                'b' -> "b"
                'p' -> if (next == 'h') { i++; "f" } else "b"
                // "-tion" is said "-shun" ("Station" = ستيشن).
                't' -> if (next == 'h') { i++; "t" } else if (s.startsWith("ion", i + 1)) "x" else "t"
                'd' -> if (next == 'h') { i++; "s" } else "d"
                'j' -> "j"
                'g' -> when {
                    next != 'h' -> "j"
                    // English's silent "gh" ("Night", "Lights", "Through"): no sound at all.
                    i > 0 && s[i - 1] in VOWELS && (i + 2 >= s.length || s[i + 2] == 't') -> { i++; null }
                    else -> { i++; "j" }
                }
                // "wh" at the start is said w ("White" = وايت).
                'h' -> if (i == 1 && s[0] == 'w') null else "h"
                'k' -> if (next == 'h') { i++; "h" } else "k"
                'q' -> "k"
                'c' -> when (next) {
                    'h' -> { i++; "x" }
                    'e', 'i', 'y' -> "s"
                    'k' -> { i++; "k" }
                    else -> "k"
                }
                's' -> if (next == 'h') { i++; "x" } else "s"
                'z' -> "s"
                'x' -> "ks"
                'f', 'v' -> "f"
                'l', 'm', 'n', 'r' -> c.toString()
                in '0'..'9' -> c.toString()
                else -> null
            }
            if (code != null) add(code)
            i++
        }
        // A final h after a vowel is the Arabic ending, as above ("Jazeerah").
        if (s.length >= 3 && s.last() == 'h' && s[s.length - 2] in VOWELS && sb.length > 1 && sb.last() == 'h') sb.setLength(sb.length - 1)
        val main = sb.toString()
        if (!acronym) return listOf(main)
        val spelled = squeeze(word.mapNotNull { LETTER_NAMES[it] }.joinToString("") { arabic(it).first() })
        return listOf(main, spelled)
    }

    /** Doubled sounds once ("snn" → "sn"). */
    private fun squeeze(s: String): String {
        if (s.length < 2) return s
        val sb = StringBuilder(s.length)
        for (c in s) if (sb.isEmpty() || sb[sb.length - 1] != c) sb.append(c)
        return sb.toString()
    }
}

/**
 * A search typed in one script, compared with names in the other by sound (see [Sounds]). Each
 * word must sound like a word of the name (or like the start of one, for a word being typed), or
 * the whole search like the name run together ("ابوظبي" and "Abu Dhabi").
 */
class CrossScript private constructor(
    /** [Sounds.ARABIC] or [Sounds.LATIN]: the names to compare are the ones written in the other. */
    val wants: Int,
    private val words: List<List<String>>,
    private val joined: String,
) {
    /** True for a name worth comparing: it has letters of the other script. */
    fun wanted(nameScripts: Int): Boolean = nameScripts and wants != 0

    /** [WORDS] when every word matches, [JOINED] when the whole search does, 0 otherwise. [sound] from [Sounds.of]. */
    fun match(sound: String): Int {
        if (sound.isEmpty()) return 0
        if (words.isNotEmpty() && words.all { alts -> alts.any { hasWord(sound, it) } }) return WORDS
        if (joined.length >= 3 && runsTogether(sound)) return JOINED
        return 0
    }

    /** A word of [sound] that is [w] (2 sounds), starts with it (3+), or is it one sound short ("sports" / "سبورت"). */
    private fun hasWord(sound: String, w: String): Boolean {
        val digits = w[0] in '0'..'9'
        var i = sound.indexOf(w)
        while (i >= 0) {
            val before = sound[i - 1]
            if (before == ' ' || before == '/') {
                val after = sound.getOrElse(i + w.length) { ' ' }
                val whole = after == ' ' || after == '/'
                if (whole || (!digits && w.length >= 3)) return true
            }
            i = sound.indexOf(w, i + 1)
        }
        if (!digits && w.length >= 4) {
            val shorter = w.dropLast(1)
            var k = sound.indexOf(shorter)
            while (k >= 0) {
                val before = sound[k - 1]
                val after = sound.getOrElse(k + shorter.length) { ' ' }
                if ((before == ' ' || before == '/') && (after == ' ' || after == '/')) return true
                k = sound.indexOf(shorter, k + 1)
            }
        }
        return false
    }

    /** Some of the name's words in a row (their usual readings, run together) are the whole search run together. */
    private fun runsTogether(sound: String): Boolean {
        val primary = StringBuilder()
        val bounds = HashSet<Int>()
        bounds += 0
        var k = 1
        while (k < sound.length) {
            val end = sound.indexOf(' ', k)
            if (end < 0) break
            primary.append(sound.substring(k, end).substringBefore('/'))
            bounds += primary.length
            k = end + 1
        }
        for (s in bounds) if (s + joined.length in bounds && primary.startsWith(joined, s)) return true
        return false
    }

    companion object {
        /** Scores, below every match in the script typed (see [SearchQuery.score]). */
        const val WORDS = 200
        const val JOINED = 150

        /** For a search typed in one script only, with enough to compare; null otherwise. */
        fun of(normalizedQuery: String): CrossScript? {
            val script = Sounds.scripts(normalizedQuery)
            val wants = when (script) {
                Sounds.ARABIC -> Sounds.LATIN
                Sounds.LATIN -> Sounds.ARABIC
                else -> return null
            }
            val all = Sounds.words(normalizedQuery)
            // Words of one sound say too little on their own ("و"); numbers always count.
            val words = all.filter { alts -> alts[0][0] in '0'..'9' || alts.any { it.length >= 2 } }
            val joined = all.joinToString("") { it[0] }
            if (words.isEmpty() && joined.length < 3) return null
            return CrossScript(wants, words, joined)
        }
    }
}
