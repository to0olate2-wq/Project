package app.falak.player.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Automatic sync between linked devices: the state every device sends and the rules that merge
 * it. Each part of each playlist (the playlist itself, favorites, lists, recently watched, hidden,
 * locked and Kids groups, guide fixes, followed series, intros) carries the time it last changed;
 * the newer one wins. Favorites, lists and recently watched go per profile. Continue watching is
 * merged entry by entry. Each setting, the profiles, reminders and alert words are dated too (also
 * while not linked, so on linking the device where they were last changed wins). Removed
 * playlists and cleared entries leave a dated marker so another device doesn't bring them back.
 * A device that has never had something of its own (nothing there, never changed) takes the
 * other device's.
 */
@Serializable
data class SyncMeta(
    /** This device, for the others to tell it apart. */
    val deviceId: String = "",
    /** Secret shared by the linked devices (Base64); empty = not linked. */
    val key: String = "",
    /** Linked devices this one has synced with: device id -> name and when. */
    val peers: Map<String, SyncPeer> = emptyMap(),
    /** "<part>|<playlist id>" (or "<part>|<playlist id>@<profile>") -> when that part last changed. */
    val stamps: Map<String, Long> = emptyMap(),
    /** Removed playlists: sync id -> when. */
    val removed: Map<String, Long> = emptyMap(),
    /** Continue-watching entries cleared (finished or removed): "<sync id>|<item id>" -> when. */
    val progressGone: Map<String, Long> = emptyMap(),
    /** This device's playlist id -> the id the other devices know it by, where they differ. */
    val alias: Map<String, String> = emptyMap(),
)

@Serializable
data class SyncPeer(val name: String, val lastSync: Long = 0)

/** What one device sends another (playlists by sync id). */
@Serializable
data class SyncSnapshot(
    val v: Int = 1,
    val deviceId: String = "",
    val device: String = "",
    val playlists: List<SyncedPlaylist> = emptyList(),
    val removed: Map<String, Long> = emptyMap(),
    val parts: Map<String, SyncPart> = emptyMap(),
    val progress: Map<String, Watch> = emptyMap(),
    val progressGone: Map<String, Long> = emptyMap(),
    /** The Kids profile's watching today on the sending device (see KidsClock), so the limits count every device. */
    val kids: KidsShare? = null,
)

@Serializable
data class SyncedPlaylist(val at: Long, val playlist: Playlist)

/** One part of the data: only the field for its kind is set. */
@Serializable
data class SyncPart(
    val at: Long,
    val ids: List<String>? = null,
    val lists: List<FavList>? = null,
    val map: Map<String, String>? = null,
    /** Since 8.2: settings, profiles, reminders, followed series, intros. */
    val json: JsonElement? = null,
)

object SyncMerge {
    const val PL = "pl"
    const val FAV = "fav"
    const val LISTS = "lists"
    const val HIDDEN = "hidden"
    const val GUIDE = "guide"
    const val RECENTS = "recents"
    const val LOCKED = "locked"
    const val KIDS_GROUPS = "kidsg"
    const val FOLLOWED = "followed"
    const val INTROS = "intros"

    /** The parts of a playlist. */
    private val PARTS = setOf(PL, FAV, LISTS, HIDDEN, GUIDE, RECENTS, LOCKED, KIDS_GROUPS, FOLLOWED, INTROS)

    /** Parts kept per profile: "<playlist id>" for the main one, "<playlist id>@<profile>" for the others. */
    private val PER_PROFILE = setOf(FAV, LISTS, RECENTS)

    /** Whole-app parts: one per setting ("set|<name>"), the profiles, reminders and alert words. */
    const val SET = "set"
    const val PROFILES = "profiles|*"
    const val REMINDERS = "reminders|*"
    const val ALERTS = "alerts|*"

    private const val KEEP_MARKERS = 60L * 86_400_000L
    private const val MAX_PROGRESS = 300

    /** Settings that belong to one device (its screen, player, network, lock) or sync as parts of a playlist. */
    private val DEVICE_SETTINGS = setOf(
        "aspect", "bigBuffer", "softwareAudio", "preferHls", "frameRate", "theme", "layout", "player",
        "downloadWifiOnly", "kidsMode", "syncOn", "askedNotify",
        "hiddenGroups", "guideOverrides", "lockedGroups", "kidsGroups",
    )

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val followedSerializer = ListSerializer(FollowedSeries.serializer())
    private val introsSerializer = MapSerializer(String.serializer(), Intro.serializer())
    private val profilesSerializer = ListSerializer(Profile.serializer())
    private val remindersSerializer = ListSerializer(Reminder.serializer())

    /** The settings that sync, by name, as they'd be written. */
    internal fun sharedSettings(s: Settings): Map<String, JsonElement> =
        json.encodeToJsonElement(Settings.serializer(), s).jsonObject.filterKeys { it !in DEVICE_SETTINGS }

    private val defaultSettings: Map<String, JsonElement> by lazy { sharedSettings(Settings()) }

    fun syncId(s: AppState, localId: String): String = s.sync.alias[localId] ?: localId

    /** "<playlist id>[@<profile>]" in the ids the other devices know. */
    private fun syncScope(s: AppState, scope: String): String {
        val at = scope.indexOf('@')
        return if (at < 0) syncId(s, scope) else syncId(s, scope.substring(0, at)) + scope.substring(at)
    }

    /** A Continue-watching key ("<playlist id>[@<profile>]|<item>") as the other devices know it. */
    fun syncProgressKey(s: AppState, key: String): String = syncScope(s, key.substringBefore('|')) + "|" + key.substringAfter('|')

    /** Stamps that aren't about a playlist (kept when linking or unlinking). */
    fun wholeAppStamps(stamps: Map<String, Long>): Map<String, Long> =
        stamps.filterKeys { it.startsWith("$SET|") || it == PROFILES || it == REMINDERS || it == ALERTS }

    /** Two playlists from the same source (same link and login): the same playlist on two devices. */
    fun sameSource(a: Playlist, b: Playlist): Boolean =
        a.type == b.type && a.type != SourceType.M3U_FILE &&
            a.url.trimEnd('/').equals(b.url.trimEnd('/'), ignoreCase = true) && a.username == b.username

    private fun introsOf(s: AppState, id: String): Map<String, Intro> {
        val prefix = "$id|"
        return s.intros.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
    }

    /**
     * Dates the parts a local change touched (called on every change). The whole-app parts are
     * dated always; a playlist's parts only while linked.
     */
    fun stamp(old: AppState, new: AppState, now: Long): AppState {
        if (old === new) return new
        var stamps: HashMap<String, Long>? = null
        fun bump(key: String) {
            val m = stamps ?: HashMap(new.sync.stamps).also { stamps = it }
            m[key] = now
        }
        if (old.settings != new.settings) {
            val a = sharedSettings(old.settings)
            for ((k, v) in sharedSettings(new.settings)) if (a[k] != v) bump("$SET|$k")
        }
        if (old.profiles != new.profiles) bump(PROFILES)
        if (old.reminders != new.reminders) bump(REMINDERS)
        if (old.alertWords != new.alertWords) bump(ALERTS)

        var removed: HashMap<String, Long>? = null
        if (new.sync.key.isNotEmpty()) {
            val before = old.playlists.associateBy { it.id }
            val ids = new.playlists.mapTo(HashSet()) { it.id }
            for (p in new.playlists) {
                val id = p.id
                val o = before[id]
                if (o == null || o != p) bump("$PL|$id")
                if (old.settings.hiddenGroups[id] != new.settings.hiddenGroups[id]) bump("$HIDDEN|$id")
                if (old.settings.guideOverrides[id] != new.settings.guideOverrides[id]) bump("$GUIDE|$id")
                if (old.settings.lockedGroups[id] != new.settings.lockedGroups[id]) bump("$LOCKED|$id")
                if (old.settings.kidsGroups[id] != new.settings.kidsGroups[id]) bump("$KIDS_GROUPS|$id")
                if (old.followed[id] != new.followed[id]) bump("$FOLLOWED|$id")
                if (old.intros !== new.intros && introsOf(old, id) != introsOf(new, id)) bump("$INTROS|$id")
            }
            fun perProfile(kind: String, a: Map<String, Any>, b: Map<String, Any>) {
                if (a === b) return
                for (k in a.keys + b.keys) if (k.substringBefore('@') in ids && a[k] != b[k]) bump("$kind|$k")
            }
            perProfile(FAV, old.favorites, new.favorites)
            perProfile(LISTS, old.favLists, new.favLists)
            perProfile(RECENTS, old.recents, new.recents)

            if (old.playlists !== new.playlists) {
                for (o in old.playlists) {
                    if (o.id in ids) continue
                    val r = removed ?: HashMap(new.sync.removed).also { removed = it }
                    r[syncId(old, o.id)] = now
                    val m = stamps ?: HashMap(new.sync.stamps).also { stamps = it }
                    dropStamps(m, o.id)
                }
            }
        }
        if (stamps == null && removed == null) return new
        return new.copy(sync = new.sync.copy(stamps = stamps ?: new.sync.stamps, removed = removed ?: new.sync.removed))
    }

    /** Forgets the dates of every part of playlist [id] (all profiles). */
    private fun dropStamps(m: MutableMap<String, Long>, id: String) {
        m.keys.removeAll { key ->
            val target = key.substringAfter('|')
            key.substringBefore('|') in PARTS && (target == id || target.startsWith("$id@"))
        }
    }

    /**
     * Every part of every playlist dated [now]: this device's state wins on the next sync. Parts
     * that are empty here aren't, so linking a new device doesn't wipe them on the other one.
     */
    fun touchAll(s: AppState, now: Long): AppState {
        val stamps = HashMap(s.sync.stamps)
        for (p in s.playlists) {
            val id = p.id
            listOf(PL, FAV, LISTS, HIDDEN, GUIDE).forEach { stamps["$it|$id"] = now }
            if (!s.settings.lockedGroups[id].isNullOrEmpty()) stamps["$LOCKED|$id"] = now
            if (!s.settings.kidsGroups[id].isNullOrEmpty()) stamps["$KIDS_GROUPS|$id"] = now
            if (!s.followed[id].isNullOrEmpty()) stamps["$FOLLOWED|$id"] = now
            if (introsOf(s, id).isNotEmpty()) stamps["$INTROS|$id"] = now
        }
        val ids = s.playlists.mapTo(HashSet()) { it.id }
        for ((kind, m) in listOf(FAV to s.favorites, LISTS to s.favLists, RECENTS to s.recents)) {
            for ((k, v) in m) {
                if (k.substringBefore('@') !in ids || v.isEmpty()) continue
                stamps["$kind|$k"] = now
            }
        }
        return s.copy(sync = s.sync.copy(stamps = stamps))
    }

    fun snapshot(s: AppState, device: String): SyncSnapshot {
        val sid = { id: String -> syncId(s, id) }
        val parts = HashMap<String, SyncPart>()
        fun at(key: String) = s.sync.stamps[key] ?: 0L
        val ids = s.playlists.mapTo(HashSet()) { it.id }
        for (p in s.playlists) {
            val id = p.id
            val key = sid(id)
            parts["$HIDDEN|$key"] = SyncPart(at("$HIDDEN|$id"), ids = s.settings.hiddenGroups[id].orEmpty())
            parts["$GUIDE|$key"] = SyncPart(at("$GUIDE|$id"), map = s.settings.guideOverrides[id].orEmpty())
            parts["$LOCKED|$key"] = SyncPart(at("$LOCKED|$id"), ids = s.settings.lockedGroups[id].orEmpty())
            parts["$KIDS_GROUPS|$key"] = SyncPart(at("$KIDS_GROUPS|$id"), ids = s.settings.kidsGroups[id].orEmpty())
            parts["$FOLLOWED|$key"] = SyncPart(at("$FOLLOWED|$id"), json = json.encodeToJsonElement(followedSerializer, s.followed[id].orEmpty()))
            parts["$INTROS|$key"] = SyncPart(at("$INTROS|$id"), json = json.encodeToJsonElement(introsSerializer, introsOf(s, id)))
            // The main profile's always go (empty too); other profiles' where they have some.
            parts["$FAV|$key"] = SyncPart(at("$FAV|$id"), ids = s.favorites[id].orEmpty())
            parts["$LISTS|$key"] = SyncPart(at("$LISTS|$id"), lists = s.favLists[id].orEmpty())
            parts["$RECENTS|$key"] = SyncPart(at("$RECENTS|$id"), ids = s.recents[id].orEmpty())
        }
        for ((k, v) in s.favorites) if ('@' in k && k.substringBefore('@') in ids) parts["$FAV|${syncScope(s, k)}"] = SyncPart(at("$FAV|$k"), ids = v)
        for ((k, v) in s.favLists) if ('@' in k && k.substringBefore('@') in ids) parts["$LISTS|${syncScope(s, k)}"] = SyncPart(at("$LISTS|$k"), lists = v)
        for ((k, v) in s.recents) if ('@' in k && k.substringBefore('@') in ids) parts["$RECENTS|${syncScope(s, k)}"] = SyncPart(at("$RECENTS|$k"), ids = v)

        for ((name, value) in sharedSettings(s.settings)) parts["$SET|$name"] = SyncPart(at("$SET|$name"), json = value)
        parts[PROFILES] = SyncPart(at(PROFILES), json = json.encodeToJsonElement(profilesSerializer, s.profiles))
        parts[REMINDERS] = SyncPart(
            at(REMINDERS),
            json = json.encodeToJsonElement(remindersSerializer, s.reminders.filter { it.playlistId in ids }.map { it.copy(playlistId = sid(it.playlistId)) }),
        )
        parts[ALERTS] = SyncPart(at(ALERTS), ids = s.alertWords)

        return SyncSnapshot(
            deviceId = s.sync.deviceId,
            device = device,
            playlists = s.playlists.map { p ->
                // A playlist loaded from a file keeps its contents on this device: only its name and settings go.
                val shared = p.copy(id = sid(p.id), url = if (p.type == SourceType.M3U_FILE) "" else p.url)
                SyncedPlaylist(s.sync.stamps["$PL|${p.id}"] ?: 0L, shared)
            },
            removed = s.sync.removed,
            parts = parts,
            // Every profile's (older versions take only the main one's: they don't know the others' keys).
            progress = s.progress.filterKeys { it.substringBefore('|').substringBefore('@') in ids }
                .mapKeys { (k, _) -> syncProgressKey(s, k) },
            progressGone = s.sync.progressGone,
        )
    }

    /** Whether the part sent has anything in it. */
    private fun SyncPart.hasData(): Boolean {
        val j = this.json
        return when {
            ids != null -> ids.isNotEmpty()
            lists != null -> lists.isNotEmpty()
            map != null -> map.isNotEmpty()
            j is JsonObject -> j.isNotEmpty()
            j is kotlinx.serialization.json.JsonArray -> j.isNotEmpty()
            else -> false
        }
    }

    /**
     * Merges what another device sent into [s]. Returns the new state and the ids of playlists it
     * removed here (their cached files can go).
     */
    fun merge(s: AppState, r: SyncSnapshot, now: Long): Pair<AppState, List<String>> {
        val stamps = HashMap(s.sync.stamps)
        val alias = HashMap(s.sync.alias)
        val removed = HashMap(s.sync.removed)
        for ((k, t) in r.removed) if (t > (removed[k] ?: 0L)) removed[k] = t
        val playlists = s.playlists.toMutableList()
        // The other device's sync id -> the playlist id here.
        val toLocal = HashMap<String, String>()
        // Playlists that arrive with this merge take the other device's favorites and the rest as they are.
        val fresh = HashSet<String>()
        val sidOf = { id: String -> alias[id] ?: id }

        for (rp in r.playlists) {
            val rid = rp.playlist.id
            var i = playlists.indexOfFirst { sidOf(it.id) == rid }
            if (i < 0) {
                // Set up separately on both devices: the same playlist once they're linked. Both
                // devices settle on the smaller of the two ids.
                i = playlists.indexOfFirst { sameSource(it, rp.playlist) && it.id !in toLocal.values }
                if (i >= 0) {
                    val local = playlists[i]
                    if (rid < sidOf(local.id)) {
                        if (rid == local.id) alias.remove(local.id) else alias[local.id] = rid
                    }
                }
            }
            if (i >= 0) {
                val local = playlists[i]
                toLocal[rid] = local.id
                if (rp.at > (stamps["$PL|${local.id}"] ?: 0L)) {
                    playlists[i] = rp.playlist.copy(id = local.id, url = if (local.type == SourceType.M3U_FILE) local.url else rp.playlist.url)
                    stamps["$PL|${local.id}"] = rp.at
                }
            } else {
                // New here. Playlists from a file come over with "Your devices" (they need the file).
                if (rp.playlist.type == SourceType.M3U_FILE) continue
                if ((removed[rid] ?: -1L) >= rp.at) continue
                playlists += rp.playlist
                stamps["$PL|$rid"] = rp.at
                toLocal[rid] = rid
                fresh += rid
            }
        }

        // Removed on another device after its last change here (a newer change keeps it).
        val gone = playlists.filter { p ->
            val t = removed[sidOf(p.id)] ?: return@filter false
            t > (stamps["$PL|${p.id}"] ?: 0L)
        }.map { it.id }
        var out = s.copy(playlists = playlists)
        for (id in gone) {
            out = withoutPlaylist(out, id)
            dropStamps(stamps, id)
            alias.remove(id)
        }
        val localOfSid = playlists.filter { it.id !in gone }.associate { sidOf(it.id) to it.id }
        fun localId(sid: String): String? = (toLocal[sid] ?: localOfSid[sid])?.takeIf { it !in gone }

        // A whole-app part: the newer side wins; a device that never had its own takes the other's.
        fun takes(key: String, part: SyncPart, emptyHere: Boolean): Boolean {
            val mine = stamps[key] ?: 0L
            return part.at > mine || (mine == 0L && emptyHere && part.hasData())
        }

        // ---- profiles (before their favorites and the rest) ----
        var profiles = out.profiles
        r.parts[PROFILES]?.let { part ->
            val theirs = part.json?.let { runCatching { json.decodeFromJsonElement(profilesSerializer, it) }.getOrNull() } ?: return@let
            if (takes(PROFILES, part, profiles.isEmpty())) {
                profiles = theirs
                if (part.at > 0) stamps[PROFILES] = part.at
            }
        }
        val profileIds = profiles.mapTo(HashSet()) { it.id }.apply { add(KIDS_PROFILE) }
        // Profiles removed on the other device: their favorites and the rest go here too.
        val droppedProfiles = out.profiles.map { it.id }.filter { it !in profileIds }

        // ---- a playlist's parts: the newer side wins ----
        val favorites = out.favorites.toMutableMap()
        val favLists = out.favLists.toMutableMap()
        val recents = out.recents.toMutableMap()
        val hidden = out.settings.hiddenGroups.toMutableMap()
        val guide = out.settings.guideOverrides.toMutableMap()
        val locked = out.settings.lockedGroups.toMutableMap()
        val kidsGroups = out.settings.kidsGroups.toMutableMap()
        val followed = out.followed.toMutableMap()
        val intros = out.intros.toMutableMap()
        for ((key, part) in r.parts) {
            val kind = key.substringBefore('|')
            if (kind !in PARTS) continue
            val target = key.substringAfter('|')
            val at = target.indexOf('@')
            val profile = if (at < 0) "" else target.substring(at + 1)
            if (profile.isNotEmpty() && (kind !in PER_PROFILE || profile !in profileIds)) continue
            val base = localId(if (at < 0) target else target.substring(0, at)) ?: continue
            val local = if (profile.isEmpty()) base else "$base@$profile"
            val emptyHere = when (kind) {
                FAV -> favorites[local].isNullOrEmpty()
                LISTS -> favLists[local].isNullOrEmpty()
                RECENTS -> recents[local].isNullOrEmpty()
                HIDDEN -> hidden[local].isNullOrEmpty()
                GUIDE -> guide[local].isNullOrEmpty()
                LOCKED -> locked[local].isNullOrEmpty()
                KIDS_GROUPS -> kidsGroups[local].isNullOrEmpty()
                FOLLOWED -> followed[local].isNullOrEmpty()
                INTROS -> intros.keys.none { it.startsWith("$local|") }
                else -> false
            }
            val mine = stamps["$kind|$local"] ?: 0L
            val take = (base in fresh && profile.isEmpty()) || part.at > mine || (mine == 0L && emptyHere && part.hasData())
            if (!take) continue
            when (kind) {
                FAV -> favorites[local] = part.ids ?: continue
                LISTS -> favLists[local] = part.lists ?: continue
                RECENTS -> recents[local] = part.ids ?: continue
                HIDDEN -> hidden[local] = part.ids ?: continue
                GUIDE -> guide[local] = part.map ?: continue
                LOCKED -> locked[local] = part.ids ?: continue
                KIDS_GROUPS -> kidsGroups[local] = part.ids ?: continue
                FOLLOWED -> followed[local] = part.json?.let { runCatching { json.decodeFromJsonElement(followedSerializer, it) }.getOrNull() } ?: continue
                INTROS -> {
                    val theirs = part.json?.let { runCatching { json.decodeFromJsonElement(introsSerializer, it) }.getOrNull() } ?: continue
                    intros.keys.removeAll { it.startsWith("$local|") }
                    for ((show, intro) in theirs) intros["$local|$show"] = intro
                }
                else -> continue
            }
            if (part.at > mine) stamps["$kind|$local"] = part.at
        }

        // ---- settings, one by one ----
        var settings = out.settings
        val mySettings = sharedSettings(settings)
        val takenSettings = HashMap<String, JsonElement>()
        for ((key, part) in r.parts) {
            if (key.substringBefore('|') != SET) continue
            val name = key.substringAfter('|')
            if (name in DEVICE_SETTINGS || name !in mySettings) continue
            val value = part.json ?: continue
            val current = mySettings[name]
            if (value == current) {
                if (part.at > (stamps[key] ?: 0L)) stamps[key] = part.at
                continue
            }
            val mine = stamps[key] ?: 0L
            // Never changed here and still as it came: the other device's choice.
            if (part.at > mine || (mine == 0L && current == defaultSettings[name])) {
                takenSettings[name] = value
                if (part.at > mine) stamps[key] = part.at
            }
        }
        if (takenSettings.isNotEmpty()) settings = withSettings(settings, takenSettings)
        settings = settings.copy(hiddenGroups = hidden, guideOverrides = guide, lockedGroups = locked, kidsGroups = kidsGroups)

        // ---- reminders and alert words ----
        var reminders = out.reminders
        r.parts[REMINDERS]?.let { part ->
            val theirs = part.json?.let { runCatching { json.decodeFromJsonElement(remindersSerializer, it) }.getOrNull() } ?: return@let
            if (takes(REMINDERS, part, reminders.isEmpty())) {
                reminders = theirs.mapNotNull { rem -> localId(rem.playlistId)?.let { rem.copy(playlistId = it) } }
                if (part.at > 0) stamps[REMINDERS] = part.at
            }
        }
        var alertWords = out.alertWords
        r.parts[ALERTS]?.let { part ->
            val theirs = part.ids ?: return@let
            if (takes(ALERTS, part, alertWords.isEmpty())) {
                alertWords = theirs
                if (part.at > 0) stamps[ALERTS] = part.at
            }
        }

        // ---- Continue watching: entry by entry, the latest wins; a newer "cleared" marker removes it ----
        val progressGone = HashMap(s.sync.progressGone)
        for ((k, t) in r.progressGone) if (t > (progressGone[k] ?: 0L)) progressGone[k] = t
        // Keys come as "<sync id>[@<profile>]|<item id>", in the other device's ids or this one's.
        val localKey = { key: String ->
            val scope = key.substringBefore('|')
            val at = scope.indexOf('@')
            val profile = if (at < 0) "" else scope.substring(at + 1)
            if (profile.isNotEmpty() && profile !in profileIds) {
                null
            } else {
                localId(if (at < 0) scope else scope.substring(0, at))?.let { (if (profile.isEmpty()) it else "$it@$profile") + "|" + key.substringAfter('|') }
            }
        }
        val progress = LinkedHashMap(out.progress)
        for ((k, w) in r.progress) {
            val lk = localKey(k) ?: continue
            val mine = progress[lk]
            val cleared = progressGone[k] ?: -1L
            if (w.at > cleared && (mine == null || w.at > mine.at)) progress[lk] = w
        }
        for ((k, t) in progressGone) {
            val lk = localKey(k) ?: continue
            val w = progress[lk] ?: continue
            if (w.at < t) progress.remove(lk)
        }
        // Oldest first, as the app keeps them; at most the usual number.
        val ordered = progress.entries.sortedBy { it.value.at }.takeLast(MAX_PROGRESS)
        val newProgress = LinkedHashMap<String, Watch>().apply { ordered.forEach { put(it.key, it.value) } }

        val peers = s.sync.peers.toMutableMap()
        if (r.deviceId.isNotEmpty()) peers[r.deviceId] = SyncPeer(r.device, now)

        out = out.copy(
            favorites = favorites,
            favLists = favLists,
            recents = recents,
            followed = followed,
            intros = if (intros == out.intros) out.intros else intros,
            profiles = profiles,
            reminders = reminders,
            alertWords = alertWords,
            progress = if (newProgress == out.progress) out.progress else newProgress,
            settings = if (settings == out.settings) out.settings else settings,
            sync = out.sync.copy(
                stamps = stamps,
                alias = alias,
                peers = peers,
                removed = removed.filterValues { now - it < KEEP_MARKERS },
                progressGone = progressGone.filterValues { now - it < KEEP_MARKERS },
            ),
        )
        for (id in droppedProfiles) out = withoutProfileData(out, id)
        if (out.activeProfile.isNotEmpty() && out.activeProfile !in profileIds) out = out.copy(activeProfile = "")
        return out to gone
    }

    /** [s] with these settings (by name) as another device has them; any that don't fit this version are left. */
    private fun withSettings(s: Settings, taken: Map<String, JsonElement>): Settings {
        val mine = json.encodeToJsonElement(Settings.serializer(), s).jsonObject
        runCatching { return json.decodeFromJsonElement(Settings.serializer(), JsonObject(mine + taken)) }
        // One of them doesn't fit (a newer version's): take the others one at a time.
        var out = mine
        for ((k, v) in taken) {
            val tried = JsonObject(out + (k to v))
            if (runCatching { json.decodeFromJsonElement(Settings.serializer(), tried) }.isSuccess) out = tried
        }
        return json.decodeFromJsonElement(Settings.serializer(), out)
    }

    /** [s] without what profile [id] kept (its favorites, lists, recently watched, last channel, Continue watching). */
    fun withoutProfileData(s: AppState, id: String): AppState {
        val tag = "@$id"
        fun <V> drop(m: Map<String, V>) = m.filterKeys { !it.endsWith(tag) }
        return s.copy(
            favorites = drop(s.favorites),
            favLists = drop(s.favLists),
            recents = drop(s.recents),
            lastLive = drop(s.lastLive),
            progress = s.progress.filterKeys { !it.substringBefore('|').endsWith(tag) },
        )
    }

    /** [s] without playlist [id] and everything that belonged to it. */
    fun withoutPlaylist(s: AppState, id: String): AppState = s.copy(
        playlists = s.playlists.filterNot { it.id == id },
        // The main profile's keys are the id; other profiles' are "id@profile".
        favorites = s.favorites.filterKeys { it != id && !it.startsWith("$id@") },
        favLists = s.favLists.filterKeys { it != id && !it.startsWith("$id@") },
        settings = s.settings.copy(
            guideOverrides = s.settings.guideOverrides - id,
            hiddenGroups = s.settings.hiddenGroups - id,
            lockedGroups = s.settings.lockedGroups - id,
            kidsGroups = s.settings.kidsGroups - id,
        ),
        recents = s.recents.filterKeys { it != id && !it.startsWith("$id@") },
        lastPlaylistId = s.lastPlaylistId.takeIf { it != id },
        notices = s.notices.filterNot { it.playlistId == id },
        lastLive = s.lastLive.filterKeys { it != id && !it.startsWith("$id@") },
        followed = s.followed - id,
        intros = s.intros.filterKeys { !it.startsWith("$id|") },
        reminders = s.reminders.filterNot { it.playlistId == id },
    )

    /** The parts of two states that sync, for telling whether a merge changed anything. */
    fun sameSynced(a: AppState, b: AppState): Boolean =
        a.playlists == b.playlists && a.favorites == b.favorites && a.favLists == b.favLists &&
            a.recents == b.recents && a.settings == b.settings && a.progress == b.progress &&
            a.followed == b.followed && a.intros == b.intros && a.profiles == b.profiles &&
            a.reminders == b.reminders && a.alertWords == b.alertWords
}
