package id.homebase.api.image

import id.homebase.api.lib.image.ImageFormatDetector

/**
 * Removes identity-bearing metadata from encoded image bytes without touching pixel data.
 *
 * The send path uploads the picked file byte-for-byte, so a camera JPEG arrived at the
 * recipient carrying EXIF GPS, capture time and camera serial (#1297). Re-encoding to drop
 * it would cost a generation of quality on every photo; dropping the metadata segments
 * leaves the compressed image data bit-identical.
 *
 * **Orientation is preserved deliberately.** It lives in the same EXIF block as the GPS,
 * and naively dropping the block rotates people's photos. A minimal EXIF containing only
 * `Orientation` is re-emitted when the source had a non-default value.
 *
 * Kept because they affect how the image decodes rather than who took it: JFIF (APP0),
 * ICC colour profiles (APP2) and the Adobe colour-transform marker (APP14). An HDR JPEG's
 * gain map is declared in its XMP, so that XMP is rebuilt with only the gain-map fields.
 *
 * Unknown or malformed input is returned unchanged — this must never be able to corrupt a
 * send. JPEG, PNG, WebP and HEIF/HEIC are handled; every other format passes through untouched.
 * [scrubWithOutcome] says which of those happened, so a caller can warn when metadata may remain.
 */
object ImageMetadataScrubber {

    sealed interface Outcome {
        class Scrubbed(val bytes: ByteArray) : Outcome
        data object NothingToStrip : Outcome
        data object UnsupportedFormat : Outcome
        /** A handled format the scrubber couldn't follow, so its metadata may still be there. */
        data object Unparseable : Outcome
    }

    /**
     * Returns [bytes] with metadata removed, or the identical instance when there was
     * nothing to remove or the format isn't handled. Callers can use referential equality
     * (`result === bytes`) to skip re-writing the file.
     */
    fun scrub(bytes: ByteArray): ByteArray = (scrubWithOutcome(bytes) as? Outcome.Scrubbed)?.bytes ?: bytes

    fun scrubWithOutcome(bytes: ByteArray): Outcome = when {
        isJpeg(bytes) -> scrubJpeg(bytes)
        isPng(bytes) -> scrubPng(bytes)
        isWebp(bytes) -> scrubWebp(bytes)
        ImageFormatDetector.isHeic(bytes) -> scrubHeif(bytes)
        else -> Outcome.UnsupportedFormat
    }

    /** A source's EXIF (its TIFF block) and XMP packet, read for a re-encode that keeps them. */
    class Blocks(val exif: ByteArray?, val xmp: ByteArray?)

    /** The formats [readBlocks] reads, by content type. */
    val BLOCK_FORMATS = setOf("image/jpeg", "image/webp", "image/heic", "image/heif")

    /** [bytes]' metadata, or null when it has none or isn't a format read here (JPEG, WebP, HEIF). */
    fun readBlocks(bytes: ByteArray): Blocks? = when {
        isJpeg(bytes) -> jpegBlocks(bytes)
        isWebp(bytes) -> webpBlocks(bytes)
        ImageFormatDetector.isHeic(bytes) -> heifBlocks(bytes)
        else -> null
    }?.takeIf { it.exif != null || it.xmp != null }

    private fun jpegBlocks(src: ByteArray): Blocks {
        var exif: ByteArray? = null
        var xmp: ByteArray? = null
        walkJpegSegments(src) { marker, payload, end ->
            if (marker == MARKER_APP1) {
                if (exif == null && isExifHeader(src, payload, end)) exif = src.copyOfRange(payload + 6, end)
                if (xmp == null && startsWith(src, payload, end, XMP_JPEG_HEADER)) xmp = src.copyOfRange(payload + XMP_JPEG_HEADER.size, end)
            }
            true
        }
        return Blocks(exif, xmp)
    }

    private fun webpBlocks(src: ByteArray): Blocks {
        var exif: ByteArray? = null
        var xmp: ByteArray? = null
        walkWebpChunks(src) { type, payload, end ->
            when (type) {
                "EXIF" -> exif = src.copyOfRange(webpExifTiffStart(src, payload, end), end)
                "XMP " -> xmp = src.copyOfRange(payload, end)
            }
            true
        }
        return Blocks(exif, xmp)
    }

    private fun heifBlocks(src: ByteArray): Blocks? {
        val items = heifMetadataItems(src) ?: return null
        return Blocks(
            exif = items.firstOrNull { it.isExif }?.let { heifExifTiff(src, it.ranges) },
            xmp = items.firstOrNull { !it.isExif }?.let { concat(src, it.ranges) },
        )
    }

    private fun isPng(b: ByteArray) =
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
            b[2] == 0x4E.toByte() && b[3] == 0x47.toByte() && b[4] == 0x0D.toByte() &&
            b[5] == 0x0A.toByte() && b[6] == 0x1A.toByte() && b[7] == 0x0A.toByte()

    private fun isWebp(b: ByteArray) =
        b.size >= 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
            b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() &&
            b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()

    /* ---------------- JPEG ---------------- */


    /**
     * Drop application segments that carry provenance: APP1 (EXIF and XMP), APP13
     * (Photoshop/IPTC), any other vendor APPn, and free-text comments. APP0/APP2/APP14 are
     * decode-affecting, not identifying, so they stay.
     */
    private fun shouldDropSegment(marker: Int): Boolean = when (marker) {
        MARKER_APP0, MARKER_APP2, MARKER_APP14 -> false
        MARKER_COM -> true
        in 0xE0..0xEF -> true
        else -> false
    }

    private fun scrubJpeg(src: ByteArray): Outcome {
        val orientation = readJpegOrientation(src)
        val pieces = mutableListOf<Piece>()
        pieces += Piece.Span(src, 0, 2) // SOI

        // How much the primary image grew (negative: shrank), in all and after an MPF index, whose entries count from it.
        var resized = 0
        var resizedAfterMpf = 0
        var mpf: Piece.Span? = null
        fun resize(by: Int) {
            resized += by
            if (mpf != null) resizedAfterMpf += by
        }

        var orientationEmitted = orientation == null || orientation == 1
        fun emitOrientation() {
            if (!orientationEmitted) {
                val exif = minimalExifApp1(orientation!!)
                pieces += Piece.Literal(exif)
                resize(exif.size)
                orientationEmitted = true
            }
        }

        var i = 2
        var droppedAnything = false
        while (true) {
            if (i >= src.size) break
            if (src[i] != 0xFF.toByte()) return Outcome.Unparseable // not a marker boundary — refuse to touch it
            val segmentStart = i
            var j = i
            while (j < src.size && src[j] == 0xFF.toByte()) j++ // fill bytes
            if (j >= src.size) { pieces += Piece.Span(src, segmentStart, src.size); break }
            val marker = src[j].toInt() and 0xFF
            i = j + 1

            // Standalone markers: no length field, no payload.
            if (marker == 0x01 || marker in 0xD0..0xD7) {
                pieces += Piece.Span(src, segmentStart, i)
                continue
            }
            if (marker == MARKER_EOI) {
                pieces += Piece.Span(src, segmentStart, src.size)
                break
            }
            if (marker == MARKER_SOS) {
                // Entropy-coded data follows to the end; metadata can't appear past here.
                emitOrientation()
                pieces += Piece.Span(src, segmentStart, src.size)
                break
            }
            if (i + 1 >= src.size) { pieces += Piece.Span(src, segmentStart, src.size); break }
            val length = ((src[i].toInt() and 0xFF) shl 8) or (src[i + 1].toInt() and 0xFF)
            if (length < 2) return Outcome.Unparseable
            val segmentEnd = i + length
            if (segmentEnd > src.size) {
                if (shouldDropSegment(marker)) droppedAnything = true else pieces += Piece.Span(src, segmentStart, src.size)
                break
            }

            val rebuiltXmp = if (marker == MARKER_APP1) gainmapXmpApp1(src, i + 2, segmentEnd) else null
            // Already rebuilt, so scrubbing twice changes nothing.
            val unchanged = rebuiltXmp != null && rebuiltXmp.contentEquals(src.copyOfRange(j - 1, segmentEnd))
            val gainmapXmp = rebuiltXmp.takeUnless { unchanged }
            if (gainmapXmp == null && !unchanged && shouldDropSegment(marker)) {
                droppedAnything = true
                resize(segmentStart - segmentEnd)
            } else {
                // Re-emitted EXIF belongs after JFIF but before everything else.
                if (marker != MARKER_APP0) emitOrientation()
                if (gainmapXmp != null) {
                    droppedAnything = true
                    pieces += Piece.Literal(gainmapXmp)
                    resize(gainmapXmp.size - (segmentEnd - segmentStart))
                } else {
                    val span = Piece.Span(src, segmentStart, segmentEnd)
                    if (marker == MARKER_APP2 && mpf == null && startsWith(src, i + 2, segmentEnd, MPF_HEADER)) mpf = span
                    pieces += span
                }
            }
            i = segmentEnd
        }

        if (!droppedAnything) return Outcome.NothingToStrip
        mpf?.let { span ->
            if (resized != 0) pieces[pieces.indexOf(span)] = Piece.Literal(movedMpf(src, span, resized, resizedAfterMpf))
        }
        return Outcome.Scrubbed(assemble(pieces))
    }


    /**
     * The MPF index (CIPA DC-007) after the primary image changed size: its first entry's size, and every later
     * entry's offset, which counts from the index itself. Unchanged when it can't be read.
     */
    private fun movedMpf(src: ByteArray, span: Piece.Span, resized: Int, resizedAfter: Int): ByteArray {
        val out = src.copyOfRange(span.from, span.until)
        val index = mpfEntries(out, tiff = 4 + 4, end = out.size) ?: return out // FF E2, length, then "MPF\0"
        for (k in 0 until index.count) {
            val at = index.list + k * 16
            if (k == 0) writeU32(out, at + 4, readU32(out, at + 4, index.little) + resized, index.little)
            else readU32(out, at + 8, index.little).takeIf { it != 0 }?.let { writeU32(out, at + 8, it + resizedAfter, index.little) }
        }
        return out
    }

    private fun writeU32(b: ByteArray, at: Int, value: Int, little: Boolean) {
        for (k in 0 until 4) b[at + if (little) k else 3 - k] = (value ushr (8 * k)).toByte()
    }

    /** EXIF `Orientation` (1..8), or null when absent/unparseable. */
    private fun readJpegOrientation(src: ByteArray): Int? {
        var orientation: Int? = null
        walkJpegSegments(src) { marker, payload, end ->
            val isExif = marker == MARKER_APP1 && isExifHeader(src, payload, end)
            if (isExif) orientation = readTiffOrientation(src, payload + 6, end)
            !isExif
        }
        return orientation
    }


    private fun isExifHeader(b: ByteArray, from: Int, until: Int): Boolean =
        until - from >= 6 &&
            b[from] == 'E'.code.toByte() && b[from + 1] == 'x'.code.toByte() &&
            b[from + 2] == 'i'.code.toByte() && b[from + 3] == 'f'.code.toByte() &&
            b[from + 4] == 0.toByte() && b[from + 5] == 0.toByte()

    private fun readTiffOrientation(b: ByteArray, tiff: Int, until: Int): Int? {
        if (tiff + 8 > until) return null
        val little = when {
            b[tiff] == 'I'.code.toByte() && b[tiff + 1] == 'I'.code.toByte() -> true
            b[tiff] == 'M'.code.toByte() && b[tiff + 1] == 'M'.code.toByte() -> false
            else -> return null
        }
        if (readU16(b, tiff + 2, little) != 42) return null
        val ifd = tiff + readU32(b, tiff + 4, little)
        if (ifd + 2 > until || ifd < tiff) return null
        val count = readU16(b, ifd, little)
        for (e in 0 until count) {
            val entry = ifd + 2 + e * 12
            if (entry + 12 > until) return null
            if (readU16(b, entry, little) == 0x0112) {
                val value = readU16(b, entry + 8, little)
                return if (value in 1..8) value else null
            }
        }
        return null
    }

    /** A TIFF block whose entire IFD0 is a single Orientation tag — nothing identifying. */
    private fun minimalExifTiff(orientation: Int): ByteArray = byteArrayOf(
        0x49, 0x49, 0x2A, 0x00,                         // TIFF header, little-endian
        0x08, 0x00, 0x00, 0x00,                         // IFD0 at offset 8
        0x01, 0x00,                                     // one entry
        0x12, 0x01,                                     // tag 0x0112 Orientation
        0x03, 0x00,                                     // type SHORT
        0x01, 0x00, 0x00, 0x00,                         // count 1
        (orientation and 0xFF).toByte(), 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,                         // no next IFD
    )

    private fun minimalExifApp1(orientation: Int): ByteArray = exifApp1(minimalExifTiff(orientation))

    /**
     * [jpeg] with an Exif APP1 holding [tiff] after SOI and any JFIF, and its MPF index (an Ultra HDR file's) told the
     * primary image grew. Unchanged when it isn't a JPEG or [tiff] can't fit one segment.
     */
    internal fun withExif(jpeg: ByteArray, tiff: ByteArray): ByteArray {
        if (!isJpeg(jpeg) || tiff.size + 8 > 0xFFFF) return jpeg
        val exif = exifApp1(tiff)
        var at = 2
        var mpf: Piece.Span? = null
        walkJpegSegments(jpeg) { marker, payload, end ->
            if (marker == MARKER_APP0 && payload == 6) at = end
            if (marker == MARKER_APP2 && startsWith(jpeg, payload, end, MPF_HEADER)) mpf = Piece.Span(jpeg, payload - 4, end)
            mpf == null
        }
        val index = mpf?.takeIf { it.from >= at }
        val pieces = if (index == null) {
            listOf(Piece.Span(jpeg, 0, at), Piece.Literal(exif), Piece.Span(jpeg, at, jpeg.size))
        } else {
            listOf(
                Piece.Span(jpeg, 0, at), Piece.Literal(exif), Piece.Span(jpeg, at, index.from),
                Piece.Literal(movedMpf(jpeg, index, resized = exif.size, resizedAfter = 0)), Piece.Span(jpeg, index.until, jpeg.size),
            )
        }
        return assemble(pieces)
    }

    /** [jpeg] tagged with [orientation], so decoders turn it upright the way they turn its source. */
    internal fun withOrientation(jpeg: ByteArray, orientation: Int): ByteArray = withExif(jpeg, minimalExifTiff(orientation))

    private fun exifApp1(tiff: ByteArray): ByteArray = jpegSegment(MARKER_APP1, byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00) + tiff)


    private val HDRGM_VERSION = Regex("""hdrgm:Version\s*=\s*(["'])([0-9]{1,3}\.[0-9]{1,3})\1|<hdrgm:Version>([0-9]{1,3}\.[0-9]{1,3})</hdrgm:Version>""")
    private val CONTAINER_DIRECTORY = Regex("""<Container:Directory>(.*?)</Container:Directory>""", RegexOption.DOT_MATCHES_ALL)
    private val CONTAINER_ITEM = Regex("""<Container:Item\s([^<>]*?)/>""")
    private val ITEM_ATTRIBUTE = Regex("""Item:(\w+)\s*=\s*(["'])([^"'<>]*)\2""")

    /**
     * The Container directory's items, each Primary or GainMap with an image MIME type and numeric sizes, or ""
     * when anything else is there: another item (a motion photo's video, a depth map), element-form items, a
     * comment. Decoders then find the gain map through the MPF index alone.
     */
    private fun gainmapDirectory(xml: String): String {
        val body = CONTAINER_DIRECTORY.find(xml)?.groupValues?.get(1) ?: return ""
        if ("<!--" in body || "CDATA" in body) return ""
        val items = CONTAINER_ITEM.findAll(body).toList()
        if (items.isEmpty() || items.size != Regex("<Container:Item\\b").findAll(body).count()) return ""
        val rebuilt = items.map { item ->
            val attributes = LinkedHashMap<String, String>()
            ITEM_ATTRIBUTE.findAll(item.groupValues[1]).forEach { attributes.getOrPut(it.groupValues[1]) { it.groupValues[3] } }
            val semantic = attributes["Semantic"]?.takeIf { it == "Primary" || it == "GainMap" } ?: return ""
            val mime = attributes["Mime"]?.takeIf { it == "image/jpeg" } ?: return ""
            val sizes = listOf("Length", "Padding").mapNotNull { name ->
                attributes[name]?.let { value -> if (value.length in 1..10 && value.all(Char::isDigit)) " Item:$name=\"$value\"" else return "" }
            }
            "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"$semantic\" Item:Mime=\"$mime\"${sizes.joinToString("")}/></rdf:li>"
        }
        return "<Container:Directory><rdf:Seq>${rebuilt.joinToString("")}</rdf:Seq></Container:Directory>"
    }

    /**
     * An Ultra HDR JPEG declares its gain map in the primary image's XMP (`hdrgm:Version`, and the
     * Container directory that says where the gain-map image is). Without it, decoders show the SDR
     * image only. Null when this APP1 isn't such an XMP; otherwise the same declaration rebuilt from
     * an allowlist, so anything else the XMP held (location, camera, dates) is still dropped.
     */
    private fun gainmapXmpApp1(b: ByteArray, from: Int, until: Int): ByteArray? {
        if (!startsWith(b, from, until, XMP_JPEG_HEADER)) return null
        val xml = b.decodeToString(from + XMP_JPEG_HEADER.size, until)
        if (HDRGM_NS !in xml) return null
        val version = HDRGM_VERSION.find(xml)?.groupValues?.let { it[2].ifEmpty { it[3] } } ?: return null
        val directory = gainmapDirectory(xml)
        val xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\" xmlns:hdrgm=\"$HDRGM_NS\" xmlns:Container=\"http://ns.google.com/photos/1.0/container/\" " +
            "xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\" hdrgm:Version=\"$version\">$directory</rdf:Description></rdf:RDF></x:xmpmeta>"
        return (XMP_JPEG_HEADER + xmp.encodeToByteArray()).takeIf { it.size + 2 <= 0xFFFF }?.let { jpegSegment(MARKER_APP1, it) }
    }

    /* ---------------- WebP ---------------- */

    // RIFF container: 12-byte header then FourCC + LE size + payload, each payload padded to
    // an even length. Metadata rides in dedicated chunks rather than being spliced into the
    // bitstream, so removing them is a chunk-level operation.
    private const val WEBP_FLAG_EXIF = 0x08
    private const val WEBP_FLAG_XMP = 0x04

    private fun scrubWebp(src: ByteArray): Outcome {
        val orientation = readWebpOrientation(src)
        val keepOrientation = orientation != null && orientation != 1

        val pieces = mutableListOf<Piece>()
        pieces += Piece.Span(src, 0, 12)
        var i = 12
        var droppedAnything = false
        var sawVp8x = false

        while (i + 8 <= src.size) {
            val type = fourCc(src, i)
            val size = readU32(src, i + 4, little = true)
            if (size < 0) return Outcome.Unparseable // >2 GiB chunk, or malformed
            val padded = size + (size and 1)
            val chunkEnd = i + 8 + padded
            if (chunkEnd > src.size || chunkEnd < i) {
                if (type == "EXIF" || type == "XMP ") droppedAnything = true else pieces += Piece.Span(src, i, src.size)
                break
            }

            when (type) {
                "EXIF", "XMP " -> droppedAnything = true
                "VP8X" -> {
                    sawVp8x = true
                    // The flags byte advertises which optional chunks are present. Leaving
                    // EXIF/XMP advertised after removing them yields a file decoders may
                    // reject, so rewrite it rather than copying the span.
                    if (size < 1) { pieces += Piece.Span(src, i, chunkEnd) } else {
                        val chunk = src.copyOfRange(i, chunkEnd)
                        val flags = chunk[8].toInt() and 0xFF
                        val updated = if (keepOrientation) {
                            (flags or WEBP_FLAG_EXIF) and WEBP_FLAG_XMP.inv()
                        } else {
                            flags and (WEBP_FLAG_EXIF or WEBP_FLAG_XMP).inv()
                        }
                        if (updated != flags) droppedAnything = true
                        chunk[8] = updated.toByte()
                        pieces += Piece.Literal(chunk)
                    }
                }
                else -> pieces += Piece.Span(src, i, chunkEnd)
            }
            i = chunkEnd
        }

        // Metadata chunks belong at the end of the stream, after the image data.
        if (keepOrientation && sawVp8x) pieces += Piece.Literal(webpChunk("EXIF", minimalExifTiff(orientation)))

        if (!droppedAnything) return Outcome.NothingToStrip
        val out = assemble(pieces)
        // RIFF size counts everything after the first 8 bytes and must match the new length.
        writeLe32(out, 4, out.size - 8)
        return Outcome.Scrubbed(out)
    }

    /**
     * WebP stores the raw TIFF block in its EXIF chunk. Some encoders prefix it with the
     * JPEG-style `Exif\u0000\u0000` marker anyway, so accept both.
     */
    private fun readWebpOrientation(src: ByteArray): Int? {
        var orientation: Int? = null
        walkWebpChunks(src) { type, payload, end ->
            if (type == "EXIF") orientation = readTiffOrientation(src, webpExifTiffStart(src, payload, end), end)
            type != "EXIF"
        }
        return orientation
    }

    private fun webpExifTiffStart(src: ByteArray, payload: Int, end: Int) =
        if (isExifHeader(src, payload, end)) payload + 6 else payload

    /** Visits each chunk as (type, payload start, payload end) until [visit] returns false. Stops at the first malformed chunk. */
    private inline fun walkWebpChunks(src: ByteArray, visit: (type: String, payload: Int, end: Int) -> Boolean) {
        var i = 12
        while (i + 8 <= src.size) {
            val size = readU32(src, i + 4, little = true)
            if (size < 0) return
            val payload = i + 8
            val end = payload + size
            val chunkEnd = end + (size and 1)
            if (end > src.size || chunkEnd < i || !visit(fourCc(src, i), payload, end)) return
            i = chunkEnd
        }
    }

    /* ---------------- HEIF ---------------- */

    // Overwriting the Exif and XMP items at the same length keeps every iloc offset valid. Orientation
    // is in the untouched irot/imir properties; the EXIF copy stays for readers that look there.
    private const val XMP_CONTENT_TYPE = "application/rdf+xml"

    private class HeifItem(val type: String, val contentType: String?)

    private class Extent(val offset: Long, val length: Long, val inIdat: Boolean)

    private fun scrubHeif(src: ByteArray): Outcome {
        val items = heifMetadataItems(src) ?: return Outcome.Unparseable
        val out = src.copyOf()
        for (item in items) {
            val exif = if (item.isExif) heifExifPayload(readHeifExifOrientation(src, item.ranges)) else ByteArray(0)
            val replacement = if (exif.size <= item.ranges.sumOf { it.last - it.first + 1 }) exif else ByteArray(0)
            var k = 0
            for (range in item.ranges) for (at in range) {
                out[at] = if (k < replacement.size) replacement[k] else if (item.isExif) 0 else 0x20
                k++
            }
        }
        return if (out.contentEquals(src)) Outcome.NothingToStrip else Outcome.Scrubbed(out)
    }

    /** An Exif or XMP item, by the file ranges its data occupies. */
    private class HeifMetadataItem(val isExif: Boolean, val ranges: List<IntRange>)

    /** The file's Exif and XMP items, or null when its boxes can't be followed. */
    private fun heifMetadataItems(src: ByteArray): List<HeifMetadataItem>? {
        val meta = topLevelBox(src, "meta") ?: return null
        val items = HashMap<Long, HeifItem>()
        val locations = HashMap<Long, List<Extent>>()
        var idatData: Long = -1
        var i = meta.first + 4 // full box
        while (i + 8 <= meta.second) {
            val box = boxAt(src, i, meta.second) ?: return null
            when (box.type) {
                "iinf" -> if (!readIinf(src, box.dataStart, box.end, items)) return null
                "iloc" -> if (!readIloc(src, box.dataStart, box.end, locations)) return null
                "idat" -> idatData = box.dataStart.toLong()
            }
            i = box.end
        }
        val found = mutableListOf<HeifMetadataItem>()
        for ((id, item) in items) {
            val isExif = item.type == "Exif"
            val isXmp = item.type == "mime" && item.contentType?.substringBefore(';')?.trim() == XMP_CONTENT_TYPE
            if (!isExif && !isXmp) continue
            val extents = locations[id] ?: return null
            found += HeifMetadataItem(isExif, extents.map { extent ->
                if (extent.inIdat && idatData < 0) return null
                val start = extent.offset + if (extent.inIdat) idatData else 0
                if (start < 0 || extent.length <= 0 || start + extent.length > src.size) return null
                start.toInt() until (start + extent.length).toInt()
            })
        }
        return found
    }

    private fun concat(src: ByteArray, ranges: List<IntRange>): ByteArray {
        val out = ByteArray(ranges.sumOf { it.last - it.first + 1 })
        var at = 0
        for (r in ranges) { src.copyInto(out, at, r.first, r.last + 1); at += r.last - r.first + 1 }
        return out
    }

    /** HEIF's Exif item is a 4-byte offset to the TIFF header, then the TIFF block: the block, or null. */
    private fun heifExifTiff(src: ByteArray, ranges: List<IntRange>): ByteArray? {
        val data = concat(src, ranges)
        if (data.size < 4) return null
        val tiff = 4 + readU32(data, 0, little = false)
        return if (tiff in 4 until data.size) data.copyOfRange(tiff, data.size) else null
    }

    private class Box(val type: String, val dataStart: Int, val end: Int)

    private fun boxAt(b: ByteArray, at: Int, limit: Int): Box? {
        if (at + 8 > limit) return null
        val size32 = readU32(b, at, little = false).toLong() and 0xFFFFFFFFL
        val type = fourCc(b, at + 4)
        val (size, header) = when (size32) {
            0L -> (limit - at).toLong() to 8
            1L -> {
                if (at + 16 > limit) return null
                u64(b, at + 8) to 16
            }
            else -> size32 to 8
        }
        if (size < header || at + size > limit) return null
        return Box(type, at + header, (at + size).toInt())
    }

    private fun topLevelBox(b: ByteArray, type: String): Pair<Int, Int>? {
        var i = 0
        while (i + 8 <= b.size) {
            val box = boxAt(b, i, b.size) ?: return null
            if (box.type == type) return box.dataStart to box.end
            i = box.end
        }
        return null
    }

    private fun readIinf(b: ByteArray, from: Int, until: Int, into: MutableMap<Long, HeifItem>): Boolean {
        if (from + 4 > until) return false
        val version = b[from].toInt() and 0xFF
        var i = from + 4 + if (version == 0) 2 else 4
        while (i + 8 <= until) {
            val box = boxAt(b, i, until) ?: return false
            // Versions 0 and 1 predate item types, so they can't declare Exif or XMP items.
            if (box.type == "infe" && box.dataStart < until && b[box.dataStart].toInt() and 0xFF >= 2) {
                val (id, item) = readInfe(b, box.dataStart, box.end) ?: return false
                into[id] = item
            }
            i = box.end
        }
        return true
    }

    private fun readInfe(b: ByteArray, from: Int, until: Int): Pair<Long, HeifItem>? {
        if (from + 4 > until) return null
        val version = b[from].toInt() and 0xFF
        var i = from + 4
        val idSize = if (version == 2) 2 else 4
        if (i + idSize + 6 > until) return null
        val id = readUnsigned(b, i, idSize)
        i += idSize + 2 // item_protection_index
        val type = fourCc(b, i)
        i += 4
        val nameEnd = indexOfZero(b, i, until) ?: return id to HeifItem(type, null)
        if (type != "mime") return id to HeifItem(type, null)
        val contentEnd = indexOfZero(b, nameEnd + 1, until) ?: until
        return id to HeifItem(type, b.decodeToString(nameEnd + 1, contentEnd))
    }

    private fun readIloc(b: ByteArray, from: Int, until: Int, into: MutableMap<Long, List<Extent>>): Boolean {
        if (from + 6 > until) return false
        val version = b[from].toInt() and 0xFF
        if (version > 2) return false
        var i = from + 4
        val offsetSize = (b[i].toInt() and 0xF0) shr 4
        val lengthSize = b[i].toInt() and 0x0F
        val baseOffsetSize = (b[i + 1].toInt() and 0xF0) shr 4
        val indexSize = if (version == 0) 0 else b[i + 1].toInt() and 0x0F
        if (listOf(offsetSize, lengthSize, baseOffsetSize, indexSize).any { it !in setOf(0, 4, 8) }) return false
        i += 2
        val countSize = if (version < 2) 2 else 4
        if (i + countSize > until) return false
        val count = readUnsigned(b, i, countSize)
        i += countSize
        repeat(count.toInt()) {
            val idSize = if (version < 2) 2 else 4
            if (i + idSize > until) return false
            val id = readUnsigned(b, i, idSize)
            i += idSize
            var method = 0
            if (version >= 1) {
                if (i + 2 > until) return false
                method = readU16(b, i, little = false) and 0x0F
                i += 2
            }
            if (i + 2 + baseOffsetSize + 2 > until) return false
            i += 2 // data_reference_index
            val base = readUnsigned(b, i, baseOffsetSize)
            i += baseOffsetSize
            val extentCount = readU16(b, i, little = false)
            i += 2
            val extents = ArrayList<Extent>(extentCount)
            repeat(extentCount) {
                if (i + indexSize + offsetSize + lengthSize > until) return false
                i += indexSize
                val offset = base + readUnsigned(b, i, offsetSize)
                i += offsetSize
                val length = readUnsigned(b, i, lengthSize)
                i += lengthSize
                extents += Extent(offset, length, inIdat = method == 1)
            }
            // Item-offset construction points into another item; leave those alone.
            if (method <= 1) into[id] = extents
        }
        return true
    }

    private fun readHeifExifOrientation(b: ByteArray, ranges: List<IntRange>): Int? =
        heifExifTiff(b, ranges)?.let { readTiffOrientation(it, 0, it.size) }

    /** HEIF's Exif item: a 4-byte offset to the TIFF header, then the TIFF block. */
    private fun heifExifPayload(orientation: Int?): ByteArray =
        byteArrayOf(0, 0, 0, 0) + minimalExifTiff(orientation ?: 1)

    private fun readUnsigned(b: ByteArray, at: Int, size: Int): Long = when (size) {
        0 -> 0L
        2 -> readU16(b, at, little = false).toLong()
        4 -> readU32(b, at, little = false).toLong() and 0xFFFFFFFFL
        8 -> u64(b, at)
        else -> 0L
    }

    private fun u64(b: ByteArray, at: Int): Long =
        ((readU32(b, at, little = false).toLong() and 0xFFFFFFFFL) shl 32) or
            (readU32(b, at + 4, little = false).toLong() and 0xFFFFFFFFL)

    private fun indexOfZero(b: ByteArray, from: Int, until: Int): Int? {
        for (k in from until until) if (b[k] == 0.toByte()) return k
        return null
    }

    /* ---------------- PNG ---------------- */

    // eXIf carries the same EXIF block as JPEG; the text chunks routinely carry camera and
    // editing-software strings; tIME is a modification timestamp.
    private val PNG_DROP = setOf("eXIf", "tEXt", "iTXt", "zTXt", "tIME")

    private fun scrubPng(src: ByteArray): Outcome {
        val pieces = mutableListOf<Piece>()
        pieces += Piece.Span(src, 0, 8) // signature
        var i = 8
        var droppedAnything = false
        while (i + 8 <= src.size) {
            val length = readU32(src, i, little = false)
            if (length < 0) return Outcome.Unparseable // > 2 GiB chunk, or malformed
            val type = buildString {
                for (k in 0 until 4) append((src[i + 4 + k].toInt() and 0xFF).toChar())
            }
            val chunkEnd = i + 12 + length // length + type + data + crc
            if (chunkEnd > src.size || chunkEnd < i) {
                if (type in PNG_DROP) droppedAnything = true else pieces += Piece.Span(src, i, src.size)
                break
            }
            if (type in PNG_DROP) {
                droppedAnything = true
            } else {
                pieces += Piece.Span(src, i, chunkEnd)
            }
            i = chunkEnd
            if (type == "IEND") break
        }
        if (!droppedAnything) return Outcome.NothingToStrip
        return Outcome.Scrubbed(assemble(pieces))
    }
}
