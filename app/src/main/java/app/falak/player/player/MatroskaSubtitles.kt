package app.falak.player.player

import androidx.media3.extractor.ExtractorInput

/**
 * MKV films with subtitles Media3 (1.5) skips but VLC shows: the older names some files give their
 * subtitle tracks. "S_TEXT/SSA" is read as "S_TEXT/ASS" (the same subtitle format, older version)
 * and "S_TEXT/ASCII" as "S_TEXT/UTF8" (plain subtitles; ASCII is a part of UTF-8). Each name is
 * swapped for one of the same length (zero-padded, as Matroska allows), so nothing else in the
 * file moves.
 *
 * Media3 reads a track's codec name as one exact-length read of the name's bytes, which is when
 * the swap happens; video, sound and subtitle samples go through untouched.
 */
object MatroskaSubtitles {
    private val SSA = "S_TEXT/SSA".toByteArray(Charsets.US_ASCII)
    private val ASS = "S_TEXT/ASS".toByteArray(Charsets.US_ASCII)
    private val ASCII = "S_TEXT/ASCII".toByteArray(Charsets.US_ASCII)
    private val UTF8 = "S_TEXT/UTF8\u0000".toByteArray(Charsets.US_ASCII)

    /** Swaps an old subtitle codec name just read into [target] for the one Media3 knows. */
    fun fix(target: ByteArray, offset: Int, length: Int) {
        when (length) {
            SSA.size -> if (matches(target, offset, SSA)) ASS.copyInto(target, offset)
            ASCII.size -> if (matches(target, offset, ASCII)) UTF8.copyInto(target, offset)
        }
    }

    private fun matches(target: ByteArray, offset: Int, name: ByteArray): Boolean {
        if (offset < 0 || offset + name.size > target.size) return false
        for (i in name.indices) if (target[offset + i] != name[i]) return false
        return true
    }
}

/** What a Matroska extractor reads through: the input as it is, with [MatroskaSubtitles] applied. */
internal class MatroskaSubtitleInput : ExtractorInput {
    lateinit var input: ExtractorInput

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = input.read(buffer, offset, length)

    override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        val ok = input.readFully(target, offset, length, allowEndOfInput)
        if (ok) MatroskaSubtitles.fix(target, offset, length)
        return ok
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int) {
        input.readFully(target, offset, length)
        MatroskaSubtitles.fix(target, offset, length)
    }

    override fun skip(length: Int): Int = input.skip(length)
    override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean = input.skipFully(length, allowEndOfInput)
    override fun skipFully(length: Int) = input.skipFully(length)
    override fun peek(target: ByteArray, offset: Int, length: Int): Int = input.peek(target, offset, length)
    override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean =
        input.peekFully(target, offset, length, allowEndOfInput)
    override fun peekFully(target: ByteArray, offset: Int, length: Int) = input.peekFully(target, offset, length)
    override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean = input.advancePeekPosition(length, allowEndOfInput)
    override fun advancePeekPosition(length: Int) = input.advancePeekPosition(length)
    override fun resetPeekPosition() = input.resetPeekPosition()
    override fun getPeekPosition(): Long = input.peekPosition
    override fun getPosition(): Long = input.position
    override fun getLength(): Long = input.length
    override fun <E : Throwable> setRetryPosition(position: Long, e: E) = input.setRetryPosition(position, e)
}
