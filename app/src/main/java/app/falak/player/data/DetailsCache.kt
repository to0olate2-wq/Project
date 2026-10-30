package app.falak.player.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Film and series details (story, year, wide picture…) kept on the device per playlist, so the
 * featured pictures and details pages don't ask the provider again every time the app opens.
 * Kept two weeks, then fetched again; at most [MAX] titles per playlist (the newest kept).
 */
object DetailsCache {
    private const val KEEP_MS = 14L * 86_400_000L
    private const val MAX = 800

    @Serializable
    private data class Entry(
        val plot: String = "",
        val poster: String = "",
        val backdrop: String = "",
        val genre: String = "",
        val cast: String = "",
        val director: String = "",
        val year: String = "",
        val duration: String = "",
        val rating: String = "",
        val trailer: String = "",
        val at: Long = 0,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val serializer = MapSerializer(String.serializer(), Entry.serializer())

    private fun file(dir: File, playlistId: String) = File(dir, playlistId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    /** The details still fresh for this playlist, by title id, with when each was fetched. */
    fun read(dir: File, playlistId: String, now: Long = System.currentTimeMillis()): Map<String, Pair<VodInfo, Long>> = try {
        val f = file(dir, playlistId)
        if (!f.exists()) {
            emptyMap()
        } else {
            json.decodeFromString(serializer, f.readText())
                .filterValues { now - it.at < KEEP_MS }
                .mapValues { (_, e) ->
                    VodInfo(e.plot, e.poster, e.backdrop, e.genre, e.cast, e.director, e.year, e.duration, e.rating, e.trailer) to e.at
                }
        }
    } catch (e: Exception) {
        emptyMap()
    }

    /** One write at a time (they share the temporary file). */
    @Synchronized
    fun write(dir: File, playlistId: String, entries: Map<String, Pair<VodInfo, Long>>) {
        dir.mkdirs()
        val kept = entries.entries.sortedByDescending { it.value.second }.take(MAX).associate { (id, v) ->
            val (i, at) = v
            id to Entry(i.plot, i.poster, i.backdrop, i.genre, i.cast, i.director, i.year, i.duration, i.rating, i.trailer, at)
        }
        val f = file(dir, playlistId)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, kept))
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }
}
