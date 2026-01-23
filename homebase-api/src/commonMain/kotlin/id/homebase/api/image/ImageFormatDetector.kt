package id.homebase.homebasekmppoc.lib.image

import co.touchlab.kermit.Logger

/**
 * Image format detection and validation utilities
 */
object ImageFormatDetector {

    /**
     * Detects the image format based on magic bytes
     */
    fun detectFormat(bytes: ByteArray): String {
        if (bytes.size < 4) return "application/octet-stream"

        return when {
            // JPEG: FF D8 FF
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> {
                val marker = when (bytes[3].toInt() and 0xFF) {
                    0xE0 -> "JFIF"
                    0xE1 -> "EXIF"
                    0xDB -> "DQT"
                    else -> {
                        val hex = (bytes[3].toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
                        "Unknown JPEG variant (0x$hex)"
                    }
                }
                Logger.d("ImageFormatDetector") { "Detected JPEG marker: $marker" }
                "image/jpeg"
            }
            // PNG: 89 50 4E 47
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
            // GIF: 47 49 46
            bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() -> "image/gif"
            // WebP: 52 49 46 46 (RIFF)
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() -> "image/webp"
            // BMP: 42 4D
            bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte() -> "image/bmp"
            else -> "application/octet-stream"
        }
    }

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
        Logger.d(tag) { "Image data: ${bytes.size} bytes" }

        val format = detectFormat(bytes)
        Logger.d(tag) { "Detected format: $format" }

        // If it's a JPEG, validate it
        if (format == "image/jpeg") {
            val isValid = validateJpeg(bytes)
            Logger.d(tag) { "JPEG validation: ${if (isValid) "VALID" else "INVALID (missing end marker)"}" }

            if (!isValid) {
                Logger.w(tag) { "JPEG appears truncated or corrupted - missing FFD9 end marker" }
            }
        }

        // Log first and last bytes
        Logger.d(tag) {
            "First 32 bytes: ${bytes.take(32).joinToString(" ") {
                (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
            }}"
        }
        Logger.d(tag) {
            "Last 32 bytes: ${bytes.takeLast(32).joinToString(" ") {
                (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
            }}"
        }
    }
}
