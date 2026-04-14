package id.homebase.api.image

import id.homebase.api.lib.image.ImageFormatDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageFormatDetectorTest {

    // =========================================================
    // detectFormat
    // =========================================================

    @Test
    fun detectFormat_jpeg() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        assertEquals("image/jpeg", ImageFormatDetector.detectFormat(bytes))
    }

    @Test
    fun detectFormat_png() {
        val bytes = ImageTestHelper.loadImage("dice.png")
        assertEquals("image/png", ImageFormatDetector.detectFormat(bytes))
    }

    @Test
    fun detectFormat_gif() {
        val bytes = ImageTestHelper.loadImage("mountain_800.gif")
        assertEquals("image/gif", ImageFormatDetector.detectFormat(bytes))
    }

    @Test
    fun detectFormat_webp() {
        val bytes = ImageTestHelper.loadImage("1_webp_a.webp")
        assertEquals("image/webp", ImageFormatDetector.detectFormat(bytes))
    }

    @Test
    fun detectFormat_webpLossless() {
        val bytes = ImageTestHelper.loadImage("1_webp_ll.webp")
        assertEquals("image/webp", ImageFormatDetector.detectFormat(bytes))
    }

    @Test
    fun detectFormat_tooShort_returnsOctetStream() {
        assertEquals("application/octet-stream", ImageFormatDetector.detectFormat(ByteArray(2)))
    }

    @Test
    fun detectFormat_empty_returnsOctetStream() {
        assertEquals("application/octet-stream", ImageFormatDetector.detectFormat(ByteArray(0)))
    }

    @Test
    fun detectFormat_randomBytes_returnsOctetStream() {
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        assertEquals("application/octet-stream", ImageFormatDetector.detectFormat(bytes))
    }

    @Test
    fun detectFormat_multipleJpegVariants() {
        val jpegFiles = listOf(
            "canon_5d_srgb.jpg",
            "red-leaf.jpg",
            "gamma_test.jpg",
            "MarsRGB_tagged.jpg"
        )
        for (file in jpegFiles) {
            val bytes = ImageTestHelper.loadImage(file)
            assertEquals("image/jpeg", ImageFormatDetector.detectFormat(bytes), "Failed for $file")
        }
    }

    @Test
    fun detectFormat_multiplePngVariants() {
        val pngFiles = listOf(
            "gradients.png",
            "rings2.png",
            "shirt_transparent.png",
            "pngsuite/basn6a08.png"
        )
        for (file in pngFiles) {
            val bytes = ImageTestHelper.loadImage(file)
            assertEquals("image/png", ImageFormatDetector.detectFormat(bytes), "Failed for $file")
        }
    }

    @Test
    fun detectFormat_corruptJpeg_stillDetectedAsJpeg() {
        val bytes = ImageTestHelper.loadImage("corrupt.jpg")
        assertEquals("image/jpeg", ImageFormatDetector.detectFormat(bytes))
    }

    @Test
    fun detectFormat_syntheticBmpHeader() {
        val bytes = byteArrayOf(0x42, 0x4D, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertEquals("image/bmp", ImageFormatDetector.detectFormat(bytes))
    }

    // =========================================================
    // isHeic
    // =========================================================

    @Test
    fun isHeic_validBrands() {
        val brands = listOf("heic", "heix", "hevc", "hevx", "mif1", "msf1")
        for (brand in brands) {
            val header = ImageTestHelper.makeHeicHeader(brand)
            assertTrue(ImageFormatDetector.isHeic(header), "Expected true for brand '$brand'")
        }
    }

    @Test
    fun isHeic_unknownBrand_returnsFalse() {
        val header = ImageTestHelper.makeHeicHeader("mp41")
        assertFalse(ImageFormatDetector.isHeic(header))
    }

    @Test
    fun isHeic_noFtyp_returnsFalse() {
        val bytes = ByteArray(12) { (it + 1).toByte() }
        assertFalse(ImageFormatDetector.isHeic(bytes))
    }

    @Test
    fun isHeic_tooShort_returnsFalse() {
        assertFalse(ImageFormatDetector.isHeic(ByteArray(8)))
    }

    @Test
    fun isHeic_jpegBytes_returnsFalse() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        assertFalse(ImageFormatDetector.isHeic(bytes))
    }

    @Test
    fun isHeic_pngBytes_returnsFalse() {
        val bytes = ImageTestHelper.loadImage("dice.png")
        assertFalse(ImageFormatDetector.isHeic(bytes))
    }

    // =========================================================
    // validateJpeg
    // =========================================================

    @Test
    fun validateJpeg_validJpeg_returnsTrue() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        assertTrue(ImageFormatDetector.validateJpeg(bytes))
    }

    @Test
    fun validateJpeg_corruptJpeg_missingEndMarker() {
        val bytes = ImageTestHelper.loadImage("corrupt.jpg")
        // corrupt.jpg is truncated, likely missing FF D9 end marker
        val hasEndMarker = bytes.size >= 2 &&
            bytes[bytes.size - 2] == 0xFF.toByte() &&
            bytes[bytes.size - 1] == 0xD9.toByte()
        assertEquals(hasEndMarker, ImageFormatDetector.validateJpeg(bytes))
    }

    @Test
    fun validateJpeg_multipleValidJpegs() {
        val files = listOf("canon_5d_srgb.jpg", "red-leaf.jpg", "waterhouse.jpg")
        for (file in files) {
            val bytes = ImageTestHelper.loadImage(file)
            assertTrue(ImageFormatDetector.validateJpeg(bytes), "Expected valid JPEG for $file")
        }
    }

    @Test
    fun validateJpeg_pngBytes_returnsFalse() {
        val bytes = ImageTestHelper.loadImage("dice.png")
        assertFalse(ImageFormatDetector.validateJpeg(bytes))
    }

    @Test
    fun validateJpeg_tooShort_returnsFalse() {
        assertFalse(ImageFormatDetector.validateJpeg(ByteArray(3)))
    }

    @Test
    fun validateJpeg_empty_returnsFalse() {
        assertFalse(ImageFormatDetector.validateJpeg(ByteArray(0)))
    }

    @Test
    fun validateJpeg_syntheticMinimalValid() {
        // Minimal valid JPEG: FF D8 FF E0 ... FF D9
        val bytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x00,
            0xFF.toByte(), 0xD9.toByte()
        )
        assertTrue(ImageFormatDetector.validateJpeg(bytes))
    }
}
