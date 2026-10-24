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

    val size: Int get() = items.size

    /** Matching items, best first; [accept] keeps out other kinds, hidden groups and the like. */
    fun find(query: String, limit: Int = Int.MAX_VALUE, accept: (Channel) -> Boolean = { true }): List<Channel> {
        val q = SearchQuery(query)
        if (q.isEmpty) return emptyList()
        var keys = LongArray(64)
        var n = 0
        for (i in names.indices) {
            val padded = names[i]
            val score = q.score(padded) { cores[i] ?: SearchText.core(items[i].name).also { cores[i] = it } }
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
