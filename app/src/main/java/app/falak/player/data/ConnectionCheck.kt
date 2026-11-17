package app.falak.player.data

import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Settings → Player → Check the connection (8.31; the owner picked it from what other apps have):
 * how fast this device's internet is, whether the provider answers and how quickly, and how fast a
 * channel comes from it. Then one plain line on which part is the slow one.
 */
object ConnectionCheck {
    /** A download measured: bytes read, how long, when the first byte came; [failed] = no answer. */
    data class Measure(val bytes: Long, val ms: Long, val firstByteMs: Long, val failed: String? = null) {
        /** Megabits a second (0 when too little came to say). */
        val mbps: Double get() = if (ms <= 0 || bytes < 50_000) 0.0 else bytes * 8.0 / 1000.0 / ms
    }

    /** A test file Cloudflare keeps for speed tests: [bytes] long. */
    fun speedUrl(bytes: Long) = "https://speed.cloudflare.com/__down?bytes=$bytes"

    /**
     * Reads [url] for at most [maxBytes] or [maxMs] (a live channel never ends), timing it.
     * Network: call off the main thread.
     */
    fun measure(http: OkHttpClient, url: String, maxBytes: Long, maxMs: Long, userAgent: String = Repository.DEFAULT_UA): Measure {
        val t0 = System.nanoTime()
        fun since() = (System.nanoTime() - t0) / 1_000_000
        return try {
            val req = Request.Builder().url(url).header("User-Agent", userAgent.ifBlank { Repository.DEFAULT_UA }).build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return Measure(0, since(), since(), "HTTP ${r.code}")
                val first = since()
                val input = r.body?.byteStream() ?: return Measure(0, since(), first, "no answer")
                val buf = ByteArray(64 * 1024)
                var total = 0L
                val start = System.nanoTime()
                while (total < maxBytes && (System.nanoTime() - t0) / 1_000_000 < maxMs) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                }
                // The rate counts from the first byte (the wait for the answer is its own figure).
                Measure(total, ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1), first)
            }
        } catch (e: Exception) {
            Measure(0, since(), since(), e.javaClass.simpleName.removeSuffix("Exception").ifBlank { "error" })
        }
    }

    fun rate(mbps: Double): String = when {
        mbps <= 0 -> "—"
        mbps < 10 -> String.format(java.util.Locale.US, "%.1f Mb/s", mbps)
        else -> "${Math.round(mbps)} Mb/s"
    }

    /** What that speed is good for. */
    fun goodFor(mbps: Double): String = when {
        mbps <= 0 -> ""
        mbps >= 25 -> "enough for 4K"
        mbps >= 8 -> "enough for full HD"
        mbps >= 4 -> "enough for HD"
        mbps >= 2 -> "enough for SD only"
        else -> "too slow for smooth video"
    }

    /**
     * Which part is slow, in one line: this device's internet ([net]), the provider's answer
     * ([provider], ms to answer; null: couldn't be asked) or how fast a channel comes ([stream]).
     */
    fun verdict(net: Measure?, provider: Measure?, stream: Measure?): String {
        val netOk = net != null && net.failed == null && net.mbps >= 8
        val netSlow = net != null && net.failed == null && net.mbps in 0.01..4.0
        val noNet = net != null && net.failed != null
        val providerDown = provider != null && provider.failed != null
        val providerSlow = provider != null && provider.failed == null && provider.firstByteMs > 3_000
        val streamSlow = stream != null && stream.failed == null && stream.mbps in 0.01..4.0
        val streamFailed = stream != null && stream.failed != null
        return when {
            noNet && providerDown -> "This device can't reach the internet. Check the Wi-Fi or cable."
            netSlow -> "Your internet is the slow part: video may stop to load. Try closer to the router, a cable, or the Larger buffer setting."
            providerDown -> "Your internet works, but your provider didn't answer. Its server may be down for now, or your subscription may need checking."
            netOk && (streamSlow || providerSlow) -> "Your internet is fine; your provider is sending slowly right now. Try again later, or ask your provider."
            netOk && streamFailed -> "Your internet is fine, but the channel didn't open. Your provider may allow fewer screens at once, or the channel is off."
            netOk -> "Your internet and your provider both look fine. If one channel stutters, it may be that channel; Prefer HLS or Larger buffer can help."
            else -> "The check couldn't finish. Try again in a moment."
        }
    }
}
