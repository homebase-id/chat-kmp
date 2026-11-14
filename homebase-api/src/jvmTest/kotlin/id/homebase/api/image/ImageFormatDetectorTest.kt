package id.homebase.api.image

import id.homebase.api.lib.image.ImageFormatDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageFormatDetectorTest {

    @Test
    fun isAnimated_pngWithAnOverflowingChunkLengthIsNotAnimated() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x7F, -1, -1, -1) +
            "tEXt".encodeToByteArray() + ByteArray(8)
        assertFalse(ImageFormatDetector.isAnimated(png))
    }

    private fun ftyp(major: String, vararg compatible: String): ByteArray {
        val size = 16 + compatible.size * 4
        return byteArrayOf(0, 0, 0, size.toByte()) + "ftyp".encodeToByteArray() + major.encodeToByteArray() +
            ByteArray(4) + compatible.joinToString("").encodeToByteArray() + ByteArray(8)
    }

    @Test
    fun isAnimated_seesASequenceDeclaredOnlyAsACompatibleBrand() {
        assertTrue(ImageFormatDetector.isAnimated(ftyp("mif1", "miaf", "msf1")))
        assertTrue(ImageFormatDetector.isAnimated(ftyp("avif", "avis")))
        assertFalse(ImageFormatDetector.isAnimated(ftyp("heic", "mif1", "heic")))
    }

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
    fun detectFormat_aBmpNeedsItsWholeHeader() {
        val header = "BM".encodeToByteArray() + byteArrayOf(0x46, 0, 0, 0) + ByteArray(4) + byteArrayOf(0x36, 0, 0, 0) + byteArrayOf(40, 0, 0, 0)
        assertEquals("image/bmp", ImageFormatDetector.detectFormat(header))
        assertEquals("application/octet-stream", ImageFormatDetector.detectFormat("BMW 320i service log, 2019".encodeToByteArray()))
        assertEquals("application/octet-stream", ImageFormatDetector.detectFormat(byteArrayOf(0x42, 0x4D, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun detectFormat_aGifNeedsItsVersion() {
        assertEquals("application/octet-stream", ImageFormatDetector.detectFormat("GIFT LIST: socks".encodeToByteArray()))
    }

    @Test
    fun detectFormat_anAvifIsNotAHeic() {
        assertEquals("image/avif", ImageFormatDetector.detectFormat(ftyp("avif", "mif1", "miaf")))
        assertEquals("image/avif", ImageFormatDetector.detectFormat(ftyp("mif1", "avif", "miaf")), "the generic HEIF brand, AVIF among the compatible ones")
        assertEquals("image/avif", ImageFormatDetector.detectFormat(ftyp("avis", "msf1")))
        assertEquals("image/heic", ImageFormatDetector.detectFormat(ftyp("heic", "mif1", "heic")))
        assertEquals("image/heic", ImageFormatDetector.detectFormat(ftyp("mif1", "heic")))
        assertTrue(ImageFormatDetector.isHeic(ftyp("mif1", "avif")), "still a HEIF for decoding and stripping")
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

    // =========================================================
    // isAnimated
    // =========================================================

    private fun b64(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)

    @Test
    fun isAnimated_multiFrameGif() {
        assertTrue(ImageFormatDetector.isAnimated(b64(ANIMATED_GIF)))
    }

    @Test
    fun isAnimated_singleFrameGif_isStill() {
        assertFalse(ImageFormatDetector.isAnimated(ImageTestHelper.loadImage("mountain_800.gif")))
        // Cut off just before the second frame's descriptor.
        assertFalse(ImageFormatDetector.isAnimated(b64(ANIMATED_GIF).copyOf(78)))
    }

    @Test
    fun isAnimated_webpAnimationFlag() {
        assertTrue(ImageFormatDetector.isAnimated(b64(ANIMATED_WEBP)))
        // VP8X with only the alpha flag, then plain VP8L and lossy VP8.
        for (still in listOf("1_webp_a.webp", "1_webp_ll.webp", "lossy_mountain.webp")) {
            assertFalse(ImageFormatDetector.isAnimated(ImageTestHelper.loadImage(still)), still)
        }
    }

    @Test
    fun isAnimated_otherFormatsAndGarbage() {
        assertFalse(ImageFormatDetector.isAnimated(ImageTestHelper.loadImage("roof_test_800x600.jpg")))
        assertFalse(ImageFormatDetector.isAnimated(ImageTestHelper.loadImage("dice.png")))
        assertFalse(ImageFormatDetector.isAnimated(ByteArray(0)))
        assertFalse(ImageFormatDetector.isAnimated("GIF89a".encodeToByteArray() + ByteArray(7) { -1 }))
    }

    private companion object {
        // 2x1, two frames, NETSCAPE + graphic-control extensions, second frame has a local color table.
        const val ANIMATED_GIF =
            "R0lGODlhAgABAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAAAgABAAAIBQABAAgIACH5" +
                "BAAKAAAALAAAAAACAAEAgQAA/wAAAAAAAAAAAAgFAAEACAgAOw=="

        // 2x1, two frames.
        const val ANIMATED_WEBP =
            "UklGRoQAAABXRUJQVlA4WAoAAAACAAAAAQAAAAAAQU5JTQYAAAAAAAAAAABBTk1GKAAAAAAAAAAAAAEAAAAAAGQAAAJWUDhM" +
                "DwAAAC8BAAAABxD9j/4HIqL/AQBBTk1GKAAAAAAAAAAAAAEAAAAAAGQAAABWUDhMDwAAAC8BAAAABxDR//4HIqL/AQA="
    }

    @Test
    fun aPngHeaderCutBeforeItsAnimationChunkCantAnswer() {
        fun chunk(type: String, data: ByteArray) =
            byteArrayOf(0, (data.size shr 16).toByte(), (data.size shr 8).toByte(), data.size.toByte()) + type.encodeToByteArray() + data + ByteArray(4)
        val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
        val apng = signature + chunk("IHDR", ByteArray(13)) + chunk("iTXt", ByteArray(70_000)) + chunk("acTL", ByteArray(8)) + chunk("IDAT", ByteArray(4))
        val header = apng.copyOf(64 * 1024)

        assertTrue(ImageFormatDetector.isAnimated(apng))
        assertFalse(ImageFormatDetector.answersIsAnimated(header))
        assertTrue(ImageFormatDetector.answersIsAnimated(apng))
        assertTrue(ImageFormatDetector.answersIsAnimated(signature + chunk("IHDR", ByteArray(13)) + chunk("IDAT", ByteArray(4))))
        assertTrue(ImageFormatDetector.answersIsAnimated(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
    }
}
