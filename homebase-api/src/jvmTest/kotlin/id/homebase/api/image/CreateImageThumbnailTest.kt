package id.homebase.api.image

import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CreateImageThumbnailTest {

    // =========================================================
    // Standard thumbnails
    // =========================================================

    @Test
    fun createThumbnail_320px_landscapeJpeg() = runTest {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val instr = baseThumbSizes[0] // 320px, 26KB
        val result = createImageThumbnail(bytes, "test-key", instr)
        assertEquals(320, result.pixelWidth)
        assertEquals(240, result.pixelHeight)
        assertEquals("test-key", result.key)
        assertEquals("image/webp", result.contentType)
        assertTrue(result.thumbnailBytes.size <= 26 * 1024, "Thumb size ${result.thumbnailBytes.size} exceeds 26KB")
    }

    @Test
    fun createThumbnail_640px_landscapeJpeg() = runTest {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val instr = baseThumbSizes[1] // 640px, 102KB
        val result = createImageThumbnail(bytes, "key", instr)
        assertTrue(max(result.pixelWidth, result.pixelHeight) <= 640)
        assertTrue(result.thumbnailBytes.size <= 102 * 1024)
    }

    @Test
    fun createThumbnail_1080px_highResJpeg() = runTest {
        val bytes = ImageTestHelper.loadImage("5760_x_4320.jpg")
        val instr = baseThumbSizes[2] // 1080px, 291KB
        val result = createImageThumbnail(bytes, "key", instr)
        assertEquals(1080, max(result.pixelWidth, result.pixelHeight))
        assertTrue(result.thumbnailBytes.size <= 291 * 1024)
    }

    @Test
    fun createThumbnail_1600px_highResJpeg() = runTest {
        val bytes = ImageTestHelper.loadImage("5760_x_4320.jpg")
        val instr = baseThumbSizes[3] // 1600px, 640KB
        val result = createImageThumbnail(bytes, "key", instr)
        assertEquals(1600, max(result.pixelWidth, result.pixelHeight))
        assertTrue(result.thumbnailBytes.size <= 640 * 1024)
    }

    @Test
    fun createThumbnail_fromPng() = runTest {
        val bytes = ImageTestHelper.loadImage("dice.png")
        val instr = baseThumbSizes[0]
        val result = createImageThumbnail(bytes, "key", instr)
        assertTrue(max(result.pixelWidth, result.pixelHeight) <= 320)
        assertEquals("image/webp", result.contentType)
        assertTrue(result.thumbnailBytes.isNotEmpty())
    }

    @Test
    fun createThumbnail_fromWebp() = runTest {
        val bytes = ImageTestHelper.loadImage("1_webp_a.webp")
        val instr = baseThumbSizes[0]
        val result = createImageThumbnail(bytes, "key", instr)
        assertTrue(result.thumbnailBytes.isNotEmpty())
    }

    @Test
    fun createThumbnail_fromLosslessWebp() = runTest {
        val bytes = ImageTestHelper.loadImage("1_webp_ll.webp")
        val instr = baseThumbSizes[0]
        val result = createImageThumbnail(bytes, "key", instr)
        assertTrue(result.thumbnailBytes.isNotEmpty())
    }

    @Test
    fun createThumbnail_fromTransparentPng() = runTest {
        val bytes = ImageTestHelper.loadImage("shirt_transparent.png")
        val instr = baseThumbSizes[0]
        val result = createImageThumbnail(bytes, "key", instr)
        assertTrue(result.thumbnailBytes.isNotEmpty())
        assertTrue(max(result.pixelWidth, result.pixelHeight) <= 320)
    }

    // =========================================================
    // Tiny thumbnails
    // =========================================================

    @Test
    fun createTinyThumb_landscapeJpeg() = runTest {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = createImageThumbnail(bytes, "key", tinyThumbSize, isTinyThumb = true)
        assertTrue(max(result.pixelWidth, result.pixelHeight) <= 20, "Tiny thumb max dim ${max(result.pixelWidth, result.pixelHeight)} > 20")
        assertTrue(result.thumbnailBytes.size <= 768, "Tiny thumb size ${result.thumbnailBytes.size} > 768")
        assertEquals("image/webp", result.contentType)
    }

    @Test
    fun createTinyThumb_highResJpeg() = runTest {
        val bytes = ImageTestHelper.loadImage("5760_x_4320.jpg")
        val result = createImageThumbnail(bytes, "key", tinyThumbSize, isTinyThumb = true)
        assertTrue(max(result.pixelWidth, result.pixelHeight) <= 20)
        assertTrue(result.thumbnailBytes.size <= 768)
    }

    @Test
    fun createTinyThumb_png() = runTest {
        val bytes = ImageTestHelper.loadImage("dice.png")
        val result = createImageThumbnail(bytes, "key", tinyThumbSize, isTinyThumb = true)
        assertTrue(max(result.pixelWidth, result.pixelHeight) <= 20)
        assertTrue(result.thumbnailBytes.size <= 768)
        assertEquals("image/webp", result.contentType)
    }

    @Test
    fun createTinyThumb_webp() = runTest {
        val bytes = ImageTestHelper.loadImage("1_webp_a.webp")
        val result = createImageThumbnail(bytes, "key", tinyThumbSize, isTinyThumb = true)
        assertTrue(max(result.pixelWidth, result.pixelHeight) <= 20)
        assertTrue(result.thumbnailBytes.size <= 768)
    }

    @Test
    fun createTinyThumb_gif() = runTest {
        val bytes = ImageTestHelper.loadImage("mountain_800.gif")
        val result = createImageThumbnail(bytes, "key", tinyThumbSize, isTinyThumb = true)
        assertTrue(max(result.pixelWidth, result.pixelHeight) <= 20)
        assertTrue(result.thumbnailBytes.size <= 768)
        assertEquals("image/webp", result.contentType)
    }

    @Test
    fun createTinyThumb_tinySourceImage() = runTest {
        val bytes = ImageTestHelper.loadImage("pngsuite/basn6a08.png")
        val result = createImageThumbnail(bytes, "key", tinyThumbSize, isTinyThumb = true)
        assertTrue(max(result.pixelWidth, result.pixelHeight) <= 20)
        assertTrue(result.thumbnailBytes.size <= 768)
    }

    @Test
    fun createTinyThumb_forcedWebpFormat() = runTest {
        // Even with JPEG instruction type, tiny thumb should be WebP
        val instrWithJpeg = ThumbnailInstruction(
            quality = 76, maxPixelDimension = 20, maxBytes = 768, type = ImageFormat.JPEG
        )
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = createImageThumbnail(bytes, "key", instrWithJpeg, isTinyThumb = true)
        assertEquals("image/webp", result.contentType)
    }

    @Test
    fun createTinyThumb_largeDenseImage_wrenches() = runTest {
        val bytes = ImageTestHelper.loadImage("wrenches.jpg")
        val result = createImageThumbnail(bytes, "key", tinyThumbSize, isTinyThumb = true)
        assertTrue(max(result.pixelWidth, result.pixelHeight) <= 20)
        assertTrue(result.thumbnailBytes.size <= 768, "Tiny thumb ${result.thumbnailBytes.size} > 768 for wrenches")
    }

    @Test
    fun createTinyThumb_largeDenseImage_waterhouse() = runTest {
        val bytes = ImageTestHelper.loadImage("waterhouse.jpg")
        val result = createImageThumbnail(bytes, "key", tinyThumbSize, isTinyThumb = true)
        assertTrue(max(result.pixelWidth, result.pixelHeight) <= 20)
        assertTrue(result.thumbnailBytes.size <= 768, "Tiny thumb ${result.thumbnailBytes.size} > 768 for waterhouse")
    }

    // =========================================================
    // Exact-size optimization
    // =========================================================

    @Test
    fun createThumbnail_exactSizeOptimization_bypassesReencoding() = runTest {
        // basn6a08.png is 32x32, 184 bytes. Instruction: maxPixelDimension=32, maxBytes=1024.
        // Since naturalMax == maxDim and size <= maxBytes and not tiny, should return original bytes.
        val bytes = ImageTestHelper.loadImage("pngsuite/basn6a08.png")
        val instr = ThumbnailInstruction(quality = 84, maxPixelDimension = 32, maxBytes = 1024)
        val result = createImageThumbnail(bytes, "key", instr, isTinyThumb = false)
        assertTrue(result.thumbnailBytes.contentEquals(bytes), "Should return original bytes as-is")
    }

    @Test
    fun createThumbnail_exactSizeTooLarge_reencodes() = runTest {
        // Same image but with maxBytes smaller than actual size (184 bytes)
        val bytes = ImageTestHelper.loadImage("pngsuite/basn6a08.png")
        val instr = ThumbnailInstruction(quality = 84, maxPixelDimension = 32, maxBytes = 100)
        val result = createImageThumbnail(bytes, "key", instr, isTinyThumb = false)
        assertFalse(result.thumbnailBytes.contentEquals(bytes), "Should re-encode, not return original")
    }

    // =========================================================
    // Quality reduction loop
    // =========================================================

    @Test
    fun createThumbnail_qualityLoop_convergesToMaxBytes() = runTest {
        val bytes = ImageTestHelper.loadImage("waterhouse.jpg")
        val instr = ThumbnailInstruction(quality = 84, maxPixelDimension = 320, maxBytes = 5 * 1024)
        val result = createImageThumbnail(bytes, "key", instr)
        // Quality loop should converge — result should be reasonably close to maxBytes or quality == 1
        assertTrue(result.thumbnailBytes.isNotEmpty())
        assertTrue(result.thumbnailBytes.size <= 10 * 1024, "Should be reasonably small after quality loop")
    }

    @Test
    fun createThumbnail_unrealisticallyTinyMaxBytes_doesNotHang() = runTest {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val instr = ThumbnailInstruction(quality = 84, maxPixelDimension = 800, maxBytes = 100)
        // Should exit after safety limit (20 iterations), not hang
        val result = createImageThumbnail(bytes, "key", instr)
        assertTrue(result.thumbnailBytes.isNotEmpty())
    }

    // =========================================================
    // Metadata
    // =========================================================

    @Test
    fun createThumbnail_payloadKeyPreserved() = runTest {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = createImageThumbnail(bytes, "my-payload-key-123", baseThumbSizes[0])
        assertEquals("my-payload-key-123", result.key)
    }

    @Test
    fun createThumbnail_jpegFormat_contentType() = runTest {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val instr = ThumbnailInstruction(quality = 84, maxPixelDimension = 320, maxBytes = 26 * 1024, type = ImageFormat.JPEG)
        val result = createImageThumbnail(bytes, "key", instr, isTinyThumb = false)
        assertEquals("image/jpeg", result.contentType)
    }

    @Test
    fun createThumbnail_pngFormat_contentType() = runTest {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val instr = ThumbnailInstruction(quality = 84, maxPixelDimension = 320, maxBytes = 50 * 1024, type = ImageFormat.PNG)
        val result = createImageThumbnail(bytes, "key", instr, isTinyThumb = false)
        assertEquals("image/png", result.contentType)
    }

    @Test
    fun createThumbnail_webpFormat_contentType() = runTest {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = createImageThumbnail(bytes, "key", baseThumbSizes[0], isTinyThumb = false)
        assertEquals("image/webp", result.contentType)
    }

    @Test
    fun createThumbnail_tinyThumbOverridesFormatToWebp() = runTest {
        val instrJpeg = ThumbnailInstruction(quality = 76, maxPixelDimension = 20, maxBytes = 768, type = ImageFormat.JPEG)
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = createImageThumbnail(bytes, "key", instrJpeg, isTinyThumb = true)
        assertEquals("image/webp", result.contentType)
    }

    // =========================================================
    // Tiny thumb on ALL test images
    // =========================================================

    companion object {
        /** Every non-corrupt image in the test library (excludes corrupt.jpg which may not decode) */
        val allTestImages = listOf(
            "1_webp_a.sm.png",
            "1_webp_a.webp",
            "1_webp_ll.webp",
            "31182064-e1c54784-a8f0-11e7-8bb3-833bba872975.png",
            "5760_x_4320.jpg",
            "5_webp_ll.webp",
            "MarsRGB_tagged.jpg",
            "MarsRGB_v4_sYCC_8bit.jpg",
            "canon_5d_srgb.jpg",
            "cmyk_logo.jpg",
            "color_profile_error.jpg",
            "cropissue.jpg",
            "dct_overflow_patterns/checkerboard_8x8.png",
            "dct_overflow_patterns/left_black_right_white.png",
            "dct_overflow_patterns/left_white_right_black.png",
            "dct_overflow_patterns/single_8x8_half.png",
            "dct_overflow_patterns/top_black_bottom_white.png",
            "dice.png",
            "frymire-srgb.png",
            "frymire.png",
            "gamma_test.jpg",
            "gradients.png",
            "little_gradient_whitespace.jpg",
            "lossy_mountain.webp",
            "mountain_800.gif",
            "orientation/Landscape_1.jpg",
            "orientation/Landscape_2.jpg",
            "orientation/Landscape_3.jpg",
            "orientation/Landscape_4.jpg",
            "orientation/Landscape_5.jpg",
            "orientation/Landscape_6.jpg",
            "orientation/Landscape_7.jpg",
            "orientation/Landscape_8.jpg",
            "orientation/Portrait_1.jpg",
            "orientation/Portrait_2.jpg",
            "orientation/Portrait_3.jpg",
            "orientation/Portrait_4.jpg",
            "orientation/Portrait_5.jpg",
            "orientation/Portrait_6.jpg",
            "orientation/Portrait_7.jpg",
            "orientation/Portrait_8.jpg",
            "png_turns_empty_2.png",
            "pngsuite/basn6a08.png",
            "red-leaf.jpg",
            "red-night.png",
            "rings2.png",
            "roof_test_800x600.jpg",
            "shirt_transparent.png",
            "waterhouse.jpg",
            "whitespace-issue.png",
            "wrenches.jpg"
        )
    }

    @Test
    fun createTinyThumb_allTestImages() = runTest {
        for (file in allTestImages) {
            val bytes = ImageTestHelper.loadImage(file)
            val result = createImageThumbnail(bytes, "key", tinyThumbSize, isTinyThumb = true)
            assertTrue(
                result.thumbnailBytes.isNotEmpty(),
                "Tiny thumb for $file produced empty bytes"
            )
            assertTrue(
                result.thumbnailBytes.size <= 768,
                "Tiny thumb for $file is ${result.thumbnailBytes.size} bytes (max 768)"
            )
            val maxDim = maxOf(result.pixelWidth, result.pixelHeight)
            assertTrue(
                maxDim <= 20,
                "Tiny thumb for $file has max dim $maxDim (max 20)"
            )
            assertEquals(
                "image/webp", result.contentType,
                "Tiny thumb for $file should be WebP"
            )
        }
    }
}
