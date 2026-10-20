package app.falak.player.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds replay ("catch-up") URLs for past programmes.
 *
 * - Xtream: /timeshift/{user}/{pass}/{minutes}/{YYYY-MM-DD:HH-MM in server time}/{id}.{ext}
 * - M3U catchup="default|append" with catchup-source placeholders ({utc}, {utcend}, {lutc},
 *   {duration}, {offset}, ${start}, ${end}, ${duration}, ${timestamp}, {Y}{m}{d}{H}{M}{S})
 * - M3U catchup="shift" (adds utc= & lutc=) and catchup="flussonic"
 */
object Catchup {

    private val XC_LONG = Regex("^(https?://[^/]+(?:/[^/]+)*?)/live/([^/]+)/([^/]+)/(\\d+)\\.(\\w+)$")
    private val XC_SHORT = Regex("^(https?://[^/]+)/([^/]+)/([^/]+)/(\\d+)$")

    /** True if [prog] on [ch] can be replayed right now (it has started and is inside the archive). */
    fun canReplay(ch: Channel, prog: Program, now: Long): Boolean =
        ch.hasCatchup && prog.start < now - 60_000 && prog.start > now - ch.catchupDays * 86_400_000L

    fun url(ch: Channel, prog: Program, serverTz: String, now: Long): String? {
        val start = prog.start
        val stop = minOf(prog.stop, now)
        val durSec = ((prog.stop - prog.start) / 1000).coerceAtLeast(60)
        val type = ch.catchupType

        if (type == "xc" || type.startsWith("xtream") || type.startsWith("xstream") || type.isEmpty()) {
            xtream(ch.url, start, durSec, serverTz)?.let { return it }
        }
        if (ch.catchupSource.isNotBlank()) {
            val filled = fill(ch.catchupSource, start, stop, now, durSec)
            return when {
                filled.startsWith("http://") || filled.startsWith("https://") -> filled
                type == "append" || filled.startsWith("?") || filled.startsWith("&") -> joinQuery(ch.url, filled.trimStart('?', '&'))
                else -> ch.url + filled
            }
        }
        return when {
            type == "shift" || type == "timeshift" ->
                joinQuery(ch.url, "utc=${start / 1000}&lutc=${now / 1000}")
            type.startsWith("fs") || type.startsWith("flussonic") -> flussonic(ch.url, start, durSec)
            else -> null
        }
    }

    private fun xtream(liveUrl: String, start: Long, durSec: Long, serverTz: String): String? {
        val fmt = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone(serverTz.ifBlank { "UTC" })
        }
        val minutes = (durSec / 60).coerceAtLeast(1)
        val time = fmt.format(Date(start))
        XC_LONG.find(liveUrl)?.destructured?.let { (base, user, pass, id, ext) ->
            return "$base/timeshift/$user/$pass/$minutes/$time/$id.$ext"
        }
        XC_SHORT.find(liveUrl)?.destructured?.let { (base, user, pass, id) ->
            return "$base/timeshift/$user/$pass/$minutes/$time/$id.ts"
        }
        return null
    }

    private fun flussonic(url: String, start: Long, durSec: Long): String? {
        val s = start / 1000
        val path = url.substringBefore('?')
        val query = url.substringAfter('?', "")
        val base = path.substringBeforeLast('/')
        val last = path.substringAfterLast('/')
        val replaced = when {
            last.endsWith(".m3u8") -> "$base/${last.removeSuffix(".m3u8")}-$s-$durSec.m3u8"
            last == "mpegts" || last.endsWith(".ts") -> "$base/timeshift_abs-$s.ts"
            else -> return null
        }
        return if (query.isEmpty()) replaced else "$replaced?$query"
    }

    private fun joinQuery(url: String, q: String): String = url + (if ('?' in url) "&" else "?") + q

    private fun fill(template: String, start: Long, stop: Long, now: Long, durSec: Long): String {
        val local = Date(start)
        fun f(p: String) = SimpleDateFormat(p, Locale.US).format(local)
        val s = start / 1000
        val e = stop / 1000
        val n = now / 1000
        return template
            .replace("\${start}", "$s").replace("{start}", "$s").replace("{utc}", "$s")
            .replace("\${end}", "$e").replace("{end}", "$e").replace("{utcend}", "$e")
            .replace("\${now}", "$n").replace("{now}", "$n").replace("{lutc}", "$n").replace("\${timestamp}", "$n")
            .replace("\${duration}", "$durSec").replace("{duration}", "$durSec")
            .replace("\${offset}", "${n - s}").replace("{offset}", "${n - s}")
            .replace("{Y}", f("yyyy")).replace("{m}", f("MM")).replace("{d}", f("dd"))
            .replace("{H}", f("HH")).replace("{M}", f("mm")).replace("{S}", f("ss"))
    }

    /** A playable item for the replay (seekable, not part of channel zapping). */
    fun asItem(ch: Channel, prog: Program, url: String): Channel = Channel(
        id = "cu:${ch.id}:${prog.start}",
        name = "${ch.name} · ${prog.title}".trimEnd(' ', '·'),
        group = ch.group,
        url = url,
        logo = ch.logo,
        kind = Kind.MOVIE,
        userAgent = ch.userAgent,
        referrer = ch.referrer,
    )
}
