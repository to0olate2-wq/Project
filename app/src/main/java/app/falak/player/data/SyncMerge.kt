package app.falak.player.data

import kotlinx.serialization.Serializable

/**
 * Automatic sync between linked devices: the state every device sends and the rules that merge
 * it. Each part of each playlist (the playlist itself, favorites, lists, hidden groups, guide
 * fixes) carries the time it last changed; the newer one wins. Continue watching is merged entry by
 * entry. Removed playlists and cleared entries leave a dated marker so another device doesn't
 * bring them back.
 */
@Serializable
data class SyncMeta(
    /** This device, for the others to tell it apart. */
    val deviceId: String = "",
    /** Secret shared by the linked devices (Base64); empty = not linked. */
    val key: String = "",
    /** Linked devices this one has synced with: device id -> name and when. */
    val peers: Map<String, SyncPeer> = emptyMap(),
    /** "<part>|<playlist id>" -> when that part last changed here (or on the device it came from). */
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
)

@Serializable
data class SyncedPlaylist(val at: Long, val playlist: Playlist)

/** One part of a playlist's data: only the field for its kind is set. */
@Serializable
data class SyncPart(
    val at: Long,
    val ids: List<String>? = null,
    val lists: List<FavList>? = null,
    val map: Map<String, String>? = null,
)

object SyncMerge {
    const val PL = "pl"
    const val FAV = "fav"
    const val LISTS = "lists"
    const val HIDDEN = "hidden"
    const val GUIDE = "guide"
    private val PARTS = listOf(PL, FAV, LISTS, HIDDEN, GUIDE)
    private const val KEEP_MARKERS = 60L * 86_400_000L
    private const val MAX_PROGRESS = 300

    fun syncId(s: AppState, localId: String): String = s.sync.alias[localId] ?: localId

    /** Two playlists from the same source (same link and login): the same playlist on two devices. */
    fun sameSource(a: Playlist, b: Playlist): Boolean =
        a.type == b.type && a.type != SourceType.M3U_FILE &&
            a.url.trimEnd('/').equals(b.url.trimEnd('/'), ignoreCase = true) && a.username == b.username

    /** Dates the parts a local change touched (called on every change while linked). */
    fun stamp(old: AppState, new: AppState, now: Long): AppState {
        if (old === new || new.sync.key.isEmpty()) return new
        var stamps: HashMap<String, Long>? = null
        fun bump(part: String, id: String) {
            val m = stamps ?: HashMap(new.sync.stamps).also { stamps = it }
            m["$part|$id"] = now
        }
        val before = old.playlists.associateBy { it.id }
        for (p in new.playlists) {
            val o = before[p.id]
            if (o == null || o != p) bump(PL, p.id)
            if (old.favorites[p.id] != new.favorites[p.id]) bump(FAV, p.id)
            if (old.favLists[p.id] != new.favLists[p.id]) bump(LISTS, p.id)
            if (old.settings.hiddenGroups[p.id] != new.settings.hiddenGroups[p.id]) bump(HIDDEN, p.id)
            if (old.settings.guideOverrides[p.id] != new.settings.guideOverrides[p.id]) bump(GUIDE, p.id)
        }
        var removed: HashMap<String, Long>? = null
        if (old.playlists !== new.playlists) {
            val ids = new.playlists.mapTo(HashSet()) { it.id }
            for (o in old.playlists) {
                if (o.id in ids) continue
                val r = removed ?: HashMap(new.sync.removed).also { removed = it }
                r[syncId(old, o.id)] = now
                val m = stamps ?: HashMap(new.sync.stamps).also { stamps = it }
                PARTS.forEach { m.remove("$it|${o.id}") }
            }
        }
        if (stamps == null && removed == null) return new
        return new.copy(sync = new.sync.copy(stamps = stamps ?: new.sync.stamps, removed = removed ?: new.sync.removed))
    }

    /** Every part of every playlist dated [now]: this device's state wins on the next sync. */
    fun touchAll(s: AppState, now: Long): AppState {
        val stamps = HashMap(s.sync.stamps)
        for (p in s.playlists) PARTS.forEach { stamps["$it|${p.id}"] = now }
        return s.copy(sync = s.sync.copy(stamps = stamps))
    }

    fun snapshot(s: AppState, device: String): SyncSnapshot {
        val sid = { id: String -> syncId(s, id) }
        val parts = HashMap<String, SyncPart>()
        for (p in s.playlists) {
            val id = p.id
            val key = sid(id)
            fun at(part: String) = s.sync.stamps["$part|$id"] ?: 0L
            parts["$FAV|$key"] = SyncPart(at(FAV), ids = s.favorites[id].orEmpty())
            parts["$LISTS|$key"] = SyncPart(at(LISTS), lists = s.favLists[id].orEmpty())
            parts["$HIDDEN|$key"] = SyncPart(at(HIDDEN), ids = s.settings.hiddenGroups[id].orEmpty())
            parts["$GUIDE|$key"] = SyncPart(at(GUIDE), map = s.settings.guideOverrides[id].orEmpty())
        }
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
            progress = s.progress.mapKeys { (k, _) -> sid(k.substringBefore('|')) + "|" + k.substringAfter('|') },
            progressGone = s.sync.progressGone,
        )
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
            PARTS.forEach { stamps.remove("$it|$id") }
            alias.remove(id)
        }

        // Favorites, lists, hidden groups, guide fixes: the newer side wins.
        val favorites = out.favorites.toMutableMap()
        val favLists = out.favLists.toMutableMap()
        val hidden = out.settings.hiddenGroups.toMutableMap()
        val guide = out.settings.guideOverrides.toMutableMap()
        for ((key, part) in r.parts) {
            val kind = key.substringBefore('|')
            val local = toLocal[key.substringAfter('|')] ?: continue
            if (local in gone) continue
            if (local !in fresh && part.at <= (stamps["$kind|$local"] ?: 0L)) continue
            when (kind) {
                FAV -> favorites[local] = part.ids.orEmpty()
                LISTS -> favLists[local] = part.lists.orEmpty()
                HIDDEN -> hidden[local] = part.ids.orEmpty()
                GUIDE -> guide[local] = part.map.orEmpty()
                else -> continue
            }
            stamps["$kind|$local"] = part.at
        }

        // Continue watching: entry by entry, the latest wins; a newer "cleared" marker removes it.
        val progressGone = HashMap(s.sync.progressGone)
        for ((k, t) in r.progressGone) if (t > (progressGone[k] ?: 0L)) progressGone[k] = t
        // Keys come as "<sync id>|<item id>", in the other device's ids or this one's.
        val localOfSid = playlists.filter { it.id !in gone }.associate { sidOf(it.id) to it.id }
        val localKey = { key: String ->
            val sid = key.substringBefore('|')
            (toLocal[sid]?.takeIf { it !in gone } ?: localOfSid[sid])?.let { "$it|" + key.substringAfter('|') }
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
            progress = if (newProgress == out.progress) out.progress else newProgress,
            settings = out.settings.copy(hiddenGroups = hidden, guideOverrides = guide),
            sync = out.sync.copy(
                stamps = stamps,
                alias = alias,
                peers = peers,
                removed = removed.filterValues { now - it < KEEP_MARKERS },
                progressGone = progressGone.filterValues { now - it < KEEP_MARKERS },
            ),
        )
        return out to gone
    }

    /** [s] without playlist [id] and everything that belonged to it. */
    fun withoutPlaylist(s: AppState, id: String): AppState = s.copy(
        playlists = s.playlists.filterNot { it.id == id },
        favorites = s.favorites - id,
        favLists = s.favLists - id,
        settings = s.settings.copy(
            guideOverrides = s.settings.guideOverrides - id,
            hiddenGroups = s.settings.hiddenGroups - id,
        ),
        recents = s.recents - id,
        lastPlaylistId = s.lastPlaylistId.takeIf { it != id },
        notices = s.notices.filterNot { it.playlistId == id },
        lastLive = s.lastLive - id,
    )

    /** The parts of two states that sync, for telling whether a merge changed anything. */
    fun sameSynced(a: AppState, b: AppState): Boolean =
        a.playlists == b.playlists && a.favorites == b.favorites && a.favLists == b.favLists &&
            a.settings.hiddenGroups == b.settings.hiddenGroups && a.settings.guideOverrides == b.settings.guideOverrides &&
            a.progress == b.progress
}
