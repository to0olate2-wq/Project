package app.falak.player.player

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.SparseArray
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaChunkExtractor
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader

/**
 * Closed captions travel inside the video, and IPTV restreams almost never say which caption
 * services they carry. We offer CC1, CC2, CC3 and CEA-708, and watch the caption bytes as they are
 * extracted (every caption track receives them, selected or not) to learn which ones really
 * contain text. Only those are shown to the user.
 */
object Captions {
    const val CC1 = 1
    const val CC2 = 2
    const val CC3 = 4
    const val CC4 = 8
    const val SERVICE1 = 16

    val FORMATS: List<Format> = listOf(
        cea608(1),
        cea608(2),
        cea608(3),
        Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA708).setAccessibilityChannel(1).build(),
    )

    private fun cea608(channel: Int) =
        Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).setAccessibilityChannel(channel).build()

    /** A caption track we added ourselves (not declared by the stream). */
    fun isProbe(f: Format): Boolean =
        (f.sampleMimeType == MimeTypes.APPLICATION_CEA608 || f.sampleMimeType == MimeTypes.APPLICATION_CEA708) &&
            f.language == null

    /** The detection bit for one of our caption formats. */
    fun bitOf(f: Format): Int = when (f.sampleMimeType) {
        MimeTypes.APPLICATION_CEA708 -> SERVICE1
        MimeTypes.APPLICATION_CEA608 -> when (f.accessibilityChannel) {
            2 -> CC2
            3 -> CC3
            4 -> CC4
            else -> CC1
        }
        else -> 0
    }
}

/** Learns, from the raw caption bytes, which caption services carry text. One per played item. */
class CaptionSniffer(private val onChange: () -> Unit) {
    @Volatile var found = 0
        private set
    /** Teletext subtitle languages announced by the stream (Media3 can't show these; VLC can). */
    @Volatile var teletext: List<String> = emptyList()
        private set

    private val main = Handler(Looper.getMainLooper())
    private val counts = IntArray(4)
    private val channel = IntArray(2) // per field: 0 = first service (CC1/CC3), 1 = second (CC2/CC4)
    private val textMode = BooleanArray(4)
    private var xds = false
    private var text708 = 0
    private val packet = ByteArray(130)
    private var packetLength = 0
    private var packetSize = 0

    private fun mark(bit: Int) {
        if (found and bit != 0) return
        found = found or bit
        main.post(onChange)
    }

    fun onTeletext(languages: List<String>) {
        if (languages.isEmpty() || teletext.isNotEmpty()) return
        teletext = languages
        main.post(onChange)
    }

    /** [data] holds cc_data triplets: marker/valid/type, byte 1, byte 2. */
    @Synchronized
    fun inspect(data: ByteArray, offset: Int, length: Int) {
        if (found == ALL) return
        var i = offset
        val end = offset + length - 3
        while (i <= end) {
            val head = data[i].toInt() and 0xFF
            val b1 = data[i + 1].toInt() and 0xFF
            val b2 = data[i + 2].toInt() and 0xFF
            i += 3
            if (head and 0x04 == 0) continue // cc_valid
            when (head and 0x03) {
                0, 1 -> on608(head and 0x03, b1 and 0x7F, b2 and 0x7F)
                3 -> {
                    finish708()
                    packetSize = (b1 and 0x3F).let { if (it == 0) 128 else it * 2 }
                    packetLength = 0
                    add708(b1)
                    add708(b2)
                }
                2 -> {
                    add708(b1)
                    add708(b2)
                }
            }
        }
    }

    private fun on608(field: Int, c1: Int, c2: Int) {
        if (c1 == 0 && c2 == 0) return
        if (field == 1) {
            // Field 2 also carries XDS (programme info) in plain letters: not captions.
            if (c1 in 0x01..0x0E) { xds = true; return }
            if (c1 == 0x0F) { xds = false; return }
        }
        if (c1 in 0x10..0x1F) {
            if (field == 1) xds = false
            val ch = if (c1 and 0x08 != 0) 1 else 0
            channel[field] = ch
            val slot = field * 2 + ch
            if (c1 and 0x07 == 0x04 && (c2 == 0x2A || c2 == 0x2B)) textMode[slot] = true // TXT mode, not captions
            if (c1 and 0x07 == 0x04 && (c2 == 0x20 || c2 in 0x25..0x27 || c2 == 0x29)) textMode[slot] = false
            return
        }
        if (xds && field == 1) return
        if (c1 < 0x20) return
        val slot = field * 2 + channel[field]
        if (textMode[slot]) return
        counts[slot] += 1 + (if (c2 >= 0x20) 1 else 0)
        if (counts[slot] >= 8) mark(1 shl slot)
    }

    private fun add708(b: Int) {
        if (packetSize == 0) return
        if (packetLength < packet.size) packet[packetLength++] = b.toByte()
        if (packetLength >= packetSize) finish708()
    }

    /** Walks the service blocks of one DTVCC packet and counts printable text in service 1. */
    private fun finish708() {
        val size = minOf(packetLength, packetSize)
        packetSize = 0
        packetLength = 0
        var j = 1 // byte 0 is the packet header
        while (j < size) {
            val header = packet[j].toInt() and 0xFF
            var service = header shr 5
            val blockSize = header and 0x1F
            j++
            if (service == 7 && j < size) {
                service = packet[j].toInt() and 0x3F
                j++
            }
            if (service == 0 || blockSize == 0) break
            var k = j
            val blockEnd = minOf(size, j + blockSize)
            while (k < blockEnd) {
                val c = packet[k].toInt() and 0xFF
                k += when {
                    c in 0x20..0x7F || c >= 0xA0 -> {
                        if (service == 1) text708++
                        1
                    }
                    c == 0x10 -> 2 // EXT1 + one code
                    c in 0x11..0x17 -> 2
                    c in 0x18..0x1F -> 3
                    c in 0x88..0x8D -> 2
                    c == 0x90 || c == 0x92 -> 3
                    c == 0x91 -> 4
                    c == 0x97 -> 5
                    c in 0x98..0x9F -> 7
                    else -> 1
                }
            }
            j += blockSize
        }
        if (text708 >= 8) mark(Captions.SERVICE1)
    }

    private companion object {
        const val ALL = Captions.CC1 or Captions.CC2 or Captions.CC3 or Captions.CC4 or Captions.SERVICE1
    }
}

/** Passes everything through, letting the sniffer look at the bytes of caption tracks. */
private class SniffingTrackOutput(private val delegate: TrackOutput, private val sniffer: CaptionSniffer) : TrackOutput {
    @Volatile private var watch = false

    override fun format(format: Format) {
        // All caption tracks receive the same bytes: looking at one of them is enough.
        watch = format.sampleMimeType == MimeTypes.APPLICATION_CEA608 && format.accessibilityChannel == 1
        delegate.format(format)
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
        delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (watch && length > 0) {
            try {
                sniffer.inspect(data.data, data.position, minOf(length, data.bytesLeft()))
            } catch (e: Exception) {
                // never let detection disturb playback
            }
        }
        delegate.sampleData(data, length, sampleDataPart)
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) =
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
}

private class SniffingOutput(private val delegate: ExtractorOutput, private val sniffer: CaptionSniffer) : ExtractorOutput {
    override fun track(id: Int, type: Int): TrackOutput {
        val out = delegate.track(id, type)
        return if (type == C.TRACK_TYPE_TEXT) SniffingTrackOutput(out, sniffer) else out
    }

    override fun endTracks() = delegate.endTracks()
    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
}

private class SniffingExtractor(private val delegate: Extractor, private val sniffer: CaptionSniffer) : Extractor {
    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)
    override fun init(output: ExtractorOutput) = delegate.init(SniffingOutput(output, sniffer))
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = delegate.read(input, seekPosition)
    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)
    override fun release() = delegate.release()
    override fun getUnderlyingImplementation(): Extractor = delegate.getUnderlyingImplementation()
}

/** Spots DVB teletext in the stream's programme table (Media3 has no teletext decoder). */
private class TeletextAwareFactory(
    private val delegate: TsPayloadReader.Factory,
    private val sniffer: CaptionSniffer,
) : TsPayloadReader.Factory {
    override fun createInitialPayloadReaders(): SparseArray<TsPayloadReader> = delegate.createInitialPayloadReaders()

    override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? {
        val languages = teletextSubtitleLanguages(esInfo.descriptorBytes)
        if (languages.isNotEmpty()) {
            sniffer.onTeletext(languages)
            return null
        }
        return delegate.createPayloadReader(streamType, esInfo)
    }

    /** Languages of teletext subtitle pages (types 2 and 5) in a teletext_descriptor (0x56 / 0x46). */
    private fun teletextSubtitleLanguages(d: ByteArray): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i + 1 < d.size) {
            val tag = d[i].toInt() and 0xFF
            val len = d[i + 1].toInt() and 0xFF
            val start = i + 2
            val end = minOf(d.size, start + len)
            if (tag == 0x56 || tag == 0x46) {
                var j = start
                while (j + 5 <= end) {
                    val lang = String(d, j, 3, Charsets.ISO_8859_1).trim()
                    val type = (d[j + 3].toInt() and 0xFF) shr 3
                    if (type == 0x02 || type == 0x05) out += lang.ifEmpty { "und" }
                    j += 5
                }
            }
            i = end
        }
        return out.distinct()
    }
}

/**
 * Extractors for progressive streams (Xtream .ts live TV, MP4/MKV films): the usual set, with our
 * caption formats, teletext detection and the caption sniffer added for MPEG-TS.
 */
class SniffingExtractorsFactory(private val sniffer: CaptionSniffer) : ExtractorsFactory {
    private val defaults = DefaultExtractorsFactory()
        .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
        .setTsSubtitleFormats(Captions.FORMATS)

    private fun adapt(list: Array<Extractor>): Array<Extractor> = Array<Extractor>(list.size) { i ->
        val e = list[i]
        val replaced = if (e is TsExtractor) newTsExtractor() else e
        SniffingExtractor(replaced, sniffer)
    }

    private fun newTsExtractor(): Extractor = TsExtractor(
        TsExtractor.MODE_SINGLE_PMT,
        /* extractorFlags= */ 0,
        DefaultSubtitleParserFactory(),
        TimestampAdjuster(0),
        TeletextAwareFactory(
            DefaultTsPayloadReaderFactory(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES, Captions.FORMATS),
            sniffer,
        ),
        TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
    )

    override fun createExtractors(): Array<Extractor> = adapt(defaults.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        adapt(defaults.createExtractors(uri, responseHeaders))
}

private class SniffingChunkExtractor(
    private val delegate: HlsMediaChunkExtractor,
    private val sniffer: CaptionSniffer,
) : HlsMediaChunkExtractor {
    override fun init(extractorOutput: ExtractorOutput) = delegate.init(SniffingOutput(extractorOutput, sniffer))
    override fun read(extractorInput: ExtractorInput): Boolean = delegate.read(extractorInput)
    override fun isPackedAudioExtractor(): Boolean = delegate.isPackedAudioExtractor
    override fun isReusable(): Boolean = delegate.isReusable
    override fun recreate(): HlsMediaChunkExtractor = SniffingChunkExtractor(delegate.recreate(), sniffer)
    override fun onTruncatedSegmentParsed() = delegate.onTruncatedSegmentParsed()
}

/** HLS: offer our caption formats when the playlist declares none, and sniff them. */
class SniffingHlsExtractorFactory(private val sniffer: CaptionSniffer) : HlsExtractorFactory {
    private val delegate = DefaultHlsExtractorFactory(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES, true)

    override fun createExtractor(
        uri: Uri,
        format: Format,
        muxedCaptionFormats: List<Format>?,
        timestampAdjuster: TimestampAdjuster,
        responseHeaders: Map<String, List<String>>,
        sniffingExtractorInput: ExtractorInput,
        playerId: PlayerId,
    ): HlsMediaChunkExtractor {
        val captions = muxedCaptionFormats ?: Captions.FORMATS
        return SniffingChunkExtractor(
            delegate.createExtractor(uri, format, captions, timestampAdjuster, responseHeaders, sniffingExtractorInput, playerId),
            sniffer,
        )
    }

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): HlsExtractorFactory {
        delegate.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    override fun experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction: Boolean): HlsExtractorFactory {
        delegate.experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction)
        return this
    }

    override fun getOutputTextFormat(sourceFormat: Format): Format = delegate.getOutputTextFormat(sourceFormat)
}
