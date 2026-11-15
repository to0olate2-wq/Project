package app.falak.player.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A backup file (8.28; the owner picked it from a list of what was missing): everything Wi-Fi
 * linking copies to another device (playlists with their passwords, favorites, lists, recently
 * watched, where films were left, followed series, reminders, add-ons, profiles, the settings
 * that sync), written to a file the person keeps. Restoring merges it in as a linked device's
 * data would be (SyncMerge.merge): what the file has comes back, and anything changed on this
 * device after the file was made stays as it is now. This device's own settings (picture size,
 * the look…) aren't in it, as they don't sync either.
 */
@Serializable
data class BackupFile(
    // No default that passes: a file without them ("{}") isn't a backup.
    val app: String = "",
    val kind: String = "",
    val v: Int = 1,
    /** When it was made (epoch ms). */
    val made: Long = 0,
    /** The app's version that made it. */
    val version: String = "",
    /** The device it was made on ("Pixel 8"). */
    val device: String = "",
    val snapshot: SyncSnapshot = SyncSnapshot(),
) {
    companion object {
        const val APP = "The Player"
        const val KIND = "backup"
    }
}

object Backup {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    /** The file's text: everything that syncs, each part counted as of [now] (so it wins over older data where it is restored). */
    fun make(s: AppState, device: String, version: String, now: Long): String {
        val snap = SyncMerge.snapshot(SyncMerge.touchAll(s, now), device)
            // (Not a device: restoring it doesn't add one to the linked devices.)
            .copy(deviceId = "", kids = null)
        return json.encodeToString(BackupFile.serializer(), BackupFile(app = BackupFile.APP, kind = BackupFile.KIND, made = now, version = version, device = device, snapshot = snap))
    }

    /** Reads a backup file; throws IllegalArgumentException with a plain reason when it isn't one. */
    fun read(text: String): BackupFile {
        val b = try {
            json.decodeFromString(BackupFile.serializer(), text.trim().removePrefix("﻿"))
        } catch (e: Exception) {
            throw IllegalArgumentException("This isn't a backup file of The Player.")
        }
        if (b.app != BackupFile.APP || b.kind != BackupFile.KIND) throw IllegalArgumentException("This isn't a backup file of The Player.")
        if (b.v > 1) throw IllegalArgumentException("This backup was made by a newer version of The Player: update the app first.")
        return b
    }

    /** [s] with the backup merged in, and the playlists it removed here (their saved copies can go). */
    fun restore(s: AppState, b: BackupFile, now: Long): Pair<AppState, List<String>> = SyncMerge.merge(s, b.snapshot, now)

    /** "3 playlists, 2 add-ons" for the message after a restore, and before it. */
    fun summary(b: BackupFile): String {
        val n = b.snapshot.playlists.size
        val addons = b.snapshot.parts["${SyncMerge.SET}|addons"]?.json?.let { (it as? kotlinx.serialization.json.JsonArray)?.size } ?: 0
        val favorites = b.snapshot.parts.filterKeys { it.startsWith("${SyncMerge.FAV}|") }.values.sumOf { it.ids?.size ?: 0 }
        return listOfNotNull(
            "$n playlist${if (n == 1) "" else "s"}",
            if (favorites > 0) "$favorites favorite${if (favorites == 1) "" else "s"}" else null,
            if (addons > 0) "$addons add-on${if (addons == 1) "" else "s"}" else null,
        ).joinToString(", ")
    }

    /** "ThePlayer-backup-2026-11-15.json" */
    fun fileName(now: Long): String = "ThePlayer-backup-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now)) + ".json"
}
