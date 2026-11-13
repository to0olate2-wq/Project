package app.falak.player.data

/**
 * A big playlist's groups folded into folders (8.20, Live TV): providers name their groups
 * "UK | Sports", "UK | News", "AR: Kids", "[FR] Cinema"… Groups whose names start the same way,
 * before such a mark, become one folder ("UK") holding them under the rest of their names
 * ("Sports", "News"). The folder's name is the start as the provider wrote it: nothing is guessed
 * (no "AR means Arabic"). A start only one group has makes no folder, and the list is only folded
 * when that gives at least two folders. The groups themselves (their names as keys, their
 * channels, hiding, locking, your own order) are untouched: this is only how the list is drawn.
 */
object GroupFolders {
    sealed interface Entry

    /** One group: its real name (the key everywhere else) and what to show for it. */
    class Leaf(val group: String, val label: String) : Entry

    class Folder(val name: String, val groups: List<Leaf>) : Entry

    class Tree(val entries: List<Entry>) {
        val folders: List<Folder> = entries.filterIsInstance<Folder>()
        val hasFolders: Boolean get() = folders.isNotEmpty()
        private val home: Map<String, Folder> = run {
            val out = HashMap<String, Folder>()
            for (f in folders) for (g in f.groups) out[g.group] = f
            out
        }
        private val labels: Map<String, String> = run {
            val out = HashMap<String, String>()
            for (e in entries) when (e) {
                is Leaf -> out[e.group] = e.label
                is Folder -> for (g in e.groups) out[g.group] = g.label
            }
            out
        }

        /** The folder a group is in, if any. */
        fun folderOf(group: String?): Folder? = if (group == null) null else home[group]

        /** A group's short name (without its folder's). */
        fun label(group: String): String = labels[group] ?: group

        /** "UK · Sports" for a group in a folder, the group's own name otherwise. */
        fun title(group: String): String = folderOf(group)?.let { "${it.name} · ${label(group)}" } ?: group
    }

    // "UK | Sports", "UK: Sports", "|UK| Sports", "UK ➤ Sports", "UK - Sports" (a dash only with
    // spaces round it: "Sci-Fi" is one word). Never split at & + . , ' or brackets.
    private const val MARK = """[^\p{L}\p{N}\s&'+.,()\[\]"\-]"""
    private val MARKED = Regex("""^\s*[|\[(]?\s*(.{2,24}?)\s*[\])]?(?:\s*[|:]+\s*|\s+$MARK+\s*|\s*$MARK+\s+|\s+[-–—]+\s+)(\S.*)$""")
    // "[UK] Sports", "(UK) Sports".
    private val BRACKETED = Regex("""^\s*[\[(]\s*([^\[\]()]{2,24}?)\s*[\])]\s*[|:\-–—]*\s*(\S.*)$""")
    private val WORDY = Regex("""[\p{L}\p{N}]""")

    /** A group's name as (start, rest), or null when it has no such mark. */
    internal fun split(name: String): Pair<String, String>? {
        val m = BRACKETED.find(name) ?: MARKED.find(name) ?: return null
        val start = m.groupValues[1].trim().trim('|', '[', ']', '(', ')').trim()
        val rest = m.groupValues[2].trim()
        if (start.length < 2 || rest.isEmpty() || !WORDY.containsMatchIn(start) || !WORDY.containsMatchIn(rest)) return null
        return start to rest
    }

    private fun key(start: String) = start.lowercase().replace(Regex("""\s+"""), " ")

    /** The groups (in the order given) as folders and loose groups; flat when folding doesn't help. */
    fun build(names: List<String>): Tree {
        val parts = names.map { split(it) }
        val counts = HashMap<String, Int>()
        for (p in parts) if (p != null) counts.merge(key(p.first), 1, Int::plus)
        val shared = counts.filterValues { it >= 2 }.keys
        if (shared.size < 2) return flat(names)
        val entries = ArrayList<Any>()
        val open = HashMap<String, Pair<String, ArrayList<Leaf>>>()
        names.forEachIndexed { i, name ->
            val p = parts[i]
            val k = p?.let { key(it.first) }
            if (p == null || k !in shared) {
                entries += Leaf(name, name)
            } else {
                val folder = open.getOrPut(k!!) { (p.first to ArrayList<Leaf>()).also { entries += it } }
                folder.second += Leaf(name, p.second)
            }
        }
        return Tree(entries.map { e ->
            @Suppress("UNCHECKED_CAST")
            if (e is Leaf) e else (e as Pair<String, ArrayList<Leaf>>).let { Folder(it.first, it.second) }
        })
    }

    fun flat(names: List<String>): Tree = Tree(names.map { Leaf(it, it) })

    /** The groups whose names have every word typed (in any order, any case), in the order given. */
    fun find(names: List<String>, typed: String): List<String> {
        val words = typed.lowercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return names.filter { n ->
            val low = n.lowercase()
            words.all { it in low }
        }
    }
}

/**
 * A channel that is only a line in the provider's list, not something to watch
 * ("##### UK SPORTS #####", "━━━━━━", "***** 4K *****"): its name starts with the same sign three
 * times over, or has three signs or more and no letter or digit at all. (A flag before a name is
 * two different signs, so "🇬🇧 BBC One" is a channel.) Live TV's rows leave these out; the full
 * list of a group still has them, as the provider sent it.
 */
fun isDividerName(name: String): Boolean {
    val s = name.trim()
    if (s.isEmpty()) return false
    val first = s.codePointAt(0)
    if (Character.isLetterOrDigit(first)) return false
    var i = 0
    var same = 0
    while (i < s.length && same < 3) {
        val c = s.codePointAt(i)
        if (c != first) break
        same++
        i += Character.charCount(c)
    }
    if (same >= 3) return true
    var signs = 0
    i = 0
    while (i < s.length) {
        val c = s.codePointAt(i)
        if (Character.isLetterOrDigit(c)) return false
        if (!Character.isWhitespace(c)) signs++
        i += Character.charCount(c)
    }
    return signs >= 3
}
