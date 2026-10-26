package app.falak.player.data

import kotlinx.serialization.Serializable

@Serializable
enum class SourceType { M3U_URL, M3U_FILE, XTREAM }

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val type: SourceType,
    /** M3U link, local file path, or Xtream server URL. */
    val url: String,
    val username: String = "",
    val password: String = "",
    /** Optional EPG (XMLTV) link that overrides the one from the provider. */
    val epgUrl: String = "",
    val userAgent: String = "",
    /** Extra XMLTV guides merged after the provider's (fills channels the provider misses). */
    val extraEpg: List<String> = emptyList(),
    /** Fix for guides published with the wrong time zone, in minutes (e.g. -60). */
    val epgShiftMinutes: Int = 0,
    /** The same fix for one guide source (by its link), found by comparing it with the others. */
    val epgSourceShift: Map<String, Int> = emptyMap(),
)

@Serializable
enum class Kind { LIVE, MOVIE, SERIES }

@Serializable
data class Channel(
    val id: String,
    val name: String,
    val group: String,
    val url: String,
    val logo: String = "",
    val tvgId: String = "",
    val kind: Kind = Kind.LIVE,
    val userAgent: String = "",
    val referrer: String = "",
    /** Xtream series id: set on series entries, which open an episode list instead of playing. */
    val seriesId: String = "",
    /** Extra line shown under the name, e.g. "E3 · 45 min". */
    val info: String = "",
    /** When the provider added it (epoch seconds, Xtream only); powers "Newly added". */
    val added: Long = 0,
    val rating: String = "",
    /** Days of replay (catch-up) the provider keeps for this live channel; 0 = none. */
    val catchupDays: Int = 0,
    /** "xc" (Xtream), "default", "append", "shift", "flussonic" … from the playlist. */
    val catchupType: String = "",
    val catchupSource: String = "",
) {
    // A plain field, not `by lazy`: a lazy holder costs ~40 bytes on every entry, which adds up
    // to several MB on playlists with 100,000+ films. Racing threads just compute the same string.
    @kotlinx.serialization.Transient
    private var guideKey: String? = null

    /** Guide key: the tvg-id, or the cleaned-up name when the playlist has no id. */
    val epgKey: String
        get() = guideKey ?: run {
            // Name-only keys carry the country, so "FR: beIN Sports 1" and "AR: beIN Sports 1" stay apart.
            tvgId.lowercase().ifEmpty { "name:" + (countryOf(name, group) ?: "") + ":" + normName(name) }
        }.also { guideKey = it }
    val isSeriesFolder: Boolean get() = seriesId.isNotEmpty() && url.isEmpty()
    val hasCatchup: Boolean get() = kind == Kind.LIVE && catchupDays > 0

    /** Tidy channel list: the quality the provider's name said ("FHD"), shown as a small mark. */
    @kotlinx.serialization.Transient
    var tidyQuality: String? = null
        private set

    /** A copy shown as [display] (Tidy channel list): the same guide, the old name's [quality] as a mark. */
    fun tidied(display: String, quality: String?): Channel {
        val key = epgKey
        return copy(name = display).also {
            it.guideKey = key
            it.tidyQuality = quality
        }
    }
}

@Serializable
data class Program(val start: Long, val stop: Long, val title: String, val desc: String)

class GuideCache(
    val savedAt: Long,
    val programs: Map<String, List<Program>>,
    val matched: Int = 0,
    /** Live channels the guide was matched against. */
    val total: Int = 0,
    /** Every channel the guide sources offer, for fixing a channel's guide by hand. */
    val channels: List<GuideChannel> = emptyList(),
    /**
     * Guide keys of the channels it was matched for (hidden groups are left out). Showing a
     * group again needs a fresh download only if one of its channels isn't in here.
     */
    val scope: Set<String> = emptySet(),
)

/**
 * What one guide source did on its last download: how many of the playlist's live channels it has
 * a guide for, and how many of those no earlier source covered (its real contribution).
 */
@Serializable
data class GuideSourceStat(
    val url: String,
    val label: String,
    val provider: Boolean,
    val matched: Int,
    val added: Int,
    val ok: Boolean,
    /** Channels it shares with the guides before it whose programmes could be compared… */
    val compared: Int = 0,
    /** …and how many of them line up as they are. */
    val agree: Int = 0,
    /** Minutes to add to this source's times to line up with the others (0: no fix needed). */
    val suggestShift: Int = 0,
    /** Shared channels that line up with [suggestShift]. */
    val shiftVotes: Int = 0,
    /** Correction already applied to this source, in minutes. */
    val applied: Int = 0,
    /** Name of the guide it was compared with. */
    val against: String = "",
)

/** A channel listed in a guide source: its XMLTV id, display name and the source's host. */
class GuideChannel(val id: String, val name: String, val source: String)

/** A user-made list of channels, films or series ("Sports", "Kids", …). */
@Serializable
data class FavList(val id: String, val name: String, val ids: List<String> = emptyList())

/** List id of the built-in Favourites list (stored in [AppState.favorites]). */
const val DEFAULT_LIST = "fav"

/**
 * Country/language tags in front of names: "UK: ", "UK | ", "|UK| ", "[UK] ", "(AR) ", "AR ▎ ", "USA - ".
 * Group 1 = bracketed tag, group 2 = bare tag followed by a separator.
 */
private val PREFIX = Regex(
    "^\\s*(?:[\\[(|]\\s*([a-z0-9]{2,3})\\s*[\\])|]\\s*[|:▎┃•\\-–—]*\\s*|([a-z0-9]{2,3})\\s*(?:[|:▎┃•]+\\s*|\\s[-–—]\\s+|[-–—]\\s+))",
)
/** Superscript tags like ᴴᴰ, ᶠᴴᴰ, ⁴ᴷ (phonetic extensions and super/subscripts). */
private val SUPERSCRIPT = Regex("[\\u1D00-\\u1DBF\\u2070-\\u209F]+")
private val BRACKETS = Regex("[\\[(][^\\])]*[\\])]")
private val QUALITY = Regex(
    "\\b(fhd|uhd|hd|sd|4k|8k|hevc|h\\.?26[45]|1080[pi]?|720p?|2160p?|50fps|60fps|fps|hq|lq|backup|vip|raw|multi|" +
        "hdr|dolby|full hd|ultra hd|low|high|alt|bk|orig|original|direct|ᴴᴰ|ᶠᴴᴰ|ᵁᴴᴰ|ᴿᴬᵂ|⁴ᴷ|ˢᴰ)\\b",
)
private val NON_WORD = Regex("[^\\p{L}\\p{N}+]+")
private val COMBINING = Regex("\\p{Mn}+")
/** Non-country prefixes providers put in front of names. */
private val TAGS = setOf(
    "en", "eng", "ara", "arb", "fra", "fre", "ger", "ita", "spa", "esp", "por", "tur", "hin", "urd", "per", "kur",
    "int", "vip", "top", "ott", "4k", "hd", "fhd", "uhd", "sd", "ex", "ext", "pl", "24", "7",
)

/** Words that some guides add and others don't ("Al Jazeera" vs "Al Jazeera Channel"). */
private val GENERIC = setOf("tv", "channel", "network", "television", "the", "live", "east", "hd")

/** Lowercase, accents removed, country prefixes and quality tags dropped, split into words. */
fun nameWords(s: String): List<String> {
    var t = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
    t = COMBINING.replace(t, "")
    t = SUPERSCRIPT.replace(t, " ")
    t = t.replace("&", " and ")
    repeat(2) { // "VIP | UK | BBC One"
        val m = PREFIX.find(t)
        val tag = m?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        if (m != null && tag != null && (canonCountry(tag) != null || tag in TAGS)) t = t.substring(m.range.last + 1)
    }
    t = BRACKETS.replace(t, " ")
    t = QUALITY.replace(t, " ")
    return t.split(NON_WORD).filter { it.isNotEmpty() }
}

/**
 * "AR: MBC 1 HD", "[AE] MBC1 (Backup)" and "mbc1" all become "mbc1", so guides can be
 * matched by channel name when the playlist's tvg-id doesn't match the guide.
 */
fun normName(s: String): String = nameWords(s).joinToString("")

/**
 * The same channel whatever group or quality it is listed in ("AR | beIN Sports 1 FHD" and
 * "beIN SPORTS 1 HD" in an Arabic group): its country and its name without quality tags.
 */
fun sameChannelKey(ch: Channel): String = (countryOf(ch.name, ch.group) ?: "") + ":" + normName(ch.name)

/** 4 = 4K, 3 = FHD, 2 = HD (or not stated), 1 = SD: to try backup streams in a sensible order. */
fun qualityRank(ch: Channel): Int {
    val s = ch.name.lowercase()
    return when {
        RANK_4K.containsMatchIn(s) -> 4
        RANK_FHD.containsMatchIn(s) -> 3
        RANK_SD.containsMatchIn(s) -> 1
        else -> 2
    }
}

// Made once (a big playlist asks for thousands of names in a row).
private val RANK_4K = Regex("\\b(4k|uhd|2160p?|3840p?)\\b")
private val RANK_FHD = Regex("\\b(fhd|1080p?)\\b")
private val RANK_SD = Regex("\\b(sd|480p?|576p?|low)\\b")

/** Like [normName] but also ignores generic words such as "TV" and "Channel". */
fun looseName(s: String): String = nameWords(s).filter { it !in GENERIC }.joinToString("")

private val COUNTRY_WORDS = listOf(
    "united kingdom" to "uk", "england" to "uk", "british" to "uk", "scotland" to "uk",
    "united states" to "us", "usa" to "us", "america" to "us",
    "arabic" to "ar", "arab" to "ar", "عربي" to "ar", "france" to "fr", "french" to "fr",
    "germany" to "de", "deutsch" to "de", "canada" to "ca", "india" to "in", "hindi" to "in",
    "turkey" to "tr", "turkish" to "tr", "türk" to "tr", "spain" to "es", "españa" to "es", "spanish" to "es",
    "italy" to "it", "italia" to "it", "portugal" to "pt", "brazil" to "br", "brasil" to "br",
    "australia" to "au", "egypt" to "eg", "netherlands" to "nl", "dutch" to "nl", "poland" to "pl",
    "pakistan" to "pk", "ireland" to "ie", "mexico" to "mx", "latino" to "mx", "greece" to "gr",
)

/** Real country (or "ar" for Arabic) codes; other prefixes like "EN" or "VIP" say nothing about the guide. */
private val COUNTRY_CODES = setOf(
    "uk", "us", "ca", "au", "nz", "ie", "fr", "de", "at", "ch", "it", "es", "pt", "br", "mx", "ar", "tr", "in",
    "pk", "bd", "nl", "be", "se", "no", "dk", "fi", "pl", "ro", "gr", "ru", "ua", "ae", "sa", "eg", "qa", "kw",
    "lb", "ma", "dz", "tn", "iq", "jo", "om", "bh", "sy", "ps", "ly", "sd", "ye", "il", "ir", "af", "za", "ng",
    "ke", "ph", "id", "my", "sg", "th", "vn", "cn", "hk", "tw", "jp", "kr", "al", "rs", "hr", "ba", "mk", "bg",
    "hu", "cz", "sk", "si", "cl", "co", "pe", "ve", "ec", "uy", "cr", "pa", "do", "pr", "cu", "lat",
)

private val ARABIC_REGION = setOf(
    "ar", "ae", "sa", "eg", "qa", "kw", "lb", "ma", "dz", "tn", "iq", "jo", "om", "bh", "sy", "ps", "ly", "sd", "ye",
)

private fun canonCountry(code: String): String? = when (val c = code.lowercase()) {
    "gb", "eng" -> "uk"
    "usa" -> "us"
    "lat" -> "mx"
    else -> c.takeIf { it in COUNTRY_CODES }
}

/** Country of a playlist channel from its name prefix ("UK: …") or its group ("UK | Sports", "ARABIC"). */
fun countryOf(name: String, group: String): String? {
    for (s in listOf(name, group)) {
        var t = s.lowercase()
        repeat(2) { // "VIP | UK | …": look past one non-country tag
            val m = PREFIX.find(t) ?: return@repeat
            val tag = m.groupValues[1].ifEmpty { m.groupValues[2] }
            canonCountry(tag)?.let { return it }
            if (tag !in TAGS) return@repeat
            t = t.substring(m.range.last + 1)
        }
    }
    val g = group.lowercase()
    return COUNTRY_WORDS.firstOrNull { (w, _) -> g.contains(w) }?.second
}

/** Country suffix of an XMLTV id: "BBCOne.uk" → "uk", "MBC1.ae" → "ae". */
fun guideCountry(guideId: String): String? {
    val tail = guideId.substringAfterLast('.', "")
    return if (tail.length in 2..3) canonCountry(tail) else null
}

/** 2 = same country (or same Arabic region), 1 = unknown on either side, 0 = different countries. */
fun countryAgreement(channel: String?, guide: String?): Int = when {
    channel == null || guide == null -> 1
    channel == guide -> 2
    channel in ARABIC_REGION && guide in ARABIC_REGION -> 2
    else -> 0
}

class Season(val number: Int, val episodes: List<Channel>)

class SeriesInfo(
    val plot: String,
    val cover: String,
    val seasons: List<Season>,
    val backdrop: String = "",
    val genre: String = "",
    val cast: String = "",
    val year: String = "",
    val rating: String = "",
)

/** Movie details (Xtream get_vod_info). */
class VodInfo(
    val plot: String,
    val poster: String,
    val backdrop: String,
    val genre: String,
    val cast: String,
    val director: String,
    val year: String,
    val duration: String,
    val rating: String,
    val trailer: String,
)

@Serializable
data class PlaylistCache(
    val channels: List<Channel>,
    val epgUrl: String = "",
    /** Xtream server time zone (IANA id), needed to request replays at the right time. */
    val serverTz: String = "",
    /** When this copy was downloaded (epoch ms); drives the automatic refresh. */
    val savedAt: Long = 0,
)

@Serializable
data class Settings(
    /** 0 = fit, 1 = fill (stretch), 2 = zoom (crop) */
    val aspect: Int = 0,
    val bigBuffer: Boolean = false,
    /** Decode all audio in software (FFmpeg). Fixes silent Dolby/DTS on boxes whose HDMI passthrough fails. */
    val softwareAudio: Boolean = false,
    val preferHls: Boolean = false,
    val openLast: Boolean = true,
    /** ISO 639 code like "ar" or "en"; empty = what the stream prefers. English unless changed. */
    val audioLang: String = "en",
    /** Show subtitles/closed captions automatically when a stream has them. */
    val subtitlesOn: Boolean = false,
    val subtitleLang: String = "en",
    /** Subtitle size: 0 small, 1 normal, 2 large, 3 extra large. */
    val subSize: Int = 1,
    /** Subtitle text colour: "white" or "yellow". */
    val subColor: String = "white",
    /** Behind the subtitle text: "box" (dark band) or "outline" (dark edge round the letters). */
    val subEdge: String = "box",
    /** Auto captions: where they sit, "bottom" (where the stream's own go), "raised" (clear of news tickers) or "top". */
    val autoCapsPosition: String = "bottom",
    /** Auto captions: how many lines show at once (1 to 3). */
    val autoCapsLines: Int = 2,
    /** Auto captions "in time with the speech": they hear the sound 2 seconds early, each word shown as it's said. */
    val syncedCaptions: Boolean = false,
    /** TVs: switch the screen's refresh rate to the video's frame rate: "off", "vod" (films and series) or "all". */
    val frameRate: String = "vod",
    /** OpenSubtitles.com: free API key (+ optional account for more downloads per day). */
    val osApiKey: String = "",
    val osUser: String = "",
    val osPass: String = "",
    val pin: String = "",
    val lockAdult: Boolean = true,
    /** playlistId -> locked group names */
    val lockedGroups: Map<String, List<String>> = emptyMap(),
    /** playlistId -> group names the user doesn't want to see */
    val hiddenGroups: Map<String, List<String>> = emptyMap(),
    /** Old theme index (before 4.1); no longer used. */
    val theme: Int = 0,
    /** "auto" (light from sunrise, dark from sunset, see SunTimes), "light" or "dark". */
    val themeMode: String = "auto",
    /** "sidebar" (side menu, big continue cards, filter chips; the default) or "classic" (tabs on top, slideshow). */
    val look: String = "sidebar",
    /** Idle or paused for a while: a slideshow of the library with the time (instead of a still picture). */
    val ambient: Boolean = true,
    /** Sidebar look: sections shown as a grid rather than a list. */
    val gridSections: List<String> = listOf("MOVIES", "SERIES"),
    /** Screen layout: "auto" (detected), "tv" (remote control) or "phone" (touch). */
    val layout: String = "auto",
    /** Turn quiet channels up and loud ones down so every channel plays at a similar volume. */
    val levelVolume: Boolean = true,
    /** Night sound (clearer voices, softer bangs): "auto" = from sunset to sunrise where you are, "on", "off". */
    val nightSound: String = "auto",
    /** Series: the next episode starts by itself after a short countdown. */
    val autoNextEpisode: Boolean = true,
    /** More profiles than "Me" (each with its own favorites and Continue watching). Off until switched on. */
    val profilesOn: Boolean = false,
    /** The main profile's name in "Who's watching?". */
    val mainProfileName: String = "Me",
    /** Prayer time reminders: "off" (default), "remind" (a banner), "pause" (banner, and the player pauses). */
    val prayerMode: String = "off",
    /** Calculation method key ([PrayerTimes.Method]); "" = the usual one where the user is. */
    val prayerMethod: String = "",
    /** Asr: "std", "hanafi", or "" for the local custom. */
    val prayerAsr: String = "",
    /** Phone player rotation: "auto" (follows the phone), "landscape" or "portrait". */
    val playerRotation: String = "auto",
    /** Phones: every page turns with the phone, even with rotation locked in the phone's settings. */
    val turnWithPhone: Boolean = true,
    /** Downloads (films and episodes to watch offline) only on Wi-Fi. */
    val downloadWifiOnly: Boolean = true,
    /** Video player: "auto" (picked for this TV), "exo", "exo_soft", "vlc" or "external". */
    val player: String = "auto",
    /** Refresh the playlist in the background when the saved copy is older than this; 0 = never. */
    val autoRefreshHours: Int = 6,
    /** Tell the user (notification + What's new) when a refresh finds new channels, films or series. */
    val notifyNew: Boolean = true,
    /** When the app starts fresh (it was fully closed), play the live channel watched last. */
    val startLastChannel: Boolean = true,
    /** Kids mode: only [kidsGroups] are shown; Settings and group pages need the PIN. */
    val kidsMode: Boolean = false,
    /** playlistId -> the groups kids mode shows. */
    val kidsGroups: Map<String, List<String>> = emptyMap(),
    /** Keep playlists, favorites, hidden groups and Continue watching the same on linked devices. Off until linked. */
    val syncOn: Boolean = false,
    /** Asked Android for permission to show notifications (Android 13+), so it isn't asked again. */
    val askedNotify: Boolean = false,
    /** playlistId -> (channel guide key -> guide channel id chosen by hand; "" = no guide) */
    val guideOverrides: Map<String, Map<String, String>> = emptyMap(),
    /** Tidy channel list: names without provider labels and quality tags ("AR: MBC 1 FHD" → "MBC 1"). */
    val cleanNames: Boolean = false,
    /** Tidy channel list: one entry for a channel a group lists in several qualities. */
    val mergeDuplicates: Boolean = false,
    /** Back from a live channel keeps it playing small in a corner of the lists. */
    val miniPlayer: Boolean = true,
    /** Phones: the sound carries on with the screen off or the app left (lock-screen controls). */
    val listenMode: Boolean = false,
    /** Voice search listens for "ar" or "en"; "" = the device's language. */
    val voiceLang: String = "",
    /** Kids profile: minutes of watching a day; 0 = no limit (see KidsTime). */
    val kidsDailyMinutes: Int = 0,
    /** Kids profile: bedtime, in minutes after midnight; -1 = none. Until [kidsWakeUp]. */
    val kidsBedtime: Int = -1,
    val kidsWakeUp: Int = 7 * 60,
    /** Ramadan mode: "auto" (on during Ramadan) or "off". */
    val ramadanMode: String = "auto",
    /** Ramadan dates moved by hand, by Hijri year: "1448" → "2027-02-07|2027-03-08" (see Ramadan.encode). */
    val ramadanDates: Map<String, String> = emptyMap(),
    /** Phones: a notification this many minutes before Imsak during Ramadan; 0 = none. */
    val suhoorReminder: Int = 0,
)

/** Watch progress of a movie or episode. */
@Serializable
data class Watch(
    val pos: Long,
    val dur: Long,
    // Enough to show it in "Continue watching" and resume it directly (episodes aren't in the main list).
    val title: String = "",
    val image: String = "",
    val url: String = "",
    val kind: Kind = Kind.MOVIE,
    val seriesId: String = "",
    /** When it was last watched (for syncing with other devices); 0 on entries saved before 5.0. */
    val at: Long = 0,
)

@Serializable
data class AppState(
    val playlists: List<Playlist> = emptyList(),
    /** playlistId -> channel ids */
    val favorites: Map<String, List<String>> = emptyMap(),
    /** playlistId -> the user's own lists, in display order */
    val favLists: Map<String, List<FavList>> = emptyMap(),
    val recents: Map<String, List<String>> = emptyMap(),
    val lastPlaylistId: String? = null,
    val settings: Settings = Settings(),
    /** progressKey(playlistId, channelId) -> position */
    val progress: Map<String, Watch> = emptyMap(),
    /** What's new: what playlist refreshes added, newest first (last 40). */
    val notices: List<Notice> = emptyList(),
    /** playlistId -> the live channel watched last (played again when the app starts fresh). */
    val lastLive: Map<String, String> = emptyMap(),
    /** Automatic sync with linked devices. */
    val sync: SyncMeta = SyncMeta(),
    /** Where each show's intro starts and ends ("playlistId|show"), learned when you skip it. */
    val intros: Map<String, Intro> = emptyMap(),
    /** Series you follow, per playlist: told when new episodes appear. */
    val followed: Map<String, List<FollowedSeries>> = emptyMap(),
    /** Extra profiles (Settings → Profiles); the main one isn't listed. */
    val profiles: List<Profile> = emptyList(),
    /** The profile watching: "" = the main one, [KIDS_PROFILE] or a [Profile.id]. */
    val activeProfile: String = "",
    /** Phones paired as remotes (Settings → Phone remote). */
    val remotes: List<RemotePhone> = emptyList(),
    /** Programmes to be reminded of ("Remind me" in the TV guide), kept on this device. */
    val reminders: List<Reminder> = emptyList(),
    /** Words to be alerted about: any programme with them in its name (Settings → Reminders & alerts). */
    val alertWords: List<String> = emptyList(),
)

/** A programme the user asked to be reminded of. */
@Serializable
data class Reminder(
    val playlistId: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val start: Long,
    val stop: Long,
) {
    val key: String get() = "$channelId|$start"
}

/** A phone paired as a remote: a hash of its key (the key itself stays on the phone). */
@Serializable
data class RemotePhone(val hash: String, val label: String, val addedAt: Long, val lastUsed: Long = 0)

/** The Kids profile's id (kids mode). */
const val KIDS_PROFILE = "kids"
const val PROFILE_COLORS = 6

/** Someone else in the house: their own favorites, lists, recents and Continue watching. */
@Serializable
data class Profile(val id: String, val name: String, val color: Int = 0)

/**
 * The key a playlist's personal data (favorites, lists, recents, Continue watching, last channel)
 * is kept under for the profile watching: the playlist id itself for the main profile.
 */
fun AppState.mine(playlistId: String): String = if (activeProfile.isEmpty()) playlistId else "$playlistId@$activeProfile"

/** A show's opening titles, in ms from the start of an episode. */
@Serializable
data class Intro(val start: Long, val end: Long)

/** A followed series and how many episodes it had when last checked. */
@Serializable
data class FollowedSeries(
    val seriesId: String,
    val name: String,
    val logo: String = "",
    val episodes: Int = 0,
    val checkedAt: Long = 0,
)

/** One refresh's worth of new channels, films and series (What's new). */
@Serializable
data class Notice(
    val id: Long,
    val playlistId: String,
    val playlistName: String,
    val live: Int,
    val films: Int,
    val series: Int,
    /** The groups they went into, biggest first (up to 8). */
    val groups: List<NoticeGroup> = emptyList(),
    /** A few of the new items, to show and open (up to 12). */
    val items: List<String> = emptyList(),
    val read: Boolean = false,
    /** New episodes of a followed series ([show] is its name). */
    val episodes: Int = 0,
    val show: String = "",
) {
    /** "27 films · 3 channels · 2 series", or "2 new episodes of The Show" */
    fun summary(): String = if (episodes > 0 && show.isNotBlank()) {
        "$episodes new episode" + (if (episodes == 1) "" else "s") + " of $show"
    } else listOfNotNull(
        films.takeIf { it > 0 }?.let { "$it new film" + if (it == 1) "" else "s" },
        series.takeIf { it > 0 }?.let { "$it new series" },
        live.takeIf { it > 0 }?.let { "$it new channel" + if (it == 1) "" else "s" },
    ).joinToString(" · ")
}

@Serializable
data class NoticeGroup(val kind: Kind, val group: String, val count: Int)

val ADULT_GROUP = Regex("(?i)(xxx|adult|18\\s*\\+|porn)")

fun progressKey(playlistId: String, channelId: String) = "$playlistId|$channelId"

/** 64-bit FNV-1a hash, so ids stay stable across playlist refreshes. */
fun stableId(s: String): String {
    var h = 0xcbf29ce484222325UL.toLong()
    for (c in s) {
        h = h xor c.code.toLong()
        h *= 0x100000001b3L
    }
    return java.lang.Long.toUnsignedString(h, 36)
}

/**
 * Some providers list the same stream twice. Lists key their rows by id and crash on duplicates,
 * so later copies get "_2", "_3"… (the first keeps its id, so favourites stay put).
 */
/**
 * Refreshing rebuilds the whole playlist while the old one is still on screen. Swapping each fresh
 * entry for the identical one already in memory means a big playlist is never held twice (holding
 * two copies of 150,000 films, plus a playing 4K film's buffer, ran phones out of memory).
 */
fun interface Reuse {
    fun of(ch: Channel): Channel

    companion object {
        val NONE = Reuse { it }

        fun from(old: Map<String, Channel>): Reuse =
            if (old.isEmpty()) NONE else Reuse { ch -> old[ch.id]?.takeIf { it == ch } ?: ch }
    }
}

fun ensureUniqueIds(list: List<Channel>): List<Channel> {
    val seen = HashSet<String>(list.size * 2)
    var out: ArrayList<Channel>? = null
    for ((i, ch) in list.withIndex()) {
        if (seen.add(ch.id)) {
            out?.add(ch)
            continue
        }
        if (out == null) out = ArrayList<Channel>(list.size).apply { addAll(list.subList(0, i)) }
        var n = 2
        while (!seen.add("${ch.id}_$n")) n++
        out.add(ch.copy(id = "${ch.id}_$n"))
    }
    return out ?: list
}

/** "tv.example.com" → "tv•••.com": enough to recognise, without showing the server's address. */
fun maskHost(host: String): String {
    val h = host.substringAfter("://").substringBefore('/').substringBefore(':').trim()
    if (h.isEmpty()) return "•••"
    val isIp = h.all { it.isDigit() || it == '.' }
    val tld = h.substringAfterLast('.', "")
    return if (isIp || tld.isEmpty() || tld == h) h.take(2) + "•••" else h.take(2) + "•••." + tld
}

private val URL_IN_TEXT = Regex("(?i)\\b(?:https?|rtmp|rtsp|udp)://[^\\s\"')]+")

/** Removes server addresses from a message before it is shown (errors can contain links or hosts). */
fun hideAddresses(text: String, vararg urls: String): String {
    var t = URL_IN_TEXT.replace(text) { maskHost(it.value) }
    for (u in urls) {
        val host = u.substringAfter("://").substringBefore('/').substringBefore(':').trim()
        if (host.length >= 4) t = t.replace(host, maskHost(host), ignoreCase = true)
    }
    return t
}

/** True for names that are really a server address (older versions named playlists after the host). */
fun looksLikeAddress(name: String): Boolean {
    val n = name.trim().lowercase()
    if (n.contains("://")) return true
    return Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+(:\\d+)?$").matches(n) && n.any { it == '.' }
}
