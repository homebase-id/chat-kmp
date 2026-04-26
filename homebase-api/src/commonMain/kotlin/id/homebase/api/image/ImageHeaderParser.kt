package id.homebase.api.image

/**
 * Header-only image-size reader. Parses the first ~64 KB of an image file to extract its
 * pixel dimensions without decoding the entire payload.
 *
 * Supports JPEG (SOF0–SOF15), PNG (IHDR), GIF (LSD), and WebP (VP8 / VP8L / VP8X).
 * Returns null for formats we can't parse incrementally (e.g. HEIC) — callers should
 * fall back to [ImageUtils.getNaturalSize] in that case.
 */
object ImageHeaderParser {

    fun parse(header: ByteArray): ImageSize? {
        if (header.size < 12) return null
        return when {
            isJpeg(header) -> parseJpeg(header)
            isPng(header) -> parsePng(header)
            isGif(header) -> parseGif(header)
            isWebp(header) -> parseWebp(header)
            else -> null
        }
    }

    private fun isJpeg(b: ByteArray) = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    private fun isPng(b: ByteArray) =
        b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() &&
            b[3] == 0x47.toByte() && b[4] == 0x0D.toByte() && b[5] == 0x0A.toByte() &&
            b[6] == 0x1A.toByte() && b[7] == 0x0A.toByte()

    private fun isGif(b: ByteArray) =
        b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() &&
            b[3] == '8'.code.toByte() && (b[4] == '7'.code.toByte() || b[4] == '9'.code.toByte()) &&
            b[5] == 'a'.code.toByte()

    private fun isWebp(b: ByteArray) =
        b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() &&
            b[3] == 'F'.code.toByte() && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() &&
            b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()

    // JPEG: walk segments looking for SOF0..SOF15, skipping DHT (C4), JPG (C8), DAC (CC).
    private fun parseJpeg(b: ByteArray): ImageSize? {
        var i = 2 // past SOI
        while (i + 8 < b.size) {
            // Skip fill bytes (0xFF padding before next marker)
            while (i < b.size && b[i] == 0xFF.toByte()) i++
            if (i >= b.size) return null
            val marker = b[i].toInt() and 0xFF
            i++
            // SOFx (excluding DHT 0xC4, JPG 0xC8, DAC 0xCC)
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                if (i + 6 >= b.size) return null
                // segment length (2 bytes, big-endian, includes itself)
                // skip length (2) + sample precision (1)
                val height = ((b[i + 3].toInt() and 0xFF) shl 8) or (b[i + 4].toInt() and 0xFF)
                val width = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                if (width <= 0 || height <= 0) return null
                return ImageSize(width, height)
            }
            // Other marker — skip its segment
            if (marker == 0xD8 || marker == 0xD9) return null // SOI/EOI without SOF
            if (i + 1 >= b.size) return null
            val segLen = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            if (segLen < 2) return null
            i += segLen
        }
        return null
    }

    // PNG: IHDR chunk starts immediately after the 8-byte signature. Width @16, height @20,
    // both big-endian 32-bit.
    private fun parsePng(b: ByteArray): ImageSize? {
        if (b.size < 24) return null
        val width = readBigEndianInt(b, 16)
        val height = readBigEndianInt(b, 20)
        return if (width > 0 && height > 0) ImageSize(width, height) else null
    }

    // GIF: width @6, height @8, both little-endian 16-bit.
    private fun parseGif(b: ByteArray): ImageSize? {
        if (b.size < 10) return null
        val width = (b[6].toInt() and 0xFF) or ((b[7].toInt() and 0xFF) shl 8)
        val height = (b[8].toInt() and 0xFF) or ((b[9].toInt() and 0xFF) shl 8)
        return if (width > 0 && height > 0) ImageSize(width, height) else null
    }

    // WebP: chunk at offset 12 is "VP8 ", "VP8L", or "VP8X". Extract width/height
    // depending on chunk type.
    private fun parseWebp(b: ByteArray): ImageSize? {
        if (b.size < 30) return null
        val chunk = b.copyOfRange(12, 16).decodeToString()
        return when (chunk) {
            "VP8 " -> {
                // Lossy: width @26, height @28 (14-bit each, mask out top 2 bits)
                if (b.size < 30) return null
                val w = ((b[26].toInt() and 0xFF) or ((b[27].toInt() and 0x3F) shl 8))
                val h = ((b[28].toInt() and 0xFF) or ((b[29].toInt() and 0x3F) shl 8))
                if (w > 0 && h > 0) ImageSize(w, h) else null
            }
            "VP8L" -> {
                // Lossless: 14-bit width-1 and height-1 packed @21..24
                if (b.size < 25) return null
                val b0 = b[21].toInt() and 0xFF
                val b1 = b[22].toInt() and 0xFF
                val b2 = b[23].toInt() and 0xFF
                val b3 = b[24].toInt() and 0xFF
                val w = ((b1 and 0x3F) shl 8 or b0) + 1
                val h = ((b3 and 0x0F) shl 10 or (b2 shl 2) or ((b1 and 0xC0) shr 6)) + 1
                if (w > 0 && h > 0) ImageSize(w, h) else null
            }
            "VP8X" -> {
                // Extended: canvas width-1 @24 (24-bit LE), height-1 @27 (24-bit LE)
                if (b.size < 30) return null
                val w = ((b[24].toInt() and 0xFF) or
                    ((b[25].toInt() and 0xFF) shl 8) or
                    ((b[26].toInt() and 0xFF) shl 16)) + 1
                val h = ((b[27].toInt() and 0xFF) or
                    ((b[28].toInt() and 0xFF) shl 8) or
                    ((b[29].toInt() and 0xFF) shl 16)) + 1
                if (w > 0 && h > 0) ImageSize(w, h) else null
            }
            else -> null
        }
    }

    private fun readBigEndianInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
}
