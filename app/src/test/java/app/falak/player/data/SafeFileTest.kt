package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Playlists and settings survive a damaged save file (a power cut on a TV box). */
class SafeFileTest {
    private val dir: File = Files.createTempDirectory("safefile").toFile().apply { deleteOnExit() }
    private val file = File(dir, "state.json")
    private fun side(ext: String) = File(file.path + "." + ext)

    /** Accepts only a whole "{…}" text, like the JSON reader. */
    private val parse: (String) -> String = { t ->
        require(t.startsWith("{") && t.endsWith("}")) { "damaged" }
        t
    }

    @Test
    fun firstStartHasNothing() {
        assertNull(SafeFile.read(file, parse))
        assertFalse(side("bad").exists())
    }

    @Test
    fun savesAndKeepsTheCopyBefore() {
        SafeFile.write(file, "{one}")
        SafeFile.write(file, "{two}")
        assertEquals("{two}", SafeFile.read(file, parse))
        assertEquals("{one}", side("bak").readText())
        assertFalse(side("tmp").exists())
    }

    @Test
    fun damagedFileFallsBackToLastGoodCopyAndIsKeptAside() {
        SafeFile.write(file, "{one}")
        SafeFile.write(file, "{two}")
        file.writeText("") // a power cut left it empty
        assertEquals("{one}", SafeFile.read(file, parse))
        assertTrue(side("bad").exists())
        // The next save doesn't push the good copy out.
        SafeFile.write(file, "{three}", sync = false)
        assertEquals("{three}", SafeFile.read(file, parse))
        assertEquals("{one}", side("bak").readText())
    }

    @Test
    fun finishedSideFileIsNewest() {
        SafeFile.write(file, "{one}")
        // Stopped between the two renames: the new text is whole in .tmp, the old one in .bak.
        file.renameTo(side("bak"))
        side("tmp").writeText("{two}")
        assertEquals("{two}", SafeFile.read(file, parse))
    }

    @Test
    fun halfWrittenSideFileIsSkipped() {
        SafeFile.write(file, "{one}")
        SafeFile.write(file, "{two}")
        file.writeText("{tw")
        side("tmp").writeText("{thr")
        assertEquals("{one}", SafeFile.read(file, parse))
    }

    @Test
    fun nothingReadableKeepsTheFileForLater() {
        file.writeText("{brok")
        assertNull(SafeFile.read(file, parse))
        assertEquals("{brok", side("bad").readText())
    }
}
