package id.homebase.api.image

import co.touchlab.kermit.Logger

/**
 * A re-encoded WebP or JPEG [finalImage] carrying [source]'s EXIF, for an app that keeps metadata (THUMBNAILS.md,
 * "Metadata"): Orientation 1, because the pixels are already upright; the pixel-size tags set to the new
 * size; the camera's embedded EXIF thumbnail left out. Neither Android's nor Skia's encoder carries
 * metadata over. [finalImage] comes back unchanged when the source has none.
 *
 * XMP isn't carried: on phones it describes the source file's structure (a Motion Photo's video, an
 * Ultra HDR gain map, its orientation), which a re-encode makes false.
 */
fun withSourceMetadata(finalImage: ByteArray, source: ByteArray, width: Int, height: Int): ByteArray {
    val tiff = ImageMetadataScrubber.readBlocks(source)?.exif ?: return finalImage
    val exif = ExifTiff.normalized(tiff, width, height)
    if (exif == null) {
        Logger.w(tag = "ImageMetadataCarry") { "source EXIF couldn't be read; the final image goes without" }
        return finalImage
    }
    return if (isJpeg(finalImage)) JpegMetadata.embed(finalImage, exif) else WebpMetadata.embed(finalImage, exif, width, height)
}

private fun isJpeg(b: ByteArray) = b.size > 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

internal object JpegMetadata {
    private val EXIF_HEADER = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0)

    /**
     * [jpeg] with an APP1 Exif segment holding [tiff] right after SOI. In front of everything, so an Ultra HDR
     * file's MPF offsets, which count from the MPF segment itself, still find the gain map.
     */
    fun embed(jpeg: ByteArray, tiff: ByteArray): ByteArray {
        val length = 2 + EXIF_HEADER.size + tiff.size
        if (length > 0xFFFF) return jpeg
        val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + EXIF_HEADER + tiff
        return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
    }
}

internal object ExifTiff {
    private const val ORIENTATION = 0x0112
    private const val EXIF_IFD = 0x8769
    private const val GPS_IFD = 0x8825
    private const val INTEROP_IFD = 0xA005
    private const val PIXEL_X = 0xA002
    private const val PIXEL_Y = 0xA003
    private const val SUB_IFDS = 0x014A

    /** A copy of [tiff] made right for a re-encoded image, or null when it isn't a TIFF block this reads. */
    fun normalized(tiff: ByteArray, width: Int, height: Int): ByteArray? {
        if (tiff.size < 8) return null
        val little = when {
            tiff[0] == 'I'.code.toByte() && tiff[1] == 'I'.code.toByte() -> true
            tiff[0] == 'M'.code.toByte() && tiff[1] == 'M'.code.toByte() -> false
            else -> return null
        }
        val t = Tiff(tiff.copyOf(), little)
        if (t.u16(2) != 42) return null
        val ifd0 = t.u32(4)
        val count0 = t.entryCount(ifd0) ?: return null
        var dataEnd = 0
        var unfollowed = false
        val ifds = ArrayDeque(listOf(ifd0))
        val seen = mutableSetOf<Int>()
        while (ifds.isNotEmpty()) {
            val ifd = ifds.removeFirst()
            if (!seen.add(ifd)) continue
            // A pointer that leads nowhere readable loses that directory, not the rest.
            val count = t.entryCount(ifd) ?: continue
            dataEnd = maxOf(dataEnd, ifd + 2 + count * 12 + 4)
            for (e in 0 until count) {
                val entry = ifd + 2 + e * 12
                when (t.u16(entry)) {
                    ORIENTATION -> t.put16(entry + 8, 1)
                    PIXEL_X -> t.putNumber(entry, width)
                    PIXEL_Y -> t.putNumber(entry, height)
                    EXIF_IFD, GPS_IFD, INTEROP_IFD -> ifds += t.u32(entry + 8)
                    SUB_IFDS -> unfollowed = true
                }
                dataEnd = maxOf(dataEnd, t.valueEnd(entry))
            }
        }
        // IFD1 is the camera's embedded thumbnail: unlink it, and cut it off when nothing this kept lies past it.
        val nextPointer = ifd0 + 2 + count0 * 12
        val ifd1 = t.u32(nextPointer)
        t.put32(nextPointer, 0)
        return if (!unfollowed && ifd1 > 0 && ifd1 >= dataEnd && ifd1 <= t.b.size) t.b.copyOf(ifd1) else t.b
    }

    private class Tiff(val b: ByteArray, val little: Boolean) {
        fun u16(at: Int): Int = readU16(b, at, little)

        fun u32(at: Int): Int = readU32(b, at, little)

        fun put16(at: Int, v: Int) {
            val lo = (v and 0xFF).toByte()
            val hi = ((v shr 8) and 0xFF).toByte()
            if (little) { b[at] = lo; b[at + 1] = hi } else { b[at] = hi; b[at + 1] = lo }
        }

        fun put32(at: Int, v: Int) {
            if (little) { put16(at, v and 0xFFFF); put16(at + 2, v ushr 16) } else { put16(at, v ushr 16); put16(at + 2, v and 0xFFFF) }
        }

        fun entryCount(ifd: Int): Int? {
            if (ifd < 8 || ifd + 2 > b.size) return null
            val count = u16(ifd)
            return count.takeIf { ifd + 2 + it * 12 + 4 <= b.size }
        }

        // A SHORT or LONG holding one number, in place.
        fun putNumber(entry: Int, value: Int) {
            when (u16(entry + 2)) {
                3 -> put16(entry + 8, value)
                4 -> put32(entry + 8, value)
            }
        }

        /** Where the entry's value ends when it lives in the block outside the entry; the entry's own end otherwise. */
        fun valueEnd(entry: Int): Int {
            val size = when (u16(entry + 2)) {
                1, 2, 6, 7 -> 1
                3, 8 -> 2
                4, 9, 11 -> 4
                5, 10, 12 -> 8
                else -> return entry + 12
            }
            val bytes = size.toLong() * (u32(entry + 4).toLong() and 0xFFFFFFFFL)
            if (bytes <= 4) return entry + 12
            val offset = u32(entry + 8).toLong() and 0xFFFFFFFFL
            val end = offset + bytes
            return if (end <= b.size) end.toInt() else entry + 12
        }
    }
}

internal object WebpMetadata {
    private const val FLAG_EXIF = 0x08
    private const val FLAG_ALPHA = 0x10
    private const val CHUNKS_START = 12
    private const val VP8X_CHUNK_SIZE = 18

    /** [webp] with an [exif] chunk and the extended header that announces it, in one allocation. */
    fun embed(webp: ByteArray, exif: ByteArray, width: Int, height: Int): ByteArray {
        if (webp.size <= CHUNKS_START + 8 || fourCc(webp, 0) != "RIFF" || fourCc(webp, 8) != "WEBP") return webp
        val flags = FLAG_EXIF
        val extended = fourCc(webp, CHUNKS_START) == "VP8X"
        val exifChunk = webpChunk("EXIF", exif)
        val bodySize = webp.size - CHUNKS_START
        val out = ByteArray(CHUNKS_START + (if (extended) 0 else VP8X_CHUNK_SIZE) + bodySize + exifChunk.size)
        webp.copyInto(out, 0, 0, CHUNKS_START)
        writeLe32(out, 4, out.size - 8)
        var at = CHUNKS_START
        if (extended) {
            webp.copyInto(out, at, CHUNKS_START, webp.size)
            out[at + 8] = ((out[at + 8].toInt() and 0xFF) or flags).toByte()
        } else {
            val header = ByteArray(10)
            header[0] = (flags or (if (fourCc(webp, CHUNKS_START) == "VP8L" && hasVp8lAlpha(webp)) FLAG_ALPHA else 0)).toByte()
            put24(header, 4, width - 1)
            put24(header, 7, height - 1)
            webpChunk("VP8X", header).copyInto(out, at)
            at += VP8X_CHUNK_SIZE
            webp.copyInto(out, at, CHUNKS_START, webp.size)
        }
        exifChunk.copyInto(out, at + bodySize)
        return out
    }

    // The VP8L header's alpha_is_used bit, after the 0x2f signature and two 14-bit sizes.
    private fun hasVp8lAlpha(webp: ByteArray): Boolean {
        val at = CHUNKS_START + 8 + 1
        if (at + 4 > webp.size) return false
        return (webp[at + 3].toInt() and 0x10) != 0
    }

    private fun put24(b: ByteArray, at: Int, v: Int) {
        for (i in 0..2) b[at + i] = (v ushr (8 * i)).toByte()
    }
}
