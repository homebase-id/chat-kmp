package id.homebase.api.image

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
 * ICC colour profiles (APP2) and the Adobe colour-transform marker (APP14).
 *
 * Unknown or malformed input is returned unchanged — this must never be able to corrupt a
 * send. JPEG, PNG and WebP are handled; every other format passes through untouched.
 */
object ImageMetadataScrubber {

    /**
     * Returns [bytes] with metadata removed, or the identical instance when there was
     * nothing to remove or the format isn't handled. Callers can use referential equality
     * (`result === bytes`) to skip re-writing the file.
     */
    fun scrub(bytes: ByteArray): ByteArray = when {
        isJpeg(bytes) -> scrubJpeg(bytes)
        isPng(bytes) -> scrubPng(bytes)
        isWebp(bytes) -> scrubWebp(bytes)
        else -> bytes
    }

    private fun isJpeg(b: ByteArray) =
        b.size >= 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    private fun isPng(b: ByteArray) =
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
            b[2] == 0x4E.toByte() && b[3] == 0x47.toByte() && b[4] == 0x0D.toByte() &&
            b[5] == 0x0A.toByte() && b[6] == 0x1A.toByte() && b[7] == 0x0A.toByte()

    private fun isWebp(b: ByteArray) =
        b.size >= 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
            b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() &&
            b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()

    // A span of the source to copy verbatim, or literal bytes to insert. Collected first so
    // the output is allocated exactly once rather than grown.
    private sealed interface Piece {
        class Span(val from: Int, val until: Int) : Piece
        class Literal(val data: ByteArray) : Piece
    }

    private fun assemble(pieces: List<Piece>, src: ByteArray): ByteArray {
        var size = 0
        for (p in pieces) size += when (p) {
            is Piece.Span -> p.until - p.from
            is Piece.Literal -> p.data.size
        }
        val out = ByteArray(size)
        var at = 0
        for (p in pieces) when (p) {
            is Piece.Span -> {
                src.copyInto(out, at, p.from, p.until); at += p.until - p.from
            }
            is Piece.Literal -> {
                p.data.copyInto(out, at); at += p.data.size
            }
        }
        return out
    }

    /* ---------------- JPEG ---------------- */

    private const val MARKER_SOS = 0xDA
    private const val MARKER_EOI = 0xD9
    private const val MARKER_COM = 0xFE
    private const val MARKER_APP0 = 0xE0
    private const val MARKER_APP1 = 0xE1
    private const val MARKER_APP2 = 0xE2
    private const val MARKER_APP14 = 0xEE

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

    private fun scrubJpeg(src: ByteArray): ByteArray {
        val orientation = readJpegOrientation(src)
        val pieces = mutableListOf<Piece>()
        pieces += Piece.Span(0, 2) // SOI

        var orientationEmitted = orientation == null || orientation == 1
        fun emitOrientation() {
            if (!orientationEmitted) {
                pieces += Piece.Literal(minimalExifApp1(orientation!!))
                orientationEmitted = true
            }
        }

        var i = 2
        var droppedAnything = false
        while (true) {
            if (i >= src.size) break
            if (src[i] != 0xFF.toByte()) return src // not a marker boundary — refuse to touch it
            val segmentStart = i
            var j = i
            while (j < src.size && src[j] == 0xFF.toByte()) j++ // fill bytes
            if (j >= src.size) { pieces += Piece.Span(segmentStart, src.size); break }
            val marker = src[j].toInt() and 0xFF
            i = j + 1

            // Standalone markers: no length field, no payload.
            if (marker == 0x01 || marker in 0xD0..0xD7) {
                pieces += Piece.Span(segmentStart, i)
                continue
            }
            if (marker == MARKER_EOI) {
                pieces += Piece.Span(segmentStart, src.size)
                break
            }
            if (marker == MARKER_SOS) {
                // Entropy-coded data follows to the end; metadata can't appear past here.
                emitOrientation()
                pieces += Piece.Span(segmentStart, src.size)
                break
            }
            if (i + 1 >= src.size) { pieces += Piece.Span(segmentStart, src.size); break }
            val length = ((src[i].toInt() and 0xFF) shl 8) or (src[i + 1].toInt() and 0xFF)
            if (length < 2) return src // malformed
            val segmentEnd = i + length
            if (segmentEnd > src.size) { pieces += Piece.Span(segmentStart, src.size); break }

            if (shouldDropSegment(marker)) {
                droppedAnything = true
            } else {
                // Re-emitted EXIF belongs after JFIF but before everything else.
                if (marker != MARKER_APP0) emitOrientation()
                pieces += Piece.Span(segmentStart, segmentEnd)
            }
            i = segmentEnd
        }

        if (!droppedAnything) return src
        return assemble(pieces, src)
    }

    /** EXIF `Orientation` (1..8), or null when absent/unparseable. */
    private fun readJpegOrientation(src: ByteArray): Int? {
        var i = 2
        while (i + 3 < src.size) {
            if (src[i] != 0xFF.toByte()) return null
            var j = i
            while (j < src.size && src[j] == 0xFF.toByte()) j++
            if (j >= src.size) return null
            val marker = src[j].toInt() and 0xFF
            i = j + 1
            if (marker == MARKER_SOS || marker == MARKER_EOI) return null
            if (marker == 0x01 || marker in 0xD0..0xD7) continue
            if (i + 1 >= src.size) return null
            val length = ((src[i].toInt() and 0xFF) shl 8) or (src[i + 1].toInt() and 0xFF)
            if (length < 2) return null
            val payloadStart = i + 2
            val segmentEnd = i + length
            if (segmentEnd > src.size) return null
            if (marker == MARKER_APP1 && isExifHeader(src, payloadStart, segmentEnd)) {
                return readTiffOrientation(src, payloadStart + 6, segmentEnd)
            }
            i = segmentEnd
        }
        return null
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
        if (u16(b, tiff + 2, little) != 42) return null
        val ifd = tiff + u32(b, tiff + 4, little)
        if (ifd + 2 > until || ifd < tiff) return null
        val count = u16(b, ifd, little)
        for (e in 0 until count) {
            val entry = ifd + 2 + e * 12
            if (entry + 12 > until) return null
            if (u16(b, entry, little) == 0x0112) {
                val value = u16(b, entry + 8, little)
                return if (value in 1..8) value else null
            }
        }
        return null
    }

    private fun u16(b: ByteArray, at: Int, little: Boolean): Int {
        val a = b[at].toInt() and 0xFF
        val c = b[at + 1].toInt() and 0xFF
        return if (little) (c shl 8) or a else (a shl 8) or c
    }

    private fun u32(b: ByteArray, at: Int, little: Boolean): Int {
        val x0 = b[at].toInt() and 0xFF
        val x1 = b[at + 1].toInt() and 0xFF
        val x2 = b[at + 2].toInt() and 0xFF
        val x3 = b[at + 3].toInt() and 0xFF
        return if (little) (x3 shl 24) or (x2 shl 16) or (x1 shl 8) or x0
        else (x0 shl 24) or (x1 shl 16) or (x2 shl 8) or x3
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

    /** [minimalExifTiff] wrapped as a JPEG APP1 segment, with the Exif identifier prefix. */
    private fun minimalExifApp1(orientation: Int): ByteArray {
        val payload =
            byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00) + minimalExifTiff(orientation)
        val length = payload.size + 2
        return byteArrayOf(
            0xFF.toByte(), MARKER_APP1.toByte(),
            ((length shr 8) and 0xFF).toByte(), (length and 0xFF).toByte(),
        ) + payload
    }

    /* ---------------- WebP ---------------- */

    // RIFF container: 12-byte header then FourCC + LE size + payload, each payload padded to
    // an even length. Metadata rides in dedicated chunks rather than being spliced into the
    // bitstream, so removing them is a chunk-level operation.
    private const val WEBP_FLAG_EXIF = 0x08
    private const val WEBP_FLAG_XMP = 0x04

    private fun scrubWebp(src: ByteArray): ByteArray {
        val orientation = readWebpOrientation(src)
        val keepOrientation = orientation != null && orientation != 1

        val pieces = mutableListOf<Piece>()
        pieces += Piece.Span(0, 12)
        var i = 12
        var droppedAnything = false
        var sawVp8x = false

        while (i + 8 <= src.size) {
            val type = fourCc(src, i)
            val size = u32(src, i + 4, little = true)
            if (size < 0) return src // >2 GiB chunk, or malformed
            val padded = size + (size and 1)
            val chunkEnd = i + 8 + padded
            if (chunkEnd > src.size || chunkEnd < i) { pieces += Piece.Span(i, src.size); break }

            when (type) {
                "EXIF", "XMP " -> droppedAnything = true
                "VP8X" -> {
                    sawVp8x = true
                    // The flags byte advertises which optional chunks are present. Leaving
                    // EXIF/XMP advertised after removing them yields a file decoders may
                    // reject, so rewrite it rather than copying the span.
                    if (size < 1) { pieces += Piece.Span(i, chunkEnd) } else {
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
                else -> pieces += Piece.Span(i, chunkEnd)
            }
            i = chunkEnd
        }

        // Metadata chunks belong at the end of the stream, after the image data.
        if (keepOrientation && sawVp8x) pieces += Piece.Literal(webpExifChunk(orientation!!))

        if (!droppedAnything) return src
        val out = assemble(pieces, src)
        // RIFF size counts everything after the first 8 bytes and must match the new length.
        val riffSize = out.size - 8
        out[4] = (riffSize and 0xFF).toByte()
        out[5] = ((riffSize shr 8) and 0xFF).toByte()
        out[6] = ((riffSize shr 16) and 0xFF).toByte()
        out[7] = ((riffSize shr 24) and 0xFF).toByte()
        return out
    }

    private fun fourCc(b: ByteArray, at: Int): String = buildString {
        for (k in 0 until 4) append((b[at + k].toInt() and 0xFF).toChar())
    }

    private fun webpExifChunk(orientation: Int): ByteArray {
        val payload = minimalExifTiff(orientation)
        val size = payload.size
        val header = byteArrayOf(
            'E'.code.toByte(), 'X'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(),
            (size and 0xFF).toByte(), ((size shr 8) and 0xFF).toByte(),
            ((size shr 16) and 0xFF).toByte(), ((size shr 24) and 0xFF).toByte(),
        )
        // Chunk payloads are padded to an even length.
        val pad = if (size and 1 == 1) byteArrayOf(0) else ByteArray(0)
        return header + payload + pad
    }

    /**
     * WebP stores the raw TIFF block in its EXIF chunk. Some encoders prefix it with the
     * JPEG-style `Exif\u0000\u0000` marker anyway, so accept both.
     */
    private fun readWebpOrientation(src: ByteArray): Int? {
        var i = 12
        while (i + 8 <= src.size) {
            val type = fourCc(src, i)
            val size = u32(src, i + 4, little = true)
            if (size < 0) return null
            val padded = size + (size and 1)
            val payloadStart = i + 8
            val payloadEnd = payloadStart + size
            val chunkEnd = payloadStart + padded
            if (payloadEnd > src.size || chunkEnd < i) return null
            if (type == "EXIF") {
                val tiff =
                    if (isExifHeader(src, payloadStart, payloadEnd)) payloadStart + 6
                    else payloadStart
                return readTiffOrientation(src, tiff, payloadEnd)
            }
            i = chunkEnd
        }
        return null
    }

    /* ---------------- PNG ---------------- */

    // eXIf carries the same EXIF block as JPEG; the text chunks routinely carry camera and
    // editing-software strings; tIME is a modification timestamp.
    private val PNG_DROP = setOf("eXIf", "tEXt", "iTXt", "zTXt", "tIME")

    private fun scrubPng(src: ByteArray): ByteArray {
        val pieces = mutableListOf<Piece>()
        pieces += Piece.Span(0, 8) // signature
        var i = 8
        var droppedAnything = false
        while (i + 8 <= src.size) {
            val length = u32(src, i, little = false)
            if (length < 0) return src // > 2 GiB chunk, or malformed
            val type = buildString {
                for (k in 0 until 4) append((src[i + 4 + k].toInt() and 0xFF).toChar())
            }
            val chunkEnd = i + 12 + length // length + type + data + crc
            if (chunkEnd > src.size || chunkEnd < i) { pieces += Piece.Span(i, src.size); break }
            if (type in PNG_DROP) {
                droppedAnything = true
            } else {
                pieces += Piece.Span(i, chunkEnd)
            }
            i = chunkEnd
            if (type == "IEND") break
        }
        if (!droppedAnything) return src
        return assemble(pieces, src)
    }
}
