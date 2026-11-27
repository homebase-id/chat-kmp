package id.homebase.api.image

/**
 * A JPEG's EXIF orientation, 1..8; null when the bytes aren't JPEG or carry none. The web image decoder needs it
 * before it decodes, and browsers expose no EXIF reader.
 */
fun readJpegExifOrientation(bytes: ByteArray): Int? = readJpegExifCamera(bytes)?.orientation

/** A JPEG's camera EXIF, capture time and orientation, in pure Kotlin; null when the bytes aren't a JPEG with EXIF. */
fun readJpegExifCamera(bytes: ByteArray): ImageMetadata? {
    val (tiffStart, end) = jpegExifTiff(bytes) ?: return null
    val tiff = Tiff.at(bytes, tiffStart, end) ?: return null
    val ifd0 = tiff.ifd0() ?: return null
    val exif = ifd0.long(0x8769)?.let(tiff::directory)
    return ImageMetadata(
        capturedAt = parseExifLocalDateTime(exif?.ascii(0x9003)),
        captureUtcOffset = parseExifUtcOffset(exif?.ascii(0x9011)),
        orientation = ifd0.long(0x0112)?.takeIf { it in 1..8 },
        cameraMake = ifd0.ascii(0x010F),
        cameraModel = ifd0.ascii(0x0110),
        fNumber = exif?.rational(0x829D)?.takeIf { it > 0 },
        exposureTimeSeconds = exif?.rational(0x829A)?.takeIf { it > 0 },
        focalLengthMm = exif?.rational(0x920A)?.takeIf { it > 0 },
        iso = exif?.long(0x8827)?.takeIf { it > 0 },
    )
}

// The TIFF block inside a JPEG's EXIF APP1, as (start, end): end clamped to the bytes given.
private fun jpegExifTiff(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 4) return null
    if (u8(bytes, 0) != 0xFF || u8(bytes, 1) != 0xD8) return null

    var offset = 2
    while (offset + 4 <= bytes.size) {
        if (u8(bytes, offset) != 0xFF) return null
        val marker = u8(bytes, offset + 1)
        // Standalone markers carry no payload.
        if (marker == 0x01 || marker in 0xD0..0xD8) {
            offset += 2
            continue
        }
        // Scan data / end of image — EXIF would have appeared before here.
        if (marker == 0xDA || marker == 0xD9) return null

        val length = u16be(bytes, offset + 2)
        if (length < 2) return null
        val segmentStart = offset + 4
        val segmentEnd = offset + 2 + length

        // Clamped: callers pass only the head of the file, and a phone's APP1
        // segment (EXIF + its embedded thumbnail) often runs past it. The
        // orientation entry sits in IFD0 at the front, so parse what's here.
        val available = if (segmentEnd > bytes.size) bytes.size else segmentEnd
        if (marker == 0xE1 && isExifHeader(bytes, segmentStart, available)) {
            return (segmentStart + EXIF_HEADER_SIZE) to available
        }
        if (segmentEnd > bytes.size) return null
        offset = segmentEnd
    }
    return null
}

private class Tiff(val bytes: ByteArray, val start: Int, val end: Int, val littleEndian: Boolean) {
    fun u16(at: Int) = u16(bytes, at, littleEndian)
    fun u32(at: Int) = u32(bytes, at, littleEndian)
    fun u32long(at: Int): Long = u32raw(bytes, at, littleEndian)
    fun s32(at: Int): Long = u32raw(bytes, at, littleEndian).toInt().toLong()

    fun ifd0(): Directory? = directory(u32(start + 4))

    /** The directory [offset] bytes into the TIFF block, or null when it lies outside it. */
    fun directory(offset: Int): Directory? {
        // In Long: a corrupt offset near Int.MAX_VALUE would wrap past the bounds check.
        val at = start.toLong() + offset
        if (offset < 0 || at + 2 > end) return null
        return Directory(this, at.toInt())
    }

    companion object {
        fun at(bytes: ByteArray, start: Int, end: Int): Tiff? {
            if (start + 8 > end) return null
            val littleEndian = when {
                u8(bytes, start) == 0x49 && u8(bytes, start + 1) == 0x49 -> true
                u8(bytes, start) == 0x4D && u8(bytes, start + 1) == 0x4D -> false
                else -> return null
            }
            return Tiff(bytes, start, end, littleEndian).takeIf { it.u16(start + 2) == 42 }
        }
    }
}

private class Directory(val tiff: Tiff, val at: Int) {
    // Each entry: tag, type, count, then the value or its offset; the ones past the bytes given are left out.
    private fun entry(tag: Int): Int? {
        val count = tiff.u16(at)
        for (i in 0 until count) {
            val entry = at + 2 + i * 12
            if (entry + 12 > tiff.end) return null
            if (tiff.u16(entry) == tag) return entry
        }
        return null
    }

    // The first value: inline when they all fit in four bytes, else at the offset written there.
    fun long(tag: Int): Int? {
        val entry = entry(tag) ?: return null
        val type = tiff.u16(entry + 2)
        // 13 is IFD: a pointer some encoders type that way.
        val size = when (type) {
            3 -> 2
            4, 13 -> 4
            else -> return null
        }
        val count = tiff.u32(entry + 4)
        val at = if (count.toLong() * size <= 4) entry + 8L else tiff.start.toLong() + tiff.u32(entry + 8)
        if (at + size > tiff.end) return null
        return if (size == 2) tiff.u16(at.toInt()) else tiff.u32(at.toInt())
    }

    fun rational(tag: Int): Double? {
        val entry = entry(tag) ?: return null
        val type = tiff.u16(entry + 2)
        if (type != 5 && type != 10) return null
        val value = (tiff.start.toLong() + tiff.u32(entry + 8)).takeIf { it + 8 <= tiff.end }?.toInt() ?: return null
        val read = if (type == 10) tiff::s32 else tiff::u32long
        val denominator = read(value + 4)
        return if (denominator == 0L) null else read(value).toDouble() / denominator
    }

    fun ascii(tag: Int): String? {
        val entry = entry(tag) ?: return null
        if (tiff.u16(entry + 2) != 2) return null
        val count = tiff.u32(entry + 4)
        val value = if (count <= 4) entry + 8L else tiff.start.toLong() + tiff.u32(entry + 8)
        if (value + count > tiff.end) return null
        // Up to the first NUL, as the platforms' readers stop.
        return tiff.bytes.copyOfRange(value.toInt(), value.toInt() + count).decodeToString().substringBefore('\u0000').trim().takeIf { it.isNotEmpty() }
    }
}

/** True for the orientations that swap width and height (90°/270° rotations). */
fun jpegOrientationSwapsDimensions(orientation: Int): Boolean = orientation in 5..8

private const val EXIF_HEADER_SIZE = 6

private fun isExifHeader(bytes: ByteArray, start: Int, end: Int): Boolean =
    end - start >= EXIF_HEADER_SIZE &&
        u8(bytes, start) == 0x45 && u8(bytes, start + 1) == 0x78 &&
        u8(bytes, start + 2) == 0x69 && u8(bytes, start + 3) == 0x66 &&
        u8(bytes, start + 4) == 0x00 && u8(bytes, start + 5) == 0x00

private fun u8(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xFF

private fun u16be(bytes: ByteArray, index: Int): Int =
    (u8(bytes, index) shl 8) or u8(bytes, index + 1)

private fun u16(bytes: ByteArray, index: Int, littleEndian: Boolean): Int =
    if (littleEndian) (u8(bytes, index + 1) shl 8) or u8(bytes, index)
    else u16be(bytes, index)

private fun u32raw(bytes: ByteArray, index: Int, littleEndian: Boolean): Long =
    if (littleEndian) {
        (u8(bytes, index + 3).toLong() shl 24) or (u8(bytes, index + 2).toLong() shl 16) or
            (u8(bytes, index + 1).toLong() shl 8) or u8(bytes, index).toLong()
    } else {
        (u8(bytes, index).toLong() shl 24) or (u8(bytes, index + 1).toLong() shl 16) or
            (u8(bytes, index + 2).toLong() shl 8) or u8(bytes, index + 3).toLong()
    }

// Clamped to Int.MAX_VALUE: a corrupt 4-byte offset must not wrap negative and
// slip past the bounds checks above.
private fun u32(bytes: ByteArray, index: Int, littleEndian: Boolean): Int =
    u32raw(bytes, index, littleEndian).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
