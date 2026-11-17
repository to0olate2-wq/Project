package app.falak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Native crash records (8.37): a small tombstone written by hand, read back. */
class TombstoneTest {
    private class Msg {
        val out = ByteArrayOutputStream()
        private fun varint(v: Long) {
            var x = v
            while (true) {
                if (x and 0x7fL.inv() == 0L) { out.write(x.toInt()); return }
                out.write(((x and 0x7f) or 0x80).toInt())
                x = x ushr 7
            }
        }
        fun int(n: Int, v: Long) = apply { varint((n shl 3).toLong()); varint(v) }
        fun bytes(n: Int, b: ByteArray) = apply { varint(((n shl 3) or 2).toLong()); varint(b.size.toLong()); out.write(b) }
        fun str(n: Int, s: String) = bytes(n, s.toByteArray())
        fun msg(n: Int, m: Msg) = bytes(n, m.out.toByteArray())
    }

    private fun frame(file: String, fn: String = "", off: Long = 0, pc: Long = 0x1234) =
        Msg().int(1, pc).str(6, file).also { if (fn.isNotEmpty()) it.str(4, fn).int(5, off) }

    private fun thread(id: Int, name: String, vararg frames: Msg) = Msg().int(1, id.toLong()).str(2, name).also { t -> frames.forEach { t.msg(4, it) } }

    private fun entry(id: Int, t: Msg) = Msg().int(1, id.toLong()).msg(2, t)

    private fun log(pid: Int, prio: Int, tag: String, text: String) =
        Msg().str(1, "11-17 20:04:38.100").int(2, pid.toLong()).int(4, prio.toLong()).str(5, tag).str(6, text)

    @Test
    fun readsTheThreadThatCrashed() {
        val record = Msg()
            .int(5, 4000).int(6, 4321)
            .msg(10, Msg().int(1, 6).str(2, "SIGABRT").str(4, "SI_TKILL"))
            .str(14, "frameworks/av/media/libstagefright/MPEG4Writer.cpp:123 CHECK(timestampUs >= 0) failed.")
            .msg(16, entry(4000, thread(4000, "app.falak.player", frame("/apex/com.android.runtime/lib64/bionic/libc.so", "__epoll_pwait", 8))))
            .msg(16, entry(4321, thread(4321, "MPEG4Writer",
                frame("/apex/com.android.runtime/lib64/bionic/libc.so", "abort", 164),
                frame("/system/lib64/libstagefright.so", "android::MPEG4Writer::Track::threadEntry()", 2000),
                frame("/vendor/lib64/libunknown.so"),
            )))
            .msg(18, Msg().str(1, "main")
                .msg(2, log(4000, 4, "Info", "not shown"))
                .msg(2, log(999, 6, "Other", "another app"))
                .msg(2, log(4000, 6, "MPEG4Writer", "timestampUs -33 < 0")))
            .out.toByteArray()
        val text = Tombstone.read(record)!!
        assertTrue(text, text.contains("Signal: SIGABRT (SI_TKILL)"))
        assertTrue(text.contains("Abort message: frameworks/av/media/libstagefright/MPEG4Writer.cpp:123"))
        assertTrue(text.contains("Crashed thread: MPEG4Writer (4321)"))
        assertTrue(text.contains("#00 libc.so  abort+164"))
        assertTrue(text.contains("#01 libstagefright.so  android::MPEG4Writer::Track::threadEntry()+2000"))
        assertTrue(text.contains("#02 libunknown.so  pc 0x1234"))
        assertFalse(text.contains("__epoll_pwait")) // other threads stay out
        assertTrue(text.contains("E MPEG4Writer: timestampUs -33 < 0"))
        assertFalse(text.contains("not shown"))
        assertFalse(text.contains("another app"))
    }

    @Test
    fun saysNothingForWhatIsNotARecord() {
        assertNull(Tombstone.read("*** *** *** text tombstone of Android 11".toByteArray()))
        assertNull(Tombstone.read(ByteArray(0)))
        assertEquals(null, Tombstone.read(byteArrayOf(0x0a, 0x7f, 0x01)))
    }
}
