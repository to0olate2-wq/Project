package app.falak.player.data

/**
 * Reads Android's record of a native crash (a "tombstone", protobuf since Android 12; see AOSP
 * system/core/debuggerd/proto/tombstone.proto) into a few plain lines (8.37): the signal, the
 * abort message (what the code said just before it stopped itself), the crashing thread with its
 * frames, and the last warnings and errors the app logged. Before, only loose words were kept,
 * which named every thread but not the one that crashed. No Android in it: tested.
 */
object Tombstone {
    private class Field(val number: Int, val wire: Int, val value: Long, val start: Int, val end: Int)

    /** The fields of one message in [b] between [from] and [to]; throws on anything malformed. */
    private fun fields(b: ByteArray, from: Int = 0, to: Int = b.size): List<Field> {
        val out = ArrayList<Field>()
        var p = from
        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                require(p < to && shift < 64) { "bad varint" }
                val c = b[p++].toInt() and 0xff
                result = result or ((c and 0x7f).toLong() shl shift)
                if (c and 0x80 == 0) return result
                shift += 7
            }
        }
        while (p < to) {
            val key = varint()
            val number = (key ushr 3).toInt()
            val wire = (key and 7).toInt()
            require(number > 0) { "bad field" }
            when (wire) {
                0 -> out += Field(number, 0, varint(), 0, 0)
                1 -> { require(p + 8 <= to); out += Field(number, 1, 0, p, p + 8); p += 8 }
                5 -> { require(p + 4 <= to); out += Field(number, 5, 0, p, p + 4); p += 4 }
                2 -> {
                    val len = varint()
                    require(len >= 0 && p + len <= to) { "bad length" }
                    out += Field(number, 2, len, p, p + len.toInt())
                    p += len.toInt()
                }
                else -> throw IllegalArgumentException("wire $wire")
            }
        }
        return out
    }

    private fun str(b: ByteArray, f: Field) = String(b, f.start, f.end - f.start, Charsets.UTF_8)

    /** The plain lines, or null when [bytes] isn't such a record (older Androids write text). */
    fun read(bytes: ByteArray, maxFrames: Int = 30, maxLogs: Int = 25): String? = try {
        describe(bytes, maxFrames, maxLogs)
    } catch (e: Exception) {
        null
    }

    private fun describe(b: ByteArray, maxFrames: Int, maxLogs: Int): String? {
        val top = fields(b)
        val pid = top.firstOrNull { it.number == 5 && it.wire == 0 }?.value?.toInt()
        val tid = top.firstOrNull { it.number == 6 && it.wire == 0 }?.value?.toInt() ?: return null
        val out = StringBuilder()

        top.firstOrNull { it.number == 10 && it.wire == 2 }?.let { s ->
            val sf = fields(b, s.start, s.end)
            val name = sf.firstOrNull { it.number == 2 && it.wire == 2 }?.let { str(b, it) }.orEmpty()
            val code = sf.firstOrNull { it.number == 4 && it.wire == 2 }?.let { str(b, it) }.orEmpty()
            val hasFault = sf.any { it.number == 8 && it.wire == 0 && it.value != 0L }
            val fault = sf.firstOrNull { it.number == 9 && it.wire == 0 }?.value
            out.append("Signal: ").append(name.ifBlank { "?" })
            if (code.isNotBlank()) out.append(" (").append(code).append(')')
            if (hasFault && fault != null) out.append(", fault address 0x").append(java.lang.Long.toHexString(fault))
            out.append('\n')
        }
        top.filter { it.number == 14 && it.wire == 2 }.map { str(b, it) }.filter { it.isNotBlank() }.forEach {
            out.append("Abort message: ").append(it.take(600)).append('\n')
        }
        top.filter { it.number == 15 && it.wire == 2 }.forEach { c ->
            fields(b, c.start, c.end).firstOrNull { it.number == 1 && it.wire == 2 }?.let {
                out.append("Cause: ").append(str(b, it).take(300)).append('\n')
            }
        }

        // threads: map<uint32, Thread> = repeated entries {1: key, 2: Thread}.
        val threads = top.filter { it.number == 16 && it.wire == 2 }.mapNotNull { e ->
            val ef = fields(b, e.start, e.end)
            val key = ef.firstOrNull { it.number == 1 && it.wire == 0 }?.value?.toInt()
            val value = ef.firstOrNull { it.number == 2 && it.wire == 2 }
            if (key == null || value == null) null else key to value
        }
        val crashed = threads.firstOrNull { it.first == tid }?.second
        if (crashed != null) {
            val tf = fields(b, crashed.start, crashed.end)
            val name = tf.firstOrNull { it.number == 2 && it.wire == 2 }?.let { str(b, it) }.orEmpty()
            out.append("\nCrashed thread: ").append(name.ifBlank { "?" }).append(" (").append(tid)
                .append(if (tid == pid) ", the main thread)" else ")").append('\n')
            tf.filter { it.number == 7 && it.wire == 2 }.forEach { out.append("  note: ").append(str(b, it).take(200)).append('\n') }
            tf.filter { it.number == 4 && it.wire == 2 }.take(maxFrames).forEachIndexed { i, fr ->
                val ff = fields(b, fr.start, fr.end)
                val fn = ff.firstOrNull { it.number == 4 && it.wire == 2 }?.let { str(b, it) }.orEmpty()
                val off = ff.firstOrNull { it.number == 5 && it.wire == 0 }?.value ?: 0L
                val file = ff.firstOrNull { it.number == 6 && it.wire == 2 }?.let { str(b, it) }.orEmpty()
                val pc = ff.firstOrNull { it.number == 1 && it.wire == 0 }?.value ?: 0L
                out.append("  #").append(i.toString().padStart(2, '0')).append(' ')
                    .append(file.substringAfterLast('/').ifBlank { "?" })
                if (fn.isNotBlank()) out.append("  ").append(fn.take(160)).append('+').append(off)
                else out.append("  pc 0x").append(java.lang.Long.toHexString(pc))
                out.append('\n')
            }
        }

        // The last warnings and errors this process logged (W = 5 and above).
        val logs = ArrayList<String>()
        top.filter { it.number == 18 && it.wire == 2 }.forEach { buf ->
            fields(b, buf.start, buf.end).filter { it.number == 2 && it.wire == 2 }.forEach { m ->
                val mf = fields(b, m.start, m.end)
                val lpid = mf.firstOrNull { it.number == 2 && it.wire == 0 }?.value?.toInt()
                val prio = mf.firstOrNull { it.number == 4 && it.wire == 0 }?.value?.toInt() ?: 0
                if (prio >= 5 && (pid == null || lpid == null || lpid == pid)) {
                    val tag = mf.firstOrNull { it.number == 5 && it.wire == 2 }?.let { str(b, it) }.orEmpty()
                    val msg = mf.firstOrNull { it.number == 6 && it.wire == 2 }?.let { str(b, it) }.orEmpty()
                    val time = mf.firstOrNull { it.number == 1 && it.wire == 2 }?.let { str(b, it) }.orEmpty()
                    val level = when (prio) { 5 -> "W"; 6 -> "E"; else -> "F" }
                    logs += "$time $level $tag: ${msg.trim().take(240)}".trim()
                }
            }
        }
        if (logs.isNotEmpty()) {
            out.append("\nLast warnings and errors:\n")
            logs.takeLast(maxLogs).forEach { out.append("  ").append(it).append('\n') }
        }
        return out.toString().takeIf { it.isNotBlank() }
    }
}
