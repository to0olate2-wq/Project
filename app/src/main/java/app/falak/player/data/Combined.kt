package app.falak.player.data

/**
 * Combined playlists: one list made of several of your playlists. Their channels, films and series
 * show together (groups with the same name become one; the same stream or the same film twice
 * shows once, and the same channel twice in a group shows once with the other as a fall-back).
 *
 * Each item keeps a mark of the playlist it came from at the end of its id ("123~k3f9a"), so
 * details, episodes, replays and the account are asked of that playlist's provider.
 */
object Combined {
    const val MARK = '~'

    /**
     * A short mark for a source playlist that is the same on every linked device: from its login
     * (or link), not from its id here.
     */
    fun tag(src: Playlist): String {
        val user = src.username.ifBlank { XtreamApi.fromM3uLink(src.url)?.second.orEmpty() }
        val basis = when {
            src.type == SourceType.M3U_FILE -> "file:" + src.id
            user.isNotBlank() -> src.type.name + ":" + user
            else -> src.type.name + ":" + src.url.trim().trimEnd('/').lowercase()
        }
        return stableId(basis).take(5)
    }

    /** The mark at the end of an id from a combined playlist, or null. */
    fun tagOf(id: String): String? {
        val at = id.lastIndexOf(MARK)
        if (at < 0 || at == id.lastIndex) return null
        // After it can come "_2" (a copy listed twice, see ensureUniqueIds) or a replay's ":<start>".
        return id.substring(at + 1).takeWhile { it.isLetterOrDigit() }.ifEmpty { null }
    }

    /** The id as the source playlist's provider knows it. */
    fun untag(id: String): String = id.substringBeforeLast(MARK)

    /**
     * The playlists [p] combines, in its order, each with its mark (two that would share a mark
     * get "2", "3"…). Ones removed since, and other combined playlists, are left out.
     */
    fun sourcesOf(p: Playlist, all: List<Playlist>): List<Pair<Playlist, String>> {
        val byId = all.associateBy { it.id }
        val used = HashSet<String>()
        val out = ArrayList<Pair<Playlist, String>>()
        for (id in p.sources.distinct()) {
            val src = byId[id] ?: continue
            if (src.isCombined) continue
            val base = tag(src)
            var t = base
            var n = 2
            while (!used.add(t)) t = base + n++
            out += src to t
        }
        return out
    }

    /** The playlist an item of combined playlist [p] comes from ([id]: the item's, or a series id). */
    fun sourceFor(p: Playlist, id: String, all: List<Playlist>): Playlist? {
        val t = tagOf(id) ?: return null
        return sourcesOf(p, all).firstOrNull { it.second == t }?.first
    }

    /** Puts the loaded source playlists together, in order. */
    fun combine(parts: List<Triple<Playlist, String, PlaylistCache>>): PlaylistCache {
        val out = ArrayList<Channel>()
        val streams = HashSet<String>()
        val titles = HashSet<String>()
        for ((src, t, cache) in parts) {
            for (ch in cache.channels) {
                // The same stream in two of them: once.
                if (ch.url.isNotBlank() && !streams.add(ch.url)) continue
                // The same film or series in a group of the same name: the first playlist's.
                if (ch.kind != Kind.LIVE && !titles.add(ch.kind.name + "|" + ch.group + "|" + ch.name.trim().lowercase())) continue
                out += ch.copy(
                    id = ch.id + MARK + t,
                    seriesId = if (ch.seriesId.isEmpty()) "" else ch.seriesId + MARK + t,
                    userAgent = ch.userAgent.ifBlank { src.userAgent },
                )
            }
        }
        // Every playlist's guide (each one's own link, else its provider's, and the extra ones).
        val guides = parts.flatMap { (src, _, cache) -> listOf(src.epgUrl.ifBlank { cache.epgUrl }) + src.extraEpg }
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        // Replays need each provider's own time zone: "mark=zone" a line each.
        val zones = parts.filter { it.third.serverTz.isNotBlank() }.joinToString("\n") { (_, t, cache) -> "$t=${cache.serverTz}" }
        return PlaylistCache(out, guides.joinToString("\n"), zones, parts.minOf { it.third.savedAt })
    }

    /** The provider's time zone for item [id] ([serverTz] as [combine] wrote it, or one zone for a plain playlist). */
    fun zoneFor(serverTz: String, id: String): String {
        if ('=' !in serverTz) return serverTz
        val t = tagOf(id) ?: return ""
        return serverTz.lineSequence().firstOrNull { it.substringBefore('=') == t }?.substringAfter('=').orEmpty()
    }

    /** A source playlist's episodes, marked as coming from it. */
    fun markEpisodes(info: SeriesInfo, src: Playlist, t: String): SeriesInfo = SeriesInfo(
        plot = info.plot,
        cover = info.cover,
        seasons = info.seasons.map { s ->
            Season(s.number, s.episodes.map { ep -> ep.copy(id = ep.id + MARK + t, userAgent = ep.userAgent.ifBlank { src.userAgent }) })
        },
        backdrop = info.backdrop,
        genre = info.genre,
        cast = info.cast,
        year = info.year,
        rating = info.rating,
    )
}
