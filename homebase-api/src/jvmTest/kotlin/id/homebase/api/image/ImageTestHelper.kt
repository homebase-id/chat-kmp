package id.homebase.api.image

import kotlin.test.assertTrue

/**
 * Shared test utilities for image tests.
 */
object ImageTestHelper {

    /** Load a test image from jvmTest/resources/test_images/ */
    fun loadImage(path: String): ByteArray =
        this::class.java.getResourceAsStream("/test_images/$path")?.readBytes()
            ?: error("Test image not found: $path")

    /** Assert bytes start with JPEG magic: FF D8 FF */
    fun assertValidJpeg(bytes: ByteArray) {
        assertTrue(bytes.size >= 3, "JPEG too short: ${bytes.size} bytes")
        assertTrue(
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte(),
            "Not a valid JPEG header: ${formatHex(bytes.take(4))}"
        )
    }

    /** Assert bytes start with PNG magic: 89 50 4E 47 */
    fun assertValidPng(bytes: ByteArray) {
        assertTrue(bytes.size >= 4, "PNG too short: ${bytes.size} bytes")
        assertTrue(
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte(),
            "Not a valid PNG header: ${formatHex(bytes.take(4))}"
        )
    }

    /** Assert bytes start with RIFF (WebP container) */
    fun assertValidWebp(bytes: ByteArray) {
        assertTrue(bytes.size >= 4, "WebP too short: ${bytes.size} bytes")
        assertTrue(
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte(),
            "Not a valid WebP (RIFF) header: ${formatHex(bytes.take(4))}"
        )
    }

    /** Build a synthetic SVG byte array with optional width/height attributes */
    fun makeSvgBytes(width: Int?, height: Int?, content: String = ""): ByteArray {
        val attrs = buildString {
            append("xmlns=\"http://www.w3.org/2000/svg\"")
            if (width != null) append(" width=\"$width\"")
            if (height != null) append(" height=\"$height\"")
        }
        return "<svg $attrs>$content</svg>".toByteArray()
    }

    /** Build a synthetic 12-byte HEIC ftyp box with the given brand at offset 8 */
    fun makeHeicHeader(brand: String): ByteArray {
        require(brand.length == 4) { "Brand must be exactly 4 characters" }
        val bytes = ByteArray(12)
        // Box size (12) in big-endian at offset 0
        bytes[0] = 0; bytes[1] = 0; bytes[2] = 0; bytes[3] = 12
        // 'ftyp' at offset 4
        bytes[4] = 0x66; bytes[5] = 0x74; bytes[6] = 0x79; bytes[7] = 0x70
        // Brand at offset 8
        val brandBytes = brand.toByteArray(Charsets.US_ASCII)
        bytes[8] = brandBytes[0]; bytes[9] = brandBytes[1]
        bytes[10] = brandBytes[2]; bytes[11] = brandBytes[3]
        return bytes
    }

    private fun formatHex(bytes: List<Byte>): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
