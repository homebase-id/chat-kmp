package id.homebase.api.image

import id.homebase.api.util.ByteBuilder
import kotlin.math.roundToLong

/**
 * How the HDR rendition is rebuilt from an SDR image and its gain map. Ultra HDR v1 (`hdrgm` XMP) and ISO 21496-1
 * carry the same numbers: the content boosts and the display headrooms as log2, gamma and offsets as they are. Each
 * per-channel list holds one value for all channels, or three.
 */
data class GainmapMetadata(
    val gainMapMin: List<Double>,
    val gainMapMax: List<Double>,
    val gamma: List<Double>,
    val offsetSdr: List<Double>,
    val offsetHdr: List<Double>,
    val hdrCapacityMin: Double,
    val hdrCapacityMax: Double,
    val useBaseColorSpace: Boolean = true,
) {
    val channels: Int get() = gainMapMax.size
}

/** A gain map as its own JPEG, and how to apply it. */
internal class Gainmap(val jpeg: ByteArray, val metadata: GainmapMetadata)

/**
 * Ultra HDR JPEGs: an SDR primary image, then a gain-map JPEG, joined by an MPF index (CIPA DC-007), with the gain
 * map's metadata in both the `hdrgm` XMP Android 14 reads and the ISO 21496-1 block Android 15+, iOS 18+ and browsers
 * read. One implementation for every platform; each platform only decodes, scales and encodes plain images.
 * Layouts follow Google's libultrahdr.
 */
internal object UltraHdr {
    /** The gain map's byte range in [jpeg], from the primary image's MPF index; [jpeg] needs only reach past that. */
    fun gainmapRange(jpeg: ByteArray): LongRange? {
        if (!isJpeg(jpeg)) return null
        var range: LongRange? = null
        walkJpegSegments(jpeg) { marker, payload, end ->
            if (marker == MARKER_APP2 && startsWith(jpeg, payload, end, MPF_HEADER)) {
                range = secondImage(jpeg, payload + MPF_HEADER.size, end)
                false
            } else {
                true
            }
        }
        return range
    }

    /**
     * Whether [header], the start of a JPEG, ends before its scan without having shown an MPF index: a longer read
     * may still find one behind large EXIF, XMP or ICC segments.
     */
    fun mayHaveMpfBeyond(header: ByteArray): Boolean {
        if (!isJpeg(header)) return false
        var end = 2
        walkJpegSegments(header) { _, _, segmentEnd -> end = segmentEnd; true }
        // A walk that reached the scan stops at its SOS marker; one cut off by the header's end doesn't.
        return !(end + 1 < header.size && (header[end + 1].toInt() and 0xFF) == MARKER_SOS)
    }

    fun gainmapOf(jpeg: ByteArray): Gainmap? {
        val range = gainmapRange(jpeg) ?: return null
        if (range.last >= jpeg.size) return null
        val gainmap = jpeg.copyOfRange(range.first.toInt(), range.last.toInt() + 1)
        return metadataOf(gainmap)?.let { Gainmap(gainmap, it) }
    }

    /** A gain-map JPEG's metadata: its ISO 21496-1 block if it has one, else its `hdrgm` XMP. */
    fun metadataOf(gainmapJpeg: ByteArray): GainmapMetadata? {
        if (!isJpeg(gainmapJpeg)) return null
        var iso: GainmapMetadata? = null
        var xmp: IntRange? = null
        walkJpegSegments(gainmapJpeg) { marker, payload, end ->
            when {
                marker == MARKER_APP2 && startsWith(gainmapJpeg, payload, end, ISO_NAMESPACE) ->
                    iso = IsoGainmapMetadata.decode(gainmapJpeg.copyOfRange(payload + ISO_NAMESPACE.size, end))
                marker == MARKER_APP1 && startsWith(gainmapJpeg, payload, end, XMP_JPEG_HEADER) ->
                    xmp = payload + XMP_JPEG_HEADER.size until end
            }
            iso == null
        }
        return iso ?: xmp?.let { HdrgmXmp.decode(gainmapJpeg.decodeToString(it.first, it.last + 1)) }
    }

    /**
     * One Ultra HDR JPEG from an SDR [primary] JPEG and a [gainmap] JPEG, laid out as Android writes it: the primary's
     * XMP and ISO version block after SOI (and any JFIF), its MPF index right before the scan; the gain map's XMP and
     * ISO metadata after its SOI. Any gain-map, MPF or ISO segments the inputs already had are dropped.
     */
    fun join(primary: ByteArray, gainmap: ByteArray, metadata: GainmapMetadata): ByteArray {
        require(isJpeg(primary) && isJpeg(gainmap)) { "both images must be JPEGs" }
        val gainmapPieces = withSegments(gainmap, listOf(
            jpegSegment(MARKER_APP1, XMP_JPEG_HEADER + HdrgmXmp.encodeGainmap(metadata)),
            jpegSegment(MARKER_APP2, ISO_NAMESPACE + IsoGainmapMetadata.encode(metadata)),
        ))
        val primaryPieces = withSegments(primary, listOf(
            jpegSegment(MARKER_APP1, XMP_JPEG_HEADER + HdrgmXmp.encodePrimary(gainmapPieces.sumOf { it.size })),
            jpegSegment(MARKER_APP2, ISO_NAMESPACE + ISO_VERSION),
        ))
        // The last piece is the scan; the MPF index goes right before it, and its offsets count from after "MPF\0".
        val scan = primaryPieces.size - 1
        val mpfAt = primaryPieces.subList(0, scan).sumOf { it.size }
        val primarySize = primaryPieces.sumOf { it.size } + MPF_SEGMENT_SIZE
        val mpf = mpfSegment(primarySize, gainmapPieces.sumOf { it.size }, gainmapOffset = primarySize - (mpfAt + 8))
        return assemble(primaryPieces.subList(0, scan) + Piece.Literal(mpf) + primaryPieces[scan] + gainmapPieces)
    }

    // [jpeg] as pieces: SOI, any JFIF, [added], its other segments without the XMP, ISO and MPF ones the join writes
    // itself, and last its scan to the end.
    private fun withSegments(jpeg: ByteArray, added: List<ByteArray>): List<Piece> {
        val head = mutableListOf<Piece>(Piece.Span(jpeg, 0, 2))
        val kept = mutableListOf<Piece>()
        var at = 2
        walkJpegSegments(jpeg) { marker, payload, end ->
            val start = payload - 4
            val ours = (marker == MARKER_APP1 && startsWith(jpeg, payload, end, XMP_JPEG_HEADER)) ||
                (marker == MARKER_APP2 && (startsWith(jpeg, payload, end, ISO_NAMESPACE) || startsWith(jpeg, payload, end, MPF_HEADER)))
            when {
                marker == MARKER_APP0 && start == 2 -> head += Piece.Span(jpeg, start, end)
                !ours -> kept += Piece.Span(jpeg, start, end)
            }
            at = end
            true
        }
        return head + added.map { Piece.Literal(it) } + kept + Piece.Span(jpeg, at, jpeg.size)
    }

    private fun mpfSegment(primarySize: Int, gainmapSize: Int, gainmapOffset: Int): ByteArray {
        val out = ByteBuilder(MPF_SEGMENT_SIZE)
        out.addU16(0xFFE2); out.addU16(MPF_SEGMENT_SIZE - 2)
        out.add(MPF_HEADER)
        out.addU16(0x4D4D); out.addU16(0x002A); out.addU32(8) // big-endian TIFF header, IFD right after it
        out.addU16(3)
        out.addU16(0xB000); out.addU16(7); out.addU32(4); out.add("0100".encodeToByteArray())
        out.addU16(0xB001); out.addU16(4); out.addU32(1); out.addU32(2)
        out.addU16(0xB002); out.addU16(7); out.addU32(32); out.addU32(MP_ENTRIES_OFFSET.toLong())
        out.addU32(0) // no attribute IFD
        out.addU32(0x030000); out.addU32(primarySize.toLong()); out.addU32(0); out.addU32(0)
        out.addU32(0); out.addU32(gainmapSize.toLong()); out.addU32(gainmapOffset.toLong()); out.addU32(0)
        return out.take().also { check(it.size == MPF_SEGMENT_SIZE) }
    }

    // The second MPF entry, as a byte range of the whole file.
    private fun secondImage(b: ByteArray, tiff: Int, end: Int): LongRange? {
        val index = mpfEntries(b, tiff, end)?.takeIf { it.count >= 2 } ?: return null
        val size = readU32(b, index.list + 16 + 4, index.little).toLong() and 0xFFFFFFFFL
        val offset = readU32(b, index.list + 16 + 8, index.little).toLong() and 0xFFFFFFFFL
        if (size <= 0 || offset <= 0) return null
        val start = tiff + offset
        return start until start + size
    }

    private const val MP_ENTRIES_OFFSET = 8 + 2 + 3 * 12 + 4
    private const val MPF_SEGMENT_SIZE = 4 + 4 + MP_ENTRIES_OFFSET + 2 * 16
    private val ISO_NAMESPACE = "urn:iso:std:iso:ts:21496:-1\u0000".encodeToByteArray()
    private val ISO_VERSION = byteArrayOf(0, 0, 0, 0)
}

/** The ISO 21496-1 metadata block: big-endian, every value a fraction. */
internal object IsoGainmapMetadata {
    private const val MULTI_CHANNEL = 0x80
    private const val BASE_COLOR_SPACE = 0x40
    private const val BACKWARD = 0x04
    private const val COMMON_DENOMINATOR = 0x08
    private const val DENOMINATOR = 1 shl 20

    fun encode(m: GainmapMetadata): ByteArray {
        val out = ByteBuilder(141)
        fun fraction(v: Double) { out.addU32((v * DENOMINATOR).roundToLong() and 0xFFFFFFFFL); out.addU32(DENOMINATOR.toLong()) }
        val multi = m.channels == 3 && listOf(m.gainMapMin, m.gainMapMax, m.gamma, m.offsetSdr, m.offsetHdr).any { it.distinct().size > 1 }
        out.addU16(0); out.addU16(0)
        out.addByte((if (multi) MULTI_CHANNEL else 0) or (if (m.useBaseColorSpace) BASE_COLOR_SPACE else 0))
        fraction(m.hdrCapacityMin)
        fraction(m.hdrCapacityMax)
        for (c in 0 until if (multi) 3 else 1) {
            fraction(m.gainMapMin.channel(c))
            fraction(m.gainMapMax.channel(c))
            fraction(m.gamma.channel(c))
            fraction(m.offsetSdr.channel(c))
            fraction(m.offsetHdr.channel(c))
        }
        return out.take()
    }

    /** Null for a version this doesn't read, an HDR base image, or a block cut short. */
    fun decode(b: ByteArray): GainmapMetadata? {
        var at = 0
        fun has(n: Int) = at + n <= b.size
        fun u16(): Int = readU16(b, at, little = false).also { at += 2 }
        fun u32(): Long = (readU32(b, at, little = false).toLong() and 0xFFFFFFFFL).also { at += 4 }
        if (!has(5)) return null
        if (u16() != 0) return null
        u16()
        val flags = b[at++].toInt() and 0xFF
        if (flags and BACKWARD != 0) return null
        val channels = if (flags and MULTI_CHANNEL != 0) 3 else 1
        val common = flags and COMMON_DENOMINATOR != 0
        val commonDenominator = if (common) { if (!has(4)) return null; u32() } else 0L
        fun value(signed: Boolean): Double? {
            if (!has(if (common) 4 else 8)) return null
            val n = u32()
            val d = if (common) commonDenominator else u32()
            if (d == 0L) return null
            return (if (signed) n.toInt().toDouble() else n.toDouble()) / d
        }
        val baseHeadroom = value(signed = false) ?: return null
        val alternateHeadroom = value(signed = false) ?: return null
        val min = ArrayList<Double>(); val max = ArrayList<Double>(); val gamma = ArrayList<Double>()
        val sdr = ArrayList<Double>(); val hdr = ArrayList<Double>()
        repeat(channels) {
            min += value(signed = true) ?: return null
            max += value(signed = true) ?: return null
            gamma += value(signed = false) ?: return null
            sdr += value(signed = true) ?: return null
            hdr += value(signed = true) ?: return null
        }
        return GainmapMetadata(min, max, gamma, sdr, hdr, baseHeadroom, alternateHeadroom, flags and BASE_COLOR_SPACE != 0)
    }
}

/** Ultra HDR v1's XMP: the primary image's container directory, and the gain map's `hdrgm` values. */
internal object HdrgmXmp {
    private const val HEAD = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.2\">" +
        "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
    private const val TAIL = "</rdf:RDF></x:xmpmeta>"

    fun encodePrimary(gainmapLength: Int): ByteArray = (
        HEAD + "<rdf:Description rdf:about=\"\" xmlns:Container=\"http://ns.google.com/photos/1.0/container/\" " +
            "xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\" xmlns:hdrgm=\"$HDRGM_NS\" hdrgm:Version=\"1.0\">" +
            "<Container:Directory><rdf:Seq>" +
            "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"Primary\" Item:Mime=\"image/jpeg\"/></rdf:li>" +
            "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"GainMap\" Item:Mime=\"image/jpeg\" Item:Length=\"$gainmapLength\"/></rdf:li>" +
            "</rdf:Seq></Container:Directory></rdf:Description>" + TAIL
        ).encodeToByteArray()

    // Android 14 reads one value per field, so a three-channel map writes its first channel here; the ISO block has all.
    fun encodeGainmap(m: GainmapMetadata): ByteArray = (
        HEAD + "<rdf:Description rdf:about=\"\" xmlns:hdrgm=\"$HDRGM_NS\" hdrgm:Version=\"1.0\"" +
            " hdrgm:GainMapMin=\"${m.gainMapMin.channel(0)}\" hdrgm:GainMapMax=\"${m.gainMapMax.channel(0)}\"" +
            " hdrgm:Gamma=\"${m.gamma.channel(0)}\" hdrgm:OffsetSDR=\"${m.offsetSdr.channel(0)}\"" +
            " hdrgm:OffsetHDR=\"${m.offsetHdr.channel(0)}\" hdrgm:HDRCapacityMin=\"${m.hdrCapacityMin}\"" +
            " hdrgm:HDRCapacityMax=\"${m.hdrCapacityMax}\" hdrgm:BaseRenditionIsHDR=\"False\"/>" + TAIL
        ).encodeToByteArray()

    /** Null without `hdrgm` values, or for an HDR base image. Missing optional fields take the format's defaults. */
    fun decode(xml: String): GainmapMetadata? {
        if (HDRGM_NS !in xml) return null
        if (field(xml, "BaseRenditionIsHDR")?.singleOrNull()?.equals("true", ignoreCase = true) == true) return null
        fun numbers(name: String, default: Double?): List<Double>? {
            val values = field(xml, name) ?: return default?.let { listOf(it) }
            return values.map { it.trim().toDoubleOrNull() ?: return null }.takeIf { it.size == 1 || it.size == 3 }
        }
        return GainmapMetadata(
            gainMapMin = numbers("GainMapMin", 0.0) ?: return null,
            gainMapMax = numbers("GainMapMax", null) ?: return null,
            gamma = numbers("Gamma", 1.0) ?: return null,
            offsetSdr = numbers("OffsetSDR", 1.0 / 64) ?: return null,
            offsetHdr = numbers("OffsetHDR", 1.0 / 64) ?: return null,
            hdrCapacityMin = numbers("HDRCapacityMin", 0.0)?.singleOrNull() ?: return null,
            hdrCapacityMax = numbers("HDRCapacityMax", null)?.singleOrNull() ?: return null,
        )
    }

    private val LIST_ITEM = Regex("""<rdf:li>([^<]*)</rdf:li>""")

    // An attribute, a simple element, or an element holding an rdf:Seq of one value per channel.
    private fun field(xml: String, name: String): List<String>? {
        Regex("""hdrgm:$name\s*=\s*(["'])([^"']*)\1""").find(xml)?.let { return listOf(it.groupValues[2]) }
        val body = Regex("""<hdrgm:$name>(.*?)</hdrgm:$name>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1) ?: return null
        val items = LIST_ITEM.findAll(body).map { it.groupValues[1] }.toList()
        return items.ifEmpty { listOf(body) }
    }
}

private fun List<Double>.channel(c: Int) = if (size == 1) this[0] else this[c]
