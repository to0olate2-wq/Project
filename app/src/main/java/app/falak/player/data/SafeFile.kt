package app.falak.player.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The small files the app can't do without (playlists and settings, recordings, downloads), saved
 * so a power cut or a full disk never loses them.
 *
 * Saving: the text goes to a side file (`.tmp`) that is forced onto the disk, the copy before
 * becomes `.bak`, then the side file takes the name.
 *
 * Reading: the newest copy that reads whole (the file, else a finished side file, else `.bak`). A
 * file that can't be read is kept as `.bad` instead of being saved over, so nothing is lost for good.
 */
object SafeFile {
    /**
     * [sync]: wait until the text is on the disk, so a power cut right after can't leave an empty
     * file. That can take a moment on a slow TV box: only off the main thread. Without it the
     * `.bak` copy is the safety net.
     */
    fun write(file: File, text: String, sync: Boolean = true) {
        val tmp = side(file, "tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            if (sync) out.fd.sync()
        }
        if (file.exists()) file.renameTo(side(file, "bak"))
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Couldn't save ${file.name}")
        }
    }

    /** The newest copy of [file] that [parse] accepts, or null when there's none (a first start). */
    fun <T : Any> read(file: File, parse: (String) -> T): T? {
        val copies = listOf(file, side(file, "tmp"), side(file, "bak"))
        for (f in copies) {
            if (!f.exists()) continue
            val value = try {
                parse(f.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                null
            }
            if (value != null) {
                // The main file was damaged: kept aside, so the next save doesn't push the good copy out.
                if (f != file) keepBad(file)
                return value
            }
        }
        keepBad(file)
        return null
    }

    private fun keepBad(file: File) {
        if (file.exists()) runCatching { file.renameTo(side(file, "bad")) }
    }

    private fun side(file: File, ext: String) = File(file.path + "." + ext)
}
