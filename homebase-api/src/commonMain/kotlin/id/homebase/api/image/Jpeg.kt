package id.homebase.api.image

// JPEG segment work shared by the metadata scrubber and the Ultra HDR container.

internal const val MARKER_SOS = 0xDA
internal const val MARKER_EOI = 0xD9
internal const val MARKER_COM = 0xFE
internal const val MARKER_APP0 = 0xE0
internal const val MARKER_APP1 = 0xE1
internal const val MARKER_APP2 = 0xE2
internal const val MARKER_APP14 = 0xEE

internal val XMP_JPEG_HEADER = "http://ns.adobe.com/xap/1.0/\u0000".encodeToByteArray()
internal val MPF_HEADER = byteArrayOf(0x4D, 0x50, 0x46, 0x00)
internal const val HDRGM_NS = "http://ns.adobe.com/hdr-gain-map/1.0/"

internal fun isJpeg(b: ByteArray) = b.size >= 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

internal fun startsWith(b: ByteArray, from: Int, until: Int, prefix: ByteArray): Boolean =
    until - from >= prefix.size && prefix.indices.all { b[from + it] == prefix[it] }

/**
 * Visits each segment before the image data as (marker, payload start, end) until [visit] returns false.
 * Stops at the first malformed byte, and at a segment that runs past [src] (a header read cut it off).
 */
internal inline fun walkJpegSegments(src: ByteArray, visit: (marker: Int, payload: Int, end: Int) -> Boolean) {
    var i = 2
    while (i + 3 < src.size) {
        if (src[i] != 0xFF.toByte()) return
        var j = i
        while (j < src.size && src[j] == 0xFF.toByte()) j++
        if (j >= src.size) return
        val marker = src[j].toInt() and 0xFF
        i = j + 1
        if (marker == MARKER_SOS || marker == MARKER_EOI) return
        if (marker == 0x01 || marker in 0xD0..0xD7) continue
        if (i + 1 >= src.size) return
        val length = ((src[i].toInt() and 0xFF) shl 8) or (src[i + 1].toInt() and 0xFF)
        if (length < 2) return
        val end = i + length
        if (end > src.size || !visit(marker, i + 2, end)) return
        i = end
    }
}

internal fun jpegSegment(marker: Int, payload: ByteArray): ByteArray {
    val length = payload.size + 2
    require(length <= 0xFFFF) { "segment too long" }
    return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
}

/** An MPF index (CIPA DC-007) whose TIFF header starts at `tiff`: its entry list, 16 bytes each, at [list]. */
internal class MpfEntries(val little: Boolean, val list: Int, val count: Int)

internal fun mpfEntries(b: ByteArray, tiff: Int, end: Int): MpfEntries? {
    if (tiff + 8 > end) return null
    val little = when {
        b[tiff] == 'I'.code.toByte() && b[tiff + 1] == 'I'.code.toByte() -> true
        b[tiff] == 'M'.code.toByte() && b[tiff + 1] == 'M'.code.toByte() -> false
        else -> return null
    }
    val ifd = tiff + readU32(b, tiff + 4, little)
    if (ifd < tiff || ifd + 2 > end) return null
    for (e in 0 until readU16(b, ifd, little)) {
        val entry = ifd + 2 + e * 12
        if (entry + 12 > end) return null
        if (readU16(b, entry, little) != 0xB002) continue
        val count = readU32(b, entry + 4, little) / 16
        val list = tiff + readU32(b, entry + 8, little)
        if (count < 0 || list < tiff || list.toLong() + count.toLong() * 16 > end) return null
        return MpfEntries(little, list, count)
    }
    return null
}

// A span of a source to copy verbatim, or literal bytes to insert. Collected first so the output is allocated exactly
// once rather than grown.
internal sealed interface Piece {
    class Span(val src: ByteArray, val from: Int, val until: Int) : Piece
    class Literal(val data: ByteArray) : Piece

    val size: Int get() = when (this) {
        is Span -> until - from
        is Literal -> data.size
    }
}

internal fun assemble(pieces: List<Piece>): ByteArray {
    val out = ByteArray(pieces.sumOf { it.size })
    var at = 0
    for (p in pieces) {
        when (p) {
            is Piece.Span -> p.src.copyInto(out, at, p.from, p.until)
            is Piece.Literal -> p.data.copyInto(out, at)
        }
        at += p.size
    }
    return out
}
