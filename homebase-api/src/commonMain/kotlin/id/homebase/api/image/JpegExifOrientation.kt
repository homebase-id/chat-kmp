package id.homebase.api.image

/**
 * Pure-Kotlin EXIF orientation reader (tag 0x0112) for JPEG bytes.
 *
 * Exists because [readImageMetadata] has no implementation on web (browsers
 * expose no EXIF reader) and the web image decoder needs the orientation before
 * it decodes. Returns null when the bytes aren't JPEG or carry no orientation.
 */
fun readJpegExifOrientation(bytes: ByteArray): Int? {
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
            return parseTiffOrientation(bytes, segmentStart + EXIF_HEADER_SIZE, available)
        }
        if (segmentEnd > bytes.size) return null
        offset = segmentEnd
    }
    return null
}

/** True for the orientations that swap width and height (90°/270° rotations). */
fun jpegOrientationSwapsDimensions(orientation: Int): Boolean = orientation in 5..8

private const val EXIF_HEADER_SIZE = 6

private fun isExifHeader(bytes: ByteArray, start: Int, end: Int): Boolean =
    end - start >= EXIF_HEADER_SIZE &&
        u8(bytes, start) == 0x45 && u8(bytes, start + 1) == 0x78 &&
        u8(bytes, start + 2) == 0x69 && u8(bytes, start + 3) == 0x66 &&
        u8(bytes, start + 4) == 0x00 && u8(bytes, start + 5) == 0x00

private fun parseTiffOrientation(bytes: ByteArray, tiffStart: Int, end: Int): Int? {
    if (tiffStart + 8 > end) return null
    val littleEndian = when {
        u8(bytes, tiffStart) == 0x49 && u8(bytes, tiffStart + 1) == 0x49 -> true
        u8(bytes, tiffStart) == 0x4D && u8(bytes, tiffStart + 1) == 0x4D -> false
        else -> return null
    }
    if (u16(bytes, tiffStart + 2, littleEndian) != 42) return null

    val ifdOffset = u32(bytes, tiffStart + 4, littleEndian)
    val directory = tiffStart + ifdOffset
    if (directory < tiffStart || directory + 2 > end) return null

    val entryCount = u16(bytes, directory, littleEndian)
    for (i in 0 until entryCount) {
        val entry = directory + 2 + i * 12
        if (entry + 12 > end) return null
        if (u16(bytes, entry, littleEndian) != 0x0112) continue
        val value = when (u16(bytes, entry + 2, littleEndian)) {
            3 -> u16(bytes, entry + 8, littleEndian)
            4 -> u32(bytes, entry + 8, littleEndian)
            else -> return null
        }
        return value.takeIf { it in 1..8 }
    }
    return null
}

private fun u8(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xFF

private fun u16be(bytes: ByteArray, index: Int): Int =
    (u8(bytes, index) shl 8) or u8(bytes, index + 1)

private fun u16(bytes: ByteArray, index: Int, littleEndian: Boolean): Int =
    if (littleEndian) (u8(bytes, index + 1) shl 8) or u8(bytes, index)
    else u16be(bytes, index)

// Clamped to Int.MAX_VALUE: a corrupt 4-byte offset must not wrap negative and
// slip past the bounds checks above.
private fun u32(bytes: ByteArray, index: Int, littleEndian: Boolean): Int {
    val value = if (littleEndian) {
        (u8(bytes, index + 3).toLong() shl 24) or (u8(bytes, index + 2).toLong() shl 16) or
            (u8(bytes, index + 1).toLong() shl 8) or u8(bytes, index).toLong()
    } else {
        (u8(bytes, index).toLong() shl 24) or (u8(bytes, index + 1).toLong() shl 16) or
            (u8(bytes, index + 2).toLong() shl 8) or u8(bytes, index + 3).toLong()
    }
    return value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
