package app.falak.player.ui

private val BRACKETED = Regex("""^\s*[\[(|]\s*[^\])|]{1,6}\s*[\])|]\s*""")
private val TAGGED = Regex("""^\s*[\p{L}\p{N}+]{1,4}\s*[|:]\s*""")
private val DASHED = Regex("""^\s*\p{Lu}{2,3}\s+-\s+""")
private val QUALITY = Regex("""(?i)\b(fhd|uhd|hd|sd|4k|8k|hevc|h\.?26[45]|\d{3,4}p|50fps|60fps|raw|backup)\b""")

/**
 * "AR | MBC 1" → "MBC", "Al Jazeera News HD" → "AJ", "CNN" → "CNN", "beIN Sports 2" → "BS".
 * Leading country or group tags ("AR |", "[UK]", "UK - ") and quality words are left out. An
 * acronym first word is kept whole; otherwise the first letters of the first two words. Arabic and
 * other scripts that join their letters: just the first letter.
 */
fun initialsOf(name: String): String {
    var n = name
    repeat(2) {
        val stripped = n.replaceFirst(BRACKETED, "").replaceFirst(TAGGED, "").replaceFirst(DASHED, "")
        if (stripped.any { it.isLetterOrDigit() }) n = stripped
    }
    val cleaned = n.replace(QUALITY, " ")
    val words = cleaned.split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }
        .ifEmpty { name.split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() } }
    val first = words.firstOrNull() ?: return "TV"
    val latin = Character.UnicodeScript.of(first.codePointAt(0)).let {
        it == Character.UnicodeScript.LATIN || it == Character.UnicodeScript.COMMON
    }
    if (!latin) return first.substring(0, Character.charCount(first.codePointAt(0)))
    if (first.length in 2..4 && first.all { it.isUpperCase() || it.isDigit() } && first.any { it.isLetter() }) return first
    return words.take(2).joinToString("") { it.first().uppercase() }
}
