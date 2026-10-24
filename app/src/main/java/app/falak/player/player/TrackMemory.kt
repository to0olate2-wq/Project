package app.falak.player.player

import android.content.Context
import java.util.Locale

/**
 * The audio and subtitle tracks picked by hand in the player, remembered per channel, film or
 * series (an episode's choice carries to the next ones), so they come back by themselves next
 * time. Kept on this device; the 400 most recent.
 *
 * A choice is stored as "lang:ar" (the track's language), "name:Arabic 5.1" (its name, when it
 * has no language) or "off" (subtitles off).
 */
class TrackMemory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tracks", Context.MODE_PRIVATE)

    class Choice(val audio: String?, val text: String?)

    fun get(key: String): Choice? {
        val raw = prefs.getString(key, null) ?: return null
        val parts = raw.split(SEP)
        if (parts.size < 3) return null
        return Choice(parts[1].ifEmpty { null }, parts[2].ifEmpty { null })
    }

    fun setAudio(key: String, value: String) = put(key, value, null)

    fun setText(key: String, value: String) = put(key, null, value)

    private fun put(key: String, audio: String?, text: String?) {
        val old = get(key)
        val a = audio ?: old?.audio.orEmpty()
        val t = text ?: old?.text.orEmpty()
        val edit = prefs.edit().putString(key, listOf(System.currentTimeMillis().toString(), a, t).joinToString(SEP))
        // Oldest ones out.
        val all = prefs.all
        if (all.size > MAX) {
            all.entries
                .sortedBy { (it.value as? String)?.substringBefore(SEP)?.toLongOrNull() ?: 0L }
                .take(all.size - MAX + 50)
                .forEach { edit.remove(it.key) }
        }
        edit.apply()
    }

    companion object {
        private const val SEP = "\u001F"
        private const val MAX = 400
    }
}

/** Finding the remembered track among what a stream offers. */
object TrackMatch {
    /** What to remember for a track: its language if it has one, else its name. */
    fun valueFor(language: String?, name: String): String {
        val lang = language?.trim()?.takeIf { it.isNotEmpty() && it != "und" && it != "zxx" }
        return if (lang != null) "lang:$lang" else "name:${name.trim()}"
    }

    /** "ar", "ara" and "arb" are all Arabic; "en", "eng" English… */
    fun sameLanguage(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val x = a.trim().lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
        val y = b.trim().lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
        if (x == y) return true
        return iso3(x) != null && iso3(x) == iso3(y)
    }

    private fun iso3(code: String): String? = try {
        // ISO 639-2 bibliographic codes some streams use, mapped to the terminology ones.
        val c = when (code) {
            "ger" -> "deu"
            "fre" -> "fra"
            "dut" -> "nld"
            "chi" -> "zho"
            "per" -> "fas"
            "arb" -> "ara"
            else -> code
        }
        if (c.length == 3) c else Locale(c).isO3Language.ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    /**
     * The index of the track to pick among [tracks] (language or null, name), or -1. A saved
     * language also matches a track whose name says it ("Arabic", "[ara]") when the stream gives
     * no language codes (VLC lists names).
     */
    fun pick(saved: String, tracks: List<Pair<String?, String>>): Int {
        when {
            saved.startsWith("lang:") -> {
                val lang = saved.removePrefix("lang:")
                tracks.indexOfFirst { sameLanguage(it.first, lang) }.let { if (it >= 0) return it }
                val words = languageWords(lang)
                if (words.isEmpty()) return -1
                return tracks.indexOfFirst { (_, name) ->
                    val n = name.lowercase(Locale.ROOT)
                    words.any { w -> Regex("(^|[^a-z])" + Regex.escape(w) + "([^a-z]|$)").containsMatchIn(n) }
                }
            }
            saved.startsWith("name:") -> {
                val name = saved.removePrefix("name:")
                return tracks.indexOfFirst { it.second.trim().equals(name, ignoreCase = true) }
            }
            else -> return -1
        }
    }

    /** "ar" (or "ara") → ["arabic", "ara", "ar"]. */
    private fun languageWords(lang: String): List<String> {
        val code = lang.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
        val three = iso3(code)
        // Names come from the two-letter code ("eng" alone isn't known everywhere).
        val two = if (code.length == 2) code else Locale.getISOLanguages().firstOrNull { l -> runCatching { Locale(l).isO3Language }.getOrNull() == three }
        val name = two?.let { Locale(it).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT) }?.takeIf { it.isNotBlank() && it != two }
        return listOfNotNull(name, three, two, code.takeIf { it.length >= 2 }).distinct()
    }
}
