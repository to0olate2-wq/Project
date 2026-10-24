package app.falak.player.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.TimeZone

/**
 * Subscription watch: an Xtream account as the provider reports it (status, end date,
 * connections), so the app can say how long is left, remind before it ends, and explain a channel
 * refused because every connection is in use. Read from the provider the playlist already uses;
 * kept on the device only.
 */
@Serializable
data class Account(
    /** "Active", "Expired", "Banned", "Disabled"… as the provider says; "Login failed" when it refused the login. */
    val status: String = "",
    /** When it ends, epoch ms; null when it has no end date. */
    val expires: Long? = null,
    val trial: Boolean = false,
    /** Connections in use right now, and how many the account allows (null when not given). */
    val active: Int? = null,
    val max: Int? = null,
    /** When this was read from the provider, epoch ms. */
    val checkedAt: Long = 0,
    /** Which login it was read with (see [Accounts.owner]): a changed login is read afresh. */
    val who: String = "",
) {
    val isActive: Boolean get() = status.isEmpty() || status.equals("Active", ignoreCase = true)

    /** Every connection the account allows is in use. */
    val allInUse: Boolean get() = max != null && active != null && max > 0 && active >= max
}

object Accounts {
    private const val DAY = 86_400_000L

    /** Tells logins apart without keeping them: the playlist's kind, address and username. */
    fun owner(p: Playlist): String = (p.type.name + "|" + p.url.trim() + "|" + p.username.trim()).hashCode().toString(16)

    /** The account from Xtream's "user_info" fields ([field] gives "" for a missing one). */
    fun parse(field: (String) -> String, now: Long): Account {
        if (field("auth").let { it.isNotEmpty() && it != "1" }) return Account(status = "Login failed", checkedAt = now)
        val exp = field("exp_date").trim().toDoubleOrNull()?.toLong()?.takeIf { it > 0 }
        return Account(
            status = field("status").trim(),
            // Seconds, as Xtream panels give it (a few give milliseconds).
            expires = exp?.let { if (it < 100_000_000_000L) it * 1000 else it },
            trial = field("is_trial").trim() == "1",
            active = field("active_cons").trim().toDoubleOrNull()?.toInt(),
            max = field("max_connections").trim().toDoubleOrNull()?.toInt()?.takeIf { it > 0 },
            checkedAt = now,
        )
    }

    /** Calendar days from [now] to [at] in [zone]: 0 = today, 1 = tomorrow, negative = past. */
    fun daysUntil(at: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        fun day(t: Long) = Math.floorDiv(t + zone.getOffset(t), DAY)
        return (day(at) - day(now)).toInt()
    }

    /** "ends today", "ends tomorrow", "ends in 6 days", "ended on [date]". */
    fun endsLabel(expires: Long, now: Long, date: (Long) -> String, zone: TimeZone = TimeZone.getDefault()): String {
        if (expires <= now) return "ended on ${date(expires)}"
        return when (val d = daysUntil(expires, now, zone)) {
            0 -> "ends today"
            1 -> "ends tomorrow"
            else -> "ends in $d days"
        }
    }

    /** Settings' line: "Active · ends Fri, 12 Mar 2027 (139 days left) · 1 of 2 connections in use". */
    fun summary(a: Account, now: Long, date: (Long) -> String, zone: TimeZone = TimeZone.getDefault()): String {
        if (a.status == "Login failed") return "The provider refused the login: check the username and password"
        val parts = ArrayList<String>()
        parts += if (a.trial) "Trial" else a.status.ifEmpty { "Active" }
        val exp = a.expires
        parts += when {
            exp == null -> "no end date"
            exp <= now -> "ended on ${date(exp)}"
            else -> {
                val d = daysUntil(exp, now, zone)
                "ends ${date(exp)} (" + when (d) {
                    0 -> "today"
                    1 -> "tomorrow"
                    else -> "$d days left"
                } + ")"
            }
        }
        if (a.max != null) parts += "${a.active ?: 0} of ${a.max} " + (if (a.max == 1) "connection" else "connections") + " in use"
        return parts.joinToString(" · ")
    }

    /** The reminder to show, if any: ending within 7 days, ended, or not active. */
    fun warning(a: Account, now: Long, date: (Long) -> String, zone: TimeZone = TimeZone.getDefault()): String? {
        if (a.status == "Login failed") return null // the playlist itself says so
        if (!a.isActive) return "Your subscription is ${a.status.lowercase()}. Contact your provider to keep watching."
        val exp = a.expires ?: return null
        if (exp > now && daysUntil(exp, now, zone) > 7) return null
        val ends = endsLabel(exp, now, date, zone)
        return if (exp <= now) "Your subscription $ends. Renew it with your provider to keep watching."
        else "Your subscription $ends (${date(exp)}). Renew it with your provider to keep watching."
    }

    /** Why a channel was refused, when the account explains it (else null: a problem with the stream itself). */
    fun refusal(a: Account, now: Long, date: (Long) -> String): String? = when {
        a.status == "Login failed" -> "Your provider refused the login. Check the username and password in Settings."
        !a.isActive -> "Your subscription is ${a.status.lowercase()}, so your provider won't play channels. Contact your provider."
        a.expires != null && a.expires <= now -> "Your subscription ended on ${date(a.expires)}. Renew it with your provider to keep watching."
        a.allInUse -> "All ${a.max} " + (if (a.max == 1) "connection" else "connections") +
            " of your subscription are in use (another device is watching).\nStop it there, or try again in a minute."
        else -> null
    }
}

/** The last account read for each playlist, on this device. */
object AccountStore {
    private const val PREFS = "accounts"
    private val json = Json { ignoreUnknownKeys = true }

    fun get(context: Context, playlistId: String): Account? = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(playlistId, null)
            ?.let { json.decodeFromString(Account.serializer(), it) }
    } catch (e: Exception) {
        null
    }

    fun put(context: Context, playlistId: String, a: Account) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(playlistId, json.encodeToString(Account.serializer(), a)).apply()
    }

    fun remove(context: Context, playlistId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(playlistId).apply()
    }
}
