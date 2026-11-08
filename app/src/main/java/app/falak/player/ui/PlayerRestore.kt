package app.falak.player.ui

import android.os.Bundle
import app.falak.player.data.Channel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Android may close the app while the player is in the background (TV boxes do it often). Coming
 * back, the player is rebuilt from this: what was playing, the channels around it to zap through,
 * and how far a film got. The list is kept short, so the saved state stays small.
 */
internal object PlayerRestore {
    private const val QUEUE = "restore.queue"
    private const val INDEX = "restore.index"
    private const val PLAYLIST = "restore.playlist"
    private const val SERIES = "restore.series"
    private const val POSITION = "restore.position"

    /** Channels kept on each side of the one playing. */
    const val AROUND = 25

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(Channel.serializer())

    class Saved(
        val queue: List<Channel>,
        val index: Int,
        val playlistId: String?,
        val series: Channel?,
        /** Where a film or episode got to (ms), or negative for none. */
        val positionMs: Long,
    )

    /** [index] in [queue] and the [AROUND] channels on each side: the short list and the index in it. */
    fun window(queue: List<Channel>, index: Int): Pair<List<Channel>, Int> {
        if (queue.isEmpty()) return emptyList<Channel>() to 0
        val at = index.coerceIn(0, queue.lastIndex)
        val from = (at - AROUND).coerceAtLeast(0)
        val to = (at + AROUND + 1).coerceAtMost(queue.size)
        return queue.subList(from, to).toList() to at - from
    }

    fun save(out: Bundle, queue: List<Channel>, index: Int, playlistId: String?, series: Channel?, positionMs: Long) {
        val (list, at) = window(queue, index)
        if (list.isEmpty()) return
        out.putString(QUEUE, json.encodeToString(listSerializer, list))
        out.putInt(INDEX, at)
        playlistId?.let { out.putString(PLAYLIST, it) }
        series?.let { out.putString(SERIES, json.encodeToString(Channel.serializer(), it)) }
        out.putLong(POSITION, positionMs)
    }

    /** What [save] kept, or null when there's nothing (or it can't be read). */
    fun read(state: Bundle?): Saved? {
        val text = state?.getString(QUEUE) ?: return null
        return runCatching {
            val list = json.decodeFromString(listSerializer, text)
            if (list.isEmpty()) return null
            Saved(
                queue = list,
                index = state.getInt(INDEX, 0).coerceIn(0, list.lastIndex),
                playlistId = state.getString(PLAYLIST),
                series = state.getString(SERIES)?.let { json.decodeFromString(Channel.serializer(), it) },
                positionMs = state.getLong(POSITION, -1L),
            )
        }.getOrNull()
    }
}
