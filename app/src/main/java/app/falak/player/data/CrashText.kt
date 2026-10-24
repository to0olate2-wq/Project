package app.falak.player.data

/**
 * Crash reports as they leave the device (see [app.falak.player.CrashReports]): what kind of
 * stop it was, a short "signature" that groups the same fault from different devices, and the
 * report with anything personal taken out: web and stream addresses, IP and MAC addresses, host
 * names, logins and passwords, emails, file names on the device's storage, long tokens and long
 * quoted text. What's left is the error, where in the app's code it happened, and the app's own
 * last steps ("player opened", "channel list opened (3 groups, 120 items)").
 */
object CrashText {
    private val TLDS = "com|net|org|tv|io|me|xyz|info|live|pro|cc|co|uk|ae|sa|eg|de|fr|nl|ru|us|ca|biz|top|club|online|site|vip|stream|link|app|cloud"

    private val RULES: List<Pair<Regex, String>> = listOf(
        // Any address with a scheme (http, rtmp, udp, content…), with its user, password and token.
        Regex("""(?i)\b[a-z][a-z0-9+.\-]{1,15}://[^\s"'<>()\[\]{}]+""") to "<address>",
        Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""") to "<email>",
        Regex("""(?i)\b(user(?:name)?|pass(?:word)?|pwd|token|auth|key|mac|login)=[^&\s"]+""") to "$1=<hidden>",
        // Xtream-style paths: /live/USER/PASS/123.ts
        Regex("""(?i)/(live|movie|series|timeshift)/[^/\s]+/[^/\s]+/""") to "/$1/<hidden>/<hidden>/",
        Regex("""(?i)(host\s*:?\s*)"[^"]*"""") to "$1\"<host>\"",
        Regex("""\b(?:[0-9a-fA-F]{1,4}:){4,7}[0-9a-fA-F]{1,4}\b""") to "<ip>",
        Regex("""\b(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?\b""") to "<ip>",
        // host:port, but not the app's own "File.kt:123".
        Regex("""\b(?:[a-zA-Z0-9-]+\.)+(?!(?:kt|java):)[a-zA-Z]{2,}:\d{2,5}\b""") to "<host>",
        Regex("""\b(?:[a-z0-9-]+\.)+(?:$TLDS)\b(?!\.[a-zA-Z])""") to "<host>",
        Regex("""(?i)(?:/storage/|/sdcard/|/mnt/|/data/media/)[^\s:"')]+""") to "<file>",
        Regex(""""[^"\n]{24,}"""") to "\"…\"",
        Regex("""\b(?=[A-Za-z0-9_\-]*\d)(?=[A-Za-z0-9_\-]*[A-Za-z])[A-Za-z0-9_\-]{32,}\b""") to "<id>",
    )

    /** The report with anything personal taken out. */
    fun scrub(text: String): String = RULES.fold(text) { acc, (rule, with) -> rule.replace(acc, with) }

    /** An exception line at the start of a line: "java.lang.IllegalStateException: …", "Caused by: …". */
    private val THROWABLE = Regex("""^(?:Caused by: )?((?:[a-z_][\w$]*\.)+[A-Z][\w$]*)(?::\s|\s*$)""")

    /** A stack frame: "at a.b.C.method(File.kt:12)", or the same without "at" (the freeze record). */
    private val FRAME = Regex("""^\s*(?:at\s+)?([A-Za-z_$][\w$]*(?:\.[\w$<>\-]+)+)\(([^)]*)\)\s*$""")

    /** "crash", "anr" (not responding), "native" (video engine), "memory", "freeze" or "other". */
    fun type(text: String): String = when {
        "(ANR)" in text -> "anr"
        "(native crash)" in text -> "native"
        "free memory" in text || "too much memory" in text -> "memory"
        "Screen froze for" in text -> "freeze"
        text.lineSequence().any { THROWABLE.containsMatchIn(it) } -> "crash"
        else -> "other"
    }

    /**
     * What groups reports of the same fault: the error and the app code it came from, e.g.
     * "IllegalStateException in LibraryScreenKt.LibraryScreen (LibraryScreen.kt)". No line
     * numbers, so the same fault in the next version groups with it.
     */
    fun signature(type: String, text: String): String {
        val lines = text.lines()
        val sig = when (type) {
            "crash" -> {
                // The deepest "Caused by" is the real fault; its first app frame says where.
                var error: String? = null
                var where: String? = null
                var i = 0
                while (i < lines.size) {
                    val t = THROWABLE.find(lines[i])
                    if (t == null) {
                        i++
                        continue
                    }
                    var app: String? = null
                    var any: String? = null
                    var j = i + 1
                    while (j < lines.size) {
                        val f = FRAME.find(lines[j]) ?: break
                        if (any == null) any = frame(f)
                        if (app == null && f.groupValues[1].startsWith("app.falak.player")) app = frame(f)
                        j++
                    }
                    error = t.groupValues[1].substringAfterLast('.')
                    if (app != null || any != null || where == null) where = app ?: any ?: where
                    i = j
                }
                listOfNotNull(error ?: "Crash", where?.let { "in $it" }).joinToString(" ")
            }
            "anr", "freeze" -> {
                val frames = lines.mapNotNull { FRAME.find(it) }
                val f = frames.firstOrNull { it.groupValues[1].startsWith("app.falak.player") } ?: frames.firstOrNull()
                val label = if (type == "anr") "Not responding" else "Froze"
                if (f == null) label else "$label in ${frame(f)}"
            }
            "native" -> {
                val signal = Regex("""\bSIG[A-Z]{3,6}\b""").find(text)?.value
                val lib = Regex("""\blib[\w+\-]*(?:\.[\w+\-]+)*\.so\b""").find(text)?.value
                listOfNotNull("Engine crash", signal, lib?.let { "in $it" }).joinToString(" ")
            }
            "memory" -> "Closed by Android for memory"
            else -> lines.firstOrNull { it.startsWith("Stopped by Android") }?.trim() ?: "Stopped"
        }
        return sig.take(200)
    }

    /** "app.falak.player.ui.LibraryScreenKt$LibraryScreen$1.invoke(LibraryScreen.kt:12)" → "LibraryScreenKt$LibraryScreen.invoke (LibraryScreen.kt)". */
    private fun frame(m: MatchResult): String {
        val parts = m.groupValues[1].split('.')
        val from = parts.indexOfFirst { it.isNotEmpty() && it[0].isUpperCase() }.let { if (it < 0) maxOf(0, parts.size - 2) else it }
        val name = parts.drop(from).joinToString(".").replace(Regex("""\$\d+"""), "")
        val file = m.groupValues[2].substringBefore(':').takeIf { it.isNotBlank() && it.contains('.') }
        return if (file != null) "$name ($file)" else name
    }
}
