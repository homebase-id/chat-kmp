package id.homebase.api.lib.image

import co.touchlab.kermit.Logger

/**
 * Image format detection and validation utilities
 */
object ImageFormatDetector {
    /** What a file can stay as, or a bubble can load directly, on every client. */
    val TYPES_EVERY_CLIENT_CAN_DECODE: Set<String> = setOf("image/jpeg", "image/png", "image/webp")


    /** An image's type from its signature; callers outside this module go through contentTypeFromBytes. */
    internal fun detectFormat(bytes: ByteArray): String {
        if (bytes.size < 4) return "application/octet-stream"

        return when {
            // JPEG: FF D8 FF
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> {
                val marker = when (bytes[3].toInt() and 0xFF) {
                    0xE0 -> "JFIF"
                    0xE1 -> "EXIF"
                    0xDB -> "DQT"
                    else -> {
                        val hex =
                            (bytes[3].toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
                        "Unknown JPEG variant (0x$hex)"
                    }
                }
                Logger.d(tag = "ImageFormatDetector") { "Detected JPEG marker: $marker" }
                "image/jpeg"
            }
            // PNG: 89 50 4E 47
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
            bytes.size >= 6 && bytes.decodeToString(0, 6).let { it == "GIF87a" || it == "GIF89a" } -> "image/gif"
            // WebP: "RIFF" then "WEBP" at 8; WAV and AVI are RIFF too.
            bytes.size >= 12 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes.decodeToString(8, 12) == "WEBP" -> "image/webp"
            isBmp(bytes) -> "image/bmp"
            // AVIF is a HEIF too, often with the generic mif1 brand: its own brands tell them apart.
            isAvif(bytes) -> "image/avif"
            isHeic(bytes) -> "image/heic"
            else -> "application/octet-stream"
        }
    }

    /**
     * Detects if bytes represent a HEIC/HEIF image (ISOBMFF container with ftyp box).
     * Checks for 'ftyp' at offset 4 and known HEIC brand identifiers at offset 8.
     */
    fun isHeic(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false

        // Check for 'ftyp' at offset 4
        val ftyp = bytes[4] == 0x66.toByte() && // f
                   bytes[5] == 0x74.toByte() && // t
                   bytes[6] == 0x79.toByte() && // y
                   bytes[7] == 0x70.toByte()    // p
        if (!ftyp) return false

        // Check brand at offset 8 (4 chars)
        val brand = try {
            bytes.sliceArray(8..11).decodeToString()
        } catch (_: Exception) {
            return false
        }

        return brand in listOf("heic", "heix", "hevc", "hevx", "mif1", "msf1")
    }

    // "BM" alone is common in text and other files: a real BMP also has zeroed reserved bytes and a known DIB header size.
    private fun isBmp(b: ByteArray): Boolean =
        b.size >= 18 && b[0] == 0x42.toByte() && b[1] == 0x4D.toByte() &&
            (6..9).all { b[it] == 0.toByte() } && u32le(b, 14) in BMP_DIB_HEADER_SIZES

    private fun isAvif(b: ByteArray): Boolean = ftypBrands(b)?.any { it in AVIF_BRANDS } == true

    // The major brand and the compatible brands of an ISOBMFF file's ftyp box; null when it has none.
    private fun ftypBrands(b: ByteArray): List<String>? {
        if (b.size < 12 || b.decodeToString(4, 8) != "ftyp") return null
        val end = u32be(b, 0).coerceAtMost(b.size)
        return listOf(b.decodeToString(8, 12)) + (16 until end - 3 step 4).map { b.decodeToString(it, it + 4) }
    }

    private val BMP_DIB_HEADER_SIZES = setOf(12, 40, 52, 56, 64, 108, 124)
    private val AVIF_BRANDS = setOf("avif", "avis")

    // A single-frame GIF counts as still.
    fun isAnimated(bytes: ByteArray): Boolean =
        isAnimatedGif(bytes) || isAnimatedWebp(bytes) || isAnimatedPng(bytes) || isImageSequence(bytes)

    // An APNG announces itself with an acTL chunk before the first image data.
    private fun isAnimatedPng(b: ByteArray): Boolean = pngAnimation(b) == true

    /** False when [header] is a PNG cut off before its acTL or IDAT, so [isAnimated] can't tell from it. */
    fun answersIsAnimated(header: ByteArray): Boolean =
        header.size < 8 || header[0] != 0x89.toByte() || header.decodeToString(1, 4) != "PNG" || pngAnimation(header) != null

    // Null when the chunks run off the end of [b] before acTL or IDAT.
    private fun pngAnimation(b: ByteArray): Boolean? {
        if (b.size < 8 || b[0] != 0x89.toByte() || b.decodeToString(1, 4) != "PNG") return false
        var at = 8
        while (at + 8 <= b.size) {
            val type = b.decodeToString(at + 4, at + 8)
            if (type == "acTL") return true
            if (type == "IDAT") return false
            val length = u32be(b, at)
            if (length < 0) return false
            if (length > b.size - at - 12) return null
            at += 12 + length
        }
        return null
    }

    // Animated HEIC and AVIF are ISO image sequences, declared as the major brand or a compatible one.
    private fun isImageSequence(b: ByteArray): Boolean {
        if (b.size < 12 || b.decodeToString(4, 8) != "ftyp") return false
        if (b.decodeToString(8, 12) in IMAGE_SEQUENCE_BRANDS) return true
        val boxSize = u32be(b, 0)
        val end = if (boxSize in 16..b.size) boxSize else return false
        return (16 until end - 3 step 4).any { b.decodeToString(it, it + 4) in COMPATIBLE_SEQUENCE_BRANDS }
    }

    private val IMAGE_SEQUENCE_BRANDS = setOf("avis", "msf1", "hevc", "hevx")
    private val COMPATIBLE_SEQUENCE_BRANDS = setOf("avis", "msf1")

    fun isAnimatedGif(b: ByteArray): Boolean {
        if (b.size < 13 || b.decodeToString(0, 3) != "GIF") return false
        var i = 13 + gifColorTableSize(b[10])
        var seenFrame = false
        while (i < b.size) {
            when (b[i].toInt() and 0xFF) {
                0x2C -> {
                    if (seenFrame) return true
                    seenFrame = true
                    if (i + 10 > b.size) return false
                    // 10-byte descriptor, optional local color table, 1-byte LZW minimum code size.
                    i = skipGifSubBlocks(b, i + 10 + gifColorTableSize(b[i + 9]) + 1)
                }
                0x21 -> i = skipGifSubBlocks(b, i + 2)
                else -> return false
            }
        }
        return false
    }

    private fun gifColorTableSize(packed: Byte): Int =
        if ((packed.toInt() and 0x80) != 0) 3 * (1 shl ((packed.toInt() and 0x07) + 1)) else 0

    private fun skipGifSubBlocks(b: ByteArray, start: Int): Int {
        var i = start
        while (i < b.size) {
            val length = b[i].toInt() and 0xFF
            i += 1 + length
            if (length == 0) break
        }
        return i
    }

    internal class GifInfo(val width: Int, val height: Int, val frameCount: Int, val durationCs: Int)

    /** Walks a GIF's blocks without decoding pixels; null unless [b] is a GIF with at least one frame. */
    internal fun parseGif(b: ByteArray): GifInfo? {
        if (b.size < 13 || b.decodeToString(0, 3) != "GIF") return null
        var i = 13 + gifColorTableSize(b[10])
        var frames = 0
        var durationCs = 0
        var delayCs = 0
        while (i < b.size) {
            when (b[i].toInt() and 0xFF) {
                0x2C -> {
                    frames++
                    // Browsers and ffmpeg both play a delay under 2 cs at 10 cs.
                    durationCs += if (delayCs < 2) 10 else delayCs
                    delayCs = 0
                    if (i + 10 > b.size) break
                    i = skipGifSubBlocks(b, i + 10 + gifColorTableSize(b[i + 9]) + 1)
                }
                0x21 -> {
                    if (i + 5 < b.size && (b[i + 1].toInt() and 0xFF) == 0xF9) delayCs = u16le(b, i + 4)
                    i = skipGifSubBlocks(b, i + 2)
                }
                else -> break
            }
        }
        return if (frames > 0) GifInfo(u16le(b, 6), u16le(b, 8), frames, durationCs) else null
    }

    private fun u16le(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32be(b: ByteArray, at: Int): Int = (0..3).fold(0) { sum, i -> (sum shl 8) or (b[at + i].toInt() and 0xFF) }
    private fun u32le(b: ByteArray, at: Int): Int = (3 downTo 0).fold(0) { sum, i -> (sum shl 8) or (b[at + i].toInt() and 0xFF) }

    private const val VP8X_ANIMATION_FLAG = 0x02

    private fun isAnimatedWebp(b: ByteArray): Boolean =
        b.size > 20 &&
            b.decodeToString(0, 4) == "RIFF" &&
            b.decodeToString(8, 16) == "WEBPVP8X" &&
            (b[20].toInt() and VP8X_ANIMATION_FLAG) != 0

    /**
     * Validates JPEG format by checking for start and end markers
     */
    fun validateJpeg(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false

        // Check start marker: FF D8 FF
        val hasValidStart = bytes[0] == 0xFF.toByte() &&
                            bytes[1] == 0xD8.toByte() &&
                            bytes[2] == 0xFF.toByte()

        // Check end marker: FF D9 (last two bytes)
        val hasValidEnd = bytes.size >= 2 &&
                          bytes[bytes.size - 2] == 0xFF.toByte() &&
                          bytes[bytes.size - 1] == 0xD9.toByte()

        return hasValidStart && hasValidEnd
    }

    /**
     * Logs detailed information about image byte array
     */
    fun logImageInfo(bytes: ByteArray, tag: String = "ImageFormatDetector") {
        Logger.d(tag = tag) { "Image data: ${bytes.size} bytes" }

        val format = detectFormat(bytes)
        Logger.d(tag = tag) { "Detected format: $format" }

        // If it's a JPEG, validate it
        if (format == "image/jpeg") {
            val isValid = validateJpeg(bytes)
            Logger.d(tag = tag) { "JPEG validation: ${if (isValid) "VALID" else "INVALID (missing end marker)"}" }

            if (!isValid) {
                Logger.w(tag = tag) { "JPEG appears truncated or corrupted - missing FFD9 end marker" }
            }
        }

        // Log first and last bytes
        Logger.d(tag = tag) {
            "First 32 bytes: ${
                bytes.take(32).joinToString(" ") {
                    (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
                }
            }"
        }
        Logger.d(tag = tag) {
            "Last 32 bytes: ${
                bytes.takeLast(32).joinToString(" ") {
                    (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
                }
            }"
        }
    }
}
