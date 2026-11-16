package id.homebase.api.image

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
class Gainmap(val jpeg: ByteArray, val metadata: GainmapMetadata)

/**
 * Ultra HDR JPEGs: an SDR primary image, then a gain-map JPEG, joined by an MPF index (CIPA DC-007), with the gain
 * map's metadata in both the `hdrgm` XMP Android 14 reads and the ISO 21496-1 block Android 15+, iOS 18+ and browsers
 * read. One implementation for every platform; each platform only decodes, scales and encodes plain images.
 */
object UltraHdr {
    /** The gain map's byte range in [jpeg], from the primary image's MPF index; [jpeg] needs only reach past that. */
    fun gainmapRange(jpeg: ByteArray): LongRange? {
        if (!isJpeg(jpeg)) return null
        var range: LongRange? = null
        segments(jpeg) { marker, payload, end ->
            if (marker == APP2 && jpeg.startsWith(payload, MPF_SIGNATURE)) {
                range = mpfSecondImage(jpeg, payload + MPF_SIGNATURE.size, end)
                false
            } else {
                true
            }
        }
        return range
    }

    /** [jpeg]'s gain map, or null when it has none this reads. */
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
        var xmp: GainmapMetadata? = null
        segments(gainmapJpeg) { marker, payload, end ->
            when {
                marker == APP2 && gainmapJpeg.startsWith(payload, ISO_NAMESPACE) ->
                    iso = IsoGainmapMetadata.decode(gainmapJpeg.copyOfRange(payload + ISO_NAMESPACE.size, end))
                marker == APP1 && gainmapJpeg.startsWith(payload, XMP_NAMESPACE) ->
                    xmp = HdrgmXmp.decode(gainmapJpeg.decodeToString(payload + XMP_NAMESPACE.size, end))
            }
            iso == null
        }
        return iso ?: xmp
    }

    /**
     * One Ultra HDR JPEG from an SDR [primary] JPEG and a [gainmap] JPEG, laid out as Android writes it: the primary's
     * XMP and ISO version block after SOI (and any JFIF), its MPF index right before the scan; the gain map's XMP and
     * ISO metadata after its SOI. Any gain-map, MPF or ISO segments the inputs already had are dropped.
     */
    fun join(primary: ByteArray, gainmap: ByteArray, metadata: GainmapMetadata): ByteArray {
        require(isJpeg(primary) && isJpeg(gainmap)) { "both images must be JPEGs" }
        val gainmapOut = withSegments(gainmap, listOf(app1(XMP_NAMESPACE + HdrgmXmp.encodeGainmap(metadata)), app2(ISO_NAMESPACE + IsoGainmapMetadata.encode(metadata))))
        val head = withSegments(primary, listOf(app1(XMP_NAMESPACE + HdrgmXmp.encodePrimary(gainmapOut.size)), app2(ISO_NAMESPACE + ISO_VERSION)))
        val scan = scanStart(head)
        val primarySize = head.size + MPF_SEGMENT_SIZE
        // MPF offsets count from the byte after "MPF\0".
        val mpf = mpfSegment(primarySize, gainmapOut.size, gainmapOffset = primarySize - (scan + 8))
        return listOf(head.copyOfRange(0, scan), mpf, head.copyOfRange(scan, head.size), gainmapOut).concat()
    }

    // [jpeg] with [added] after SOI and any JFIF, and without the XMP, ISO and MPF segments the join writes itself.
    private fun withSegments(jpeg: ByteArray, added: List<ByteArray>): ByteArray {
        var jfif = ByteArray(0)
        val kept = ArrayList<ByteArray>()
        var at = 2
        segments(jpeg) { marker, payload, end ->
            val start = payload - 4
            val ours = (marker == APP1 && jpeg.startsWith(payload, XMP_NAMESPACE)) ||
                (marker == APP2 && (jpeg.startsWith(payload, ISO_NAMESPACE) || jpeg.startsWith(payload, MPF_SIGNATURE)))
            when {
                marker == APP0 && start == 2 -> jfif = jpeg.copyOfRange(start, end)
                !ours -> kept += jpeg.copyOfRange(start, end)
            }
            at = end
            true
        }
        return (listOf(jpeg.copyOfRange(0, 2), jfif) + added + kept + listOf(jpeg.copyOfRange(at, jpeg.size))).concat()
    }

    private fun List<ByteArray>.concat(): ByteArray {
        val out = ByteArray(sumOf { it.size })
        var at = 0
        for (part in this) { part.copyInto(out, at); at += part.size }
        return out
    }

    private fun scanStart(jpeg: ByteArray): Int {
        var end = 2
        segments(jpeg) { _, _, segmentEnd -> end = segmentEnd; true }
        return end
    }

    private fun mpfSegment(primarySize: Int, gainmapSize: Int, gainmapOffset: Int): ByteArray {
        val out = ArrayList<Byte>(MPF_SEGMENT_SIZE)
        fun u16(v: Int) { out += (v ushr 8).toByte(); out += v.toByte() }
        fun u32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
        u16(0xFFE2); u16(MPF_SEGMENT_SIZE - 2)
        MPF_SIGNATURE.forEach { out += it }
        u16(0x4D4D); u16(0x002A); u32(8) // big-endian TIFF header, IFD right after it
        u16(3)
        u16(0xB000); u16(7); u32(4); "0100".encodeToByteArray().forEach { out += it }
        u16(0xB001); u16(4); u32(1); u32(2)
        u16(0xB002); u16(7); u32(32); u32(MP_ENTRIES_OFFSET)
        u32(0) // no attribute IFD
        u32(0x030000); u32(primarySize); u32(0); u16(0); u16(0)
        u32(0); u32(gainmapSize); u32(gainmapOffset); u16(0); u16(0)
        check(out.size == MPF_SEGMENT_SIZE)
        return out.toByteArray()
    }

    // The second entry of an MPF index whose TIFF header starts at [tiff], as a byte range of the whole file.
    private fun mpfSecondImage(b: ByteArray, tiff: Int, end: Int): LongRange? {
        if (tiff + 8 > end) return null
        val little = when {
            b[tiff] == 'I'.code.toByte() && b[tiff + 1] == 'I'.code.toByte() -> true
            b[tiff] == 'M'.code.toByte() && b[tiff + 1] == 'M'.code.toByte() -> false
            else -> return null
        }
        fun u16(at: Int) = if (little) (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
        else ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
        fun u32(at: Int): Long = if (little) (u16(at).toLong() or (u16(at + 2).toLong() shl 16)) else ((u16(at).toLong() shl 16) or u16(at + 2).toLong())
        val ifd = tiff + u32(tiff + 4).toInt()
        if (ifd < tiff || ifd + 2 > end) return null
        for (e in 0 until u16(ifd)) {
            val entry = ifd + 2 + e * 12
            if (entry + 12 > end) return null
            if (u16(entry) != 0xB002) continue
            if (u32(entry + 4) < 32) return null
            val second = tiff + u32(entry + 8).toInt() + 16
            if (second < tiff || second + 16 > end) return null
            val size = u32(second + 4)
            val offset = u32(second + 8)
            if (size <= 0 || offset <= 0) return null
            val start = tiff + offset
            return start until start + size
        }
        return null
    }

    private inline fun segments(b: ByteArray, visit: (marker: Int, payload: Int, end: Int) -> Boolean) {
        var i = 2
        while (i + 4 <= b.size && b[i] == 0xFF.toByte()) {
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == SOS || marker == EOI || marker == 0xFF) return
            val length = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            val end = i + 2 + length
            if (length < 2 || end > b.size || !visit(marker, i + 4, end)) return
            i = end
        }
    }

    private fun ByteArray.startsWith(at: Int, prefix: ByteArray) =
        at + prefix.size <= size && prefix.indices.all { this[at + it] == prefix[it] }

    private fun isJpeg(b: ByteArray) = b.size > 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    private fun app1(payload: ByteArray) = segment(APP1, payload)
    private fun app2(payload: ByteArray) = segment(APP2, payload)

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        require(length <= 0xFFFF) { "segment too long" }
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length ushr 8).toByte(), length.toByte()) + payload
    }

    private const val APP0 = 0xE0
    private const val APP1 = 0xE1
    private const val APP2 = 0xE2
    private const val SOS = 0xDA
    private const val EOI = 0xD9
    private const val MP_ENTRIES_OFFSET = 8 + 2 + 3 * 12 + 4
    private const val MPF_SEGMENT_SIZE = 4 + 4 + MP_ENTRIES_OFFSET + 2 * 16
    private val MPF_SIGNATURE = byteArrayOf(0x4D, 0x50, 0x46, 0x00)
    private val XMP_NAMESPACE = "http://ns.adobe.com/xap/1.0/\u0000".encodeToByteArray()
    internal val ISO_NAMESPACE = "urn:iso:std:iso:ts:21496:-1\u0000".encodeToByteArray()
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
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out += (v ushr 8).toByte(); out += v.toByte() }
        fun u32(v: Long) { u16((v ushr 16).toInt() and 0xFFFF); u16((v and 0xFFFF).toInt()) }
        fun fraction(v: Double) { u32((v * DENOMINATOR).roundToLong() and 0xFFFFFFFFL); u32(DENOMINATOR.toLong()) }
        val multi = m.channels == 3 && listOf(m.gainMapMin, m.gainMapMax, m.gamma, m.offsetSdr, m.offsetHdr).any { it.distinct().size > 1 }
        u16(0); u16(0)
        out += ((if (multi) MULTI_CHANNEL else 0) or (if (m.useBaseColorSpace) BASE_COLOR_SPACE else 0)).toByte()
        fraction(m.hdrCapacityMin)
        fraction(m.hdrCapacityMax)
        for (c in 0 until if (multi) 3 else 1) {
            fraction(m.gainMapMin.channel(c))
            fraction(m.gainMapMax.channel(c))
            fraction(m.gamma.channel(c))
            fraction(m.offsetSdr.channel(c))
            fraction(m.offsetHdr.channel(c))
        }
        return out.toByteArray()
    }

    /** Null for a version this doesn't read, an HDR base image, or a block cut short. */
    fun decode(b: ByteArray): GainmapMetadata? {
        var at = 0
        fun has(n: Int) = at + n <= b.size
        fun u16(): Int = (((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)).also { at += 2 }
        fun u32(): Long = ((u16().toLong() shl 16) or u16().toLong())
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
    private const val HDRGM = "http://ns.adobe.com/hdr-gain-map/1.0/"
    private const val HEAD = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.2\">" +
        "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
    private const val TAIL = "</rdf:RDF></x:xmpmeta>"

    fun encodePrimary(gainmapLength: Int): ByteArray = (
        HEAD + "<rdf:Description rdf:about=\"\" xmlns:Container=\"http://ns.google.com/photos/1.0/container/\" " +
            "xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\" xmlns:hdrgm=\"$HDRGM\" hdrgm:Version=\"1.0\">" +
            "<Container:Directory><rdf:Seq>" +
            "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"Primary\" Item:Mime=\"image/jpeg\"/></rdf:li>" +
            "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"GainMap\" Item:Mime=\"image/jpeg\" Item:Length=\"$gainmapLength\"/></rdf:li>" +
            "</rdf:Seq></Container:Directory></rdf:Description>" + TAIL
        ).encodeToByteArray()

    // Android 14 reads one value per field, so a three-channel map writes its first channel here; the ISO block has all.
    fun encodeGainmap(m: GainmapMetadata): ByteArray = (
        HEAD + "<rdf:Description rdf:about=\"\" xmlns:hdrgm=\"$HDRGM\" hdrgm:Version=\"1.0\"" +
            " hdrgm:GainMapMin=\"${m.gainMapMin.channel(0)}\" hdrgm:GainMapMax=\"${m.gainMapMax.channel(0)}\"" +
            " hdrgm:Gamma=\"${m.gamma.channel(0)}\" hdrgm:OffsetSDR=\"${m.offsetSdr.channel(0)}\"" +
            " hdrgm:OffsetHDR=\"${m.offsetHdr.channel(0)}\" hdrgm:HDRCapacityMin=\"${m.hdrCapacityMin}\"" +
            " hdrgm:HDRCapacityMax=\"${m.hdrCapacityMax}\" hdrgm:BaseRenditionIsHDR=\"False\"/>" + TAIL
        ).encodeToByteArray()

    /** Null without `hdrgm` values, or for an HDR base image. Missing optional fields take the format's defaults. */
    fun decode(xml: String): GainmapMetadata? {
        if (HDRGM !in xml) return null
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

    // An attribute, a simple element, or an element holding an rdf:Seq of one value per channel.
    private fun field(xml: String, name: String): List<String>? {
        Regex("""hdrgm:$name\s*=\s*(["'])([^"']*)\1""").find(xml)?.let { return listOf(it.groupValues[2]) }
        val body = Regex("""<hdrgm:$name>(.*?)</hdrgm:$name>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1) ?: return null
        val items = Regex("""<rdf:li>([^<]*)</rdf:li>""").findAll(body).map { it.groupValues[1] }.toList()
        return items.ifEmpty { listOf(body) }
    }
}

private fun List<Double>.channel(c: Int) = if (size == 1) this[0] else this[c]
