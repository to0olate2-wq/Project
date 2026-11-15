package app.falak.player.data

import app.falak.player.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Asks api.trakt.tv (see Trakt). Network: call off the main thread. The account's keys live in
 * this device's settings (each device connects on its own: a TV with a code on trakt.tv/activate).
 * People, their films and ratings are kept for the session.
 */
class TraktClient(
    private val http: OkHttpClient,
    private val settings: () -> Settings,
    private val save: ((Settings) -> Settings) -> Unit,
    private val clientId: String = BuildConfig.TRAKT_ID,
    private val secret: String = BuildConfig.TRAKT_SECRET,
) {
    /** The app has its Trakt app (the owner's, built in by CI): ratings, and accounts can connect. */
    val available: Boolean get() = clientId.isNotEmpty() && !testing

    /** A Trakt account is connected on this device. */
    val connected: Boolean get() = available && settings().traktAccess.isNotEmpty()

    private val jsonType = "application/json".toMediaType()

    private fun request(url: String, token: String? = null) = Request.Builder().url(url)
        .header("Content-Type", "application/json")
        .header("trakt-api-version", "2")
        .header("trakt-api-key", clientId)
        .header("User-Agent", "ThePlayer/${BuildConfig.VERSION_NAME}")
        .apply { if (token != null) header("Authorization", "Bearer $token") }

    private fun get(url: String, token: String? = null): String? = try {
        http.newCall(request(url, token).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    } catch (e: Exception) {
        null
    }

    /** HTTP code and text (code 0: no answer). */
    private fun post(url: String, body: String, token: String? = null): Pair<Int, String> = try {
        http.newCall(request(url, token).post(body.toRequestBody(jsonType)).build()).execute().use { r -> r.code to r.body?.string().orEmpty() }
    } catch (e: Exception) {
        0 to ""
    }

    // ---- ratings (the app's key only) ----

    private val scores = java.util.concurrent.ConcurrentHashMap<String, Trakt.Score>()

    fun score(imdb: String, type: String): Trakt.Score? {
        if (!available) return null
        val key = "$type|$imdb"
        scores[key]?.let { return it }
        val s = Trakt.parseScore(get(Trakt.scoreUrl(imdb, type)) ?: return null) ?: return null
        scores[key] = s
        return s
    }

    // ---- connecting an account ----

    fun startConnecting(): Trakt.DeviceCode? {
        if (!available) return null
        val (code, text) = post("${Trakt.AUTH}/oauth/device/code", Trakt.deviceCodeBody(clientId))
        return if (code in 200..299) Trakt.parseDeviceCode(text) else null
    }

    enum class Waiting { CONNECTED, WAITING, SLOW_DOWN, EXPIRED, REFUSED, FAILED }

    /** Asked every [Trakt.DeviceCode.interval] seconds until the code is typed on trakt.tv/activate. */
    fun checkConnecting(d: Trakt.DeviceCode): Waiting {
        val (code, text) = post("${Trakt.AUTH}/oauth/device/token", Trakt.deviceTokenBody(d.deviceCode, clientId, secret))
        return when (code) {
            200 -> {
                val t = Trakt.parseTokens(text, System.currentTimeMillis()) ?: return Waiting.FAILED
                val user = get("${Trakt.API}/users/settings", t.access)?.let { Trakt.parseUser(it) }.orEmpty()
                save { it.copy(traktAccess = t.access, traktRefresh = t.refresh, traktUntil = t.until, traktUser = user) }
                Waiting.CONNECTED
            }
            400 -> Waiting.WAITING
            429 -> Waiting.SLOW_DOWN
            410 -> Waiting.EXPIRED
            // 418: said no; 403: not one of the people the app lets in (a development app: 16 at most).
            418, 403 -> Waiting.REFUSED
            0 -> Waiting.WAITING // (no answer this time: asked again)
            else -> Waiting.FAILED
        }
    }

    fun disconnect() {
        val token = settings().traktAccess
        save { it.copy(traktAccess = "", traktRefresh = "", traktUntil = 0L, traktUser = "") }
        if (token.isNotEmpty() && available) {
            post("${Trakt.AUTH}/oauth/revoke", """{"token":"$token","client_id":"$clientId"""" + (if (secret.isEmpty()) "" else ""","client_secret":"$secret"""") + "}")
        }
    }

    /** The account's key, renewed when it is about to run out; null when not connected (or it was refused). */
    @Synchronized
    private fun token(): String? {
        val s = settings()
        if (!available || s.traktAccess.isEmpty()) return null
        if (System.currentTimeMillis() < s.traktUntil || s.traktRefresh.isEmpty()) return s.traktAccess
        val (code, text) = post("${Trakt.AUTH}/oauth/token", Trakt.refreshBody(s.traktRefresh, clientId, secret))
        val t = if (code == 200) Trakt.parseTokens(text, System.currentTimeMillis()) else null
        if (t != null) {
            save { it.copy(traktAccess = t.access, traktRefresh = t.refresh, traktUntil = t.until) }
            return t.access
        }
        // Refused (the app was taken off on trakt.tv): not connected any more. No answer: the old one.
        if (code in 400..499) save { it.copy(traktAccess = "", traktRefresh = "", traktUntil = 0L, traktUser = "") }
        return if (code in 400..499) null else s.traktAccess
    }

    // ---- what you watch, your watchlist (the account) ----

    /** [action] "start", "pause" or "stop" (stop at 80% or more marks it watched on Trakt). */
    fun scrobble(action: String, video: String, progress: Double): Boolean {
        if (!settings().traktScrobble) return false
        val body = Trakt.scrobbleBody(video, progress) ?: return false
        val token = token() ?: return false
        return post("${Trakt.API}/scrobble/$action", body, token).first in 200..299
    }

    fun watchlist(): List<Trakt.Title>? {
        val token = token() ?: return null
        return Trakt.parseWatchlist(get("${Trakt.API}/sync/watchlist?extended=images", token) ?: return null)
    }

    fun setWatchlist(imdb: String, type: String, on: Boolean): Boolean {
        val token = token() ?: return false
        val url = "${Trakt.API}/sync/watchlist" + if (on) "" else "/remove"
        return post(url, Trakt.watchlistBody(imdb, type), token).first in 200..299
    }

    companion object {
        /** Screen tests: nothing is asked of Trakt. */
        @Volatile var testing = false
    }
}
