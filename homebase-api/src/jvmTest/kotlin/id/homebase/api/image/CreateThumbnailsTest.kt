package id.homebase.api.image

import kotlin.io.encoding.Base64
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CreateThumbnailsTest {

    // =========================================================
    // Standard JPEG
    // =========================================================

    @Test
    fun standardJpeg_returnsTriplet() = runTest {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val (naturalSize, embeddedThumb, thumbList) = createThumbnails(bytes, "payload-key")
        assertEquals(800, naturalSize.pixelWidth)
        assertEquals(600, naturalSize.pixelHeight)
        assertNotNull(embeddedThumb.contentType)
        assertEquals("image/webp", embeddedThumb.contentType)
        assertTrue(embeddedThumb.pixelWidth > 0)
        val content = embeddedThumb.content
        assertNotNull(content)
        assertTrue(content.isNotEmpty())
        assertTrue(thumbList.isNotEmpty())
    }

    @Test
    fun standardJpeg_embeddedThumbIsTiny() = runTest {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val (_, embeddedThumb, _) = createThumbnails(bytes, "key")
        assertTrue(embeddedThumb.pixelWidth <= 20, "Embedded thumb width ${embeddedThumb.pixelWidth} > 20")
        assertTrue(embeddedThumb.pixelHeight <= 20, "Embedded thumb height ${embeddedThumb.pixelHeight} > 20")
        assertEquals("image/webp", embeddedThumb.contentType)
    }

    @Test
    fun standardJpeg_embeddedThumbBase64Decodable() = runTest {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val (_, embeddedThumb, _) = createThumbnails(bytes, "key")
        val decoded = Base64.decode(embeddedThumb.content!!)
        assertTrue(decoded.isNotEmpty(), "Decoded embedded thumb should not be empty")
        assertTrue(decoded.size <= 768, "Decoded embedded thumb ${decoded.size} > 768 bytes")
    }

    @Test
    fun standardJpeg_thumbnailListMatchesRevisedSizes() = runTest {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val (naturalSize, _, thumbList) = createThumbnails(bytes, "key")
        val expected = getRevisedThumbs(naturalSize, baseThumbSizes)
        assertEquals(expected.size, thumbList.size, "Thumbnail count mismatch")
    }

    @Test
    fun standardJpeg_allThumbsHaveCorrectKey() = runTest {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val (_, _, thumbList) = createThumbnails(bytes, "my-key")
        for (thumb in thumbList) {
            assertEquals("my-key", thumb.key, "Thumb key mismatch")
        }
    }

    @Test
    fun standardJpeg_thumbsSortedBySize() = runTest {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val (_, _, thumbList) = createThumbnails(bytes, "key")
        for (i in 0 until thumbList.size - 1) {
            val current = max(thumbList[i].pixelWidth, thumbList[i].pixelHeight)
            val next = max(thumbList[i + 1].pixelWidth, thumbList[i + 1].pixelHeight)
            assertTrue(current <= next, "Thumbs not sorted: $current > $next")
        }
    }

    @Test
    fun standardJpeg_allThumbsBytesNonEmpty() = runTest {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val (_, _, thumbList) = createThumbnails(bytes, "key")
        for (thumb in thumbList) {
            assertTrue(thumb.thumbnailBytes.isNotEmpty(), "Thumb at ${thumb.pixelWidth}x${thumb.pixelHeight} has empty bytes")
        }
    }

    @Test
    fun standardJpeg_tinyThumbNotInList() = runTest {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val (_, _, thumbList) = createThumbnails(bytes, "key")
        for (thumb in thumbList) {
            val maxDim = max(thumb.pixelWidth, thumb.pixelHeight)
            assertTrue(maxDim > 20, "Found tiny thumb (${thumb.pixelWidth}x${thumb.pixelHeight}) in additional list")
        }
    }

    // =========================================================
    // High-res image
    // =========================================================

    @Test
    fun highResJpeg_producesMultipleSizes() = runTest {
        val bytes = ImageTestHelper.loadImage("5760_x_4320.jpg")
        val (_, _, thumbList) = createThumbnails(bytes, "key")
        assertTrue(thumbList.size >= 4, "Expected at least 4 thumbnails, got ${thumbList.size}")
    }

    @Test
    fun highResJpeg_naturalSizeCorrect() = runTest {
        val bytes = ImageTestHelper.loadImage("5760_x_4320.jpg")
        val (naturalSize, _, _) = createThumbnails(bytes, "key")
        assertEquals(5760, naturalSize.pixelWidth)
        assertEquals(4320, naturalSize.pixelHeight)
    }

    @Test
    fun highResJpeg_eachThumbReasonablySized() = runTest {
        val bytes = ImageTestHelper.loadImage("5760_x_4320.jpg")
        val (_, _, thumbList) = createThumbnails(bytes, "key")
        for (thumb in thumbList) {
            assertTrue(thumb.thumbnailBytes.size < 1024 * 1024, "Thumb at ${thumb.pixelWidth}px is ${thumb.thumbnailBytes.size} bytes")
        }
    }

    // =========================================================
    // Small image
    // =========================================================

    @Test
    fun tinyImage32x32_minimalThumbs() = runTest {
        val bytes = ImageTestHelper.loadImage("pngsuite/basn6a08.png")
        val (naturalSize, _, thumbList) = createThumbnails(bytes, "key")
        assertEquals(32, naturalSize.pixelWidth)
        assertEquals(32, naturalSize.pixelHeight)
        // All base thumbs > 32, so only synthetic at 32
        assertEquals(1, thumbList.size, "Expected 1 synthetic thumb, got ${thumbList.size}")
    }

    @Test
    fun tinyImage32x32_stillHasEmbeddedThumb() = runTest {
        val bytes = ImageTestHelper.loadImage("pngsuite/basn6a08.png")
        val (_, embeddedThumb, _) = createThumbnails(bytes, "key")
        assertNotNull(embeddedThumb.content)
        assertEquals("image/webp", embeddedThumb.contentType)
    }

    // =========================================================
    // PNG
    // =========================================================

    @Test
    fun png_producesWebpThumbs() = runTest {
        val bytes = ImageTestHelper.loadImage("dice.png")
        val (_, _, thumbList) = createThumbnails(bytes, "key")
        for (thumb in thumbList) {
            assertEquals("image/webp", thumb.contentType, "Expected WebP for PNG input")
        }
    }

    @Test
    fun transparentPng_handledCorrectly() = runTest {
        val bytes = ImageTestHelper.loadImage("shirt_transparent.png")
        val (_, _, thumbList) = createThumbnails(bytes, "key")
        assertTrue(thumbList.isNotEmpty())
        for (thumb in thumbList) {
            assertTrue(thumb.thumbnailBytes.isNotEmpty())
        }
    }

    // =========================================================
    // WebP
    // =========================================================

    @Test
    fun webpLossy_succeeds() = runTest {
        val bytes = ImageTestHelper.loadImage("lossy_mountain.webp")
        val (naturalSize, embeddedThumb, _) = createThumbnails(bytes, "key")
        assertTrue(naturalSize.pixelWidth > 0)
        assertEquals("image/webp", embeddedThumb.contentType)
    }

    @Test
    fun webpLossless_succeeds() = runTest {
        val bytes = ImageTestHelper.loadImage("1_webp_ll.webp")
        val (naturalSize, _, _) = createThumbnails(bytes, "key")
        assertTrue(naturalSize.pixelWidth > 0)
    }

    // =========================================================
    // GIF handling
    // =========================================================

    @Test
    fun gif_onlyTinyThumb_emptyAdditionalList() = runTest {
        val bytes = ImageTestHelper.loadImage("mountain_800.gif")
        val (_, _, thumbList) = createThumbnails(bytes, "key")
        assertTrue(thumbList.isEmpty(), "GIF should produce empty additional thumbnail list, got ${thumbList.size}")
    }

    @Test
    fun gif_embeddedThumbIsWebp() = runTest {
        val bytes = ImageTestHelper.loadImage("mountain_800.gif")
        val (_, embeddedThumb, _) = createThumbnails(bytes, "key")
        assertEquals("image/webp", embeddedThumb.contentType)
    }

    @Test
    fun gif_embeddedThumbDimensionsAreNaturalSize() = runTest {
        // GIF embedded thumb uses natural image dimensions, NOT tiny thumb dimensions
        val bytes = ImageTestHelper.loadImage("mountain_800.gif")
        val (naturalSize, embeddedThumb, _) = createThumbnails(bytes, "key")
        assertEquals(naturalSize.pixelWidth, embeddedThumb.pixelWidth)
        assertEquals(naturalSize.pixelHeight, embeddedThumb.pixelHeight)
    }

    @Test
    fun gif_naturalSizeDetected() = runTest {
        val bytes = ImageTestHelper.loadImage("mountain_800.gif")
        val (naturalSize, _, _) = createThumbnails(bytes, "key")
        assertTrue(naturalSize.pixelWidth > 0)
        assertTrue(naturalSize.pixelHeight > 0)
    }

    // =========================================================
    // SVG handling (synthetic data)
    // =========================================================

    @Test
    fun svg_withDimensions_returnsSvgAsIs() = runTest {
        val svgBytes = ImageTestHelper.makeSvgBytes(200, 100)
        val (naturalSize, _, thumbList) = createThumbnails(svgBytes, "key")
        assertEquals(200, naturalSize.pixelWidth)
        assertEquals(100, naturalSize.pixelHeight)
        assertEquals(1, thumbList.size)
        assertEquals("image/svg+xml", thumbList[0].contentType)
        assertTrue(thumbList[0].thumbnailBytes.contentEquals(svgBytes), "SVG should be returned as-is")
    }

    @Test
    fun svg_withoutDimensions_defaultSize() = runTest {
        val svgBytes = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\"></svg>".toByteArray()
        val (naturalSize, _, _) = createThumbnails(svgBytes, "key")
        assertEquals(320, naturalSize.pixelWidth, "Default width for SVG without dimensions")
        assertEquals(320, naturalSize.pixelHeight, "Default height for SVG without dimensions")
    }

    @Test
    fun svg_withXmlDeclaration() = runTest {
        val svgBytes = "<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"50\" height=\"50\"></svg>".toByteArray()
        val (naturalSize, _, _) = createThumbnails(svgBytes, "key")
        assertEquals(50, naturalSize.pixelWidth)
        assertEquals(50, naturalSize.pixelHeight)
    }

    @Test
    fun svg_embeddedThumbIsSvg() = runTest {
        val svgBytes = ImageTestHelper.makeSvgBytes(200, 100)
        val (_, embeddedThumb, _) = createThumbnails(svgBytes, "key")
        assertEquals("image/svg+xml", embeddedThumb.contentType)
    }

    @Test
    fun svg_embeddedThumbBase64DecodesToOriginal() = runTest {
        val svgBytes = ImageTestHelper.makeSvgBytes(200, 100)
        val (_, embeddedThumb, _) = createThumbnails(svgBytes, "key")
        val decoded = Base64.decode(embeddedThumb.content!!)
        assertTrue(decoded.contentEquals(svgBytes), "Embedded SVG base64 should decode to original bytes")
    }

    @Test
    fun svg_noResizeApplied() = runTest {
        val svgBytes = ImageTestHelper.makeSvgBytes(2000, 1000, "<rect width=\"2000\" height=\"1000\"/>")
        val (_, _, thumbList) = createThumbnails(svgBytes, "key")
        assertEquals(1, thumbList.size)
        assertTrue(thumbList[0].thumbnailBytes.contentEquals(svgBytes))
    }

    // =========================================================
    // Custom thumbSizes
    // =========================================================

    @Test
    fun customThumbSizes_overridesDefault() = runTest {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val custom = listOf(
            ThumbnailInstruction(quality = 80, maxPixelDimension = 100, maxBytes = 10 * 1024),
            ThumbnailInstruction(quality = 80, maxPixelDimension = 200, maxBytes = 50 * 1024)
        )
        val (_, _, thumbList) = createThumbnails(bytes, "key", custom)
        // With custom sizes and source 800x600 (max=800):
        // Both 100 and 200 < 800 and below 90% range (720). Kept.
        // keptThumbs.size (2) < thumbs.size (2) is FALSE if nothing filtered, so no synthetic.
        // But both are < sourceMax (800), so all kept and no synthetic needed.
        assertTrue(thumbList.isNotEmpty())
    }

    @Test
    fun customSingleSize() = runTest {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val custom = listOf(
            ThumbnailInstruction(quality = 80, maxPixelDimension = 400, maxBytes = 100 * 1024)
        )
        val (_, _, thumbList) = createThumbnails(bytes, "key", custom)
        assertTrue(thumbList.isNotEmpty())
    }

    @Test
    fun nullThumbSizes_usesDefault() = runTest {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val (_, _, thumbListNull) = createThumbnails(bytes, "key", null)
        val (_, _, thumbListDefault) = createThumbnails(bytes, "key", baseThumbSizes)
        assertEquals(thumbListNull.size, thumbListDefault.size, "null and explicit baseThumbSizes should produce same count")
    }

    // =========================================================
    // Edge cases & orientation
    // =========================================================

    @Test
    fun cmykJpeg_doesNotCrash() = runTest {
        val bytes = ImageTestHelper.loadImage("cmyk_logo.jpg")
        val (naturalSize, embeddedThumb, thumbList) = createThumbnails(bytes, "key")
        assertTrue(naturalSize.pixelWidth > 0)
        assertNotNull(embeddedThumb.content)
        assertTrue(thumbList.isNotEmpty())
    }

    @Test
    fun colorProfileErrorJpeg_doesNotCrash() = runTest {
        val bytes = ImageTestHelper.loadImage("color_profile_error.jpg")
        val (naturalSize, _, _) = createThumbnails(bytes, "key")
        assertTrue(naturalSize.pixelWidth > 0)
    }

    @Test
    fun allOrientations_landscapeConsistentDimensions() = runTest {
        var firstThumbDims: List<Pair<Int, Int>>? = null
        for (i in 1..8) {
            val bytes = ImageTestHelper.loadImage("orientation/Landscape_$i.jpg")
            val (_, _, thumbList) = createThumbnails(bytes, "key")
            val dims = mutableListOf<Pair<Int, Int>>()
            for (t in thumbList) { dims.add(t.pixelWidth to t.pixelHeight) }
            if (firstThumbDims == null) {
                firstThumbDims = dims
            } else {
                assertEquals(firstThumbDims, dims, "Landscape_$i produced different thumb dimensions")
            }
        }
    }

    @Test
    fun allOrientations_portraitConsistentDimensions() = runTest {
        var firstThumbDims: List<Pair<Int, Int>>? = null
        for (i in 1..8) {
            val bytes = ImageTestHelper.loadImage("orientation/Portrait_$i.jpg")
            val (_, _, thumbList) = createThumbnails(bytes, "key")
            val dims = mutableListOf<Pair<Int, Int>>()
            for (t in thumbList) { dims.add(t.pixelWidth to t.pixelHeight) }
            if (firstThumbDims == null) {
                firstThumbDims = dims
            } else {
                assertEquals(firstThumbDims, dims, "Portrait_$i produced different thumb dimensions")
            }
        }
    }

    @Test
    fun multipleFormats_allSucceed() = runTest {
        val files = listOf(
            "roof_test_800x600.jpg",
            "dice.png",
            "1_webp_a.webp",
            "mountain_800.gif"
        )
        for (file in files) {
            val bytes = ImageTestHelper.loadImage(file)
            val (naturalSize, embeddedThumb, _) = createThumbnails(bytes, "key")
            assertTrue(naturalSize.pixelWidth > 0, "Failed for $file: width")
            assertNotNull(embeddedThumb.content, "Failed for $file: embedded thumb")
        }
    }

    // =========================================================
    // Round-trip
    // =========================================================

    @Test
    fun outputThumbsAreDecodable() = runTest {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val (_, _, thumbList) = createThumbnails(bytes, "key")
        for (thumb in thumbList) {
            val decoded = ImageUtils.getNaturalSize(thumb.thumbnailBytes)
            assertEquals(thumb.pixelWidth, decoded.pixelWidth, "Decoded width mismatch for ${thumb.pixelWidth}px thumb")
            assertEquals(thumb.pixelHeight, decoded.pixelHeight, "Decoded height mismatch for ${thumb.pixelHeight}px thumb")
        }
    }

    @Test
    fun tinyThumbFromEmbedded_isDecodable() = runTest {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val (_, embeddedThumb, _) = createThumbnails(bytes, "key")
        val decoded = Base64.decode(embeddedThumb.content!!)
        val size = ImageUtils.getNaturalSize(decoded)
        assertTrue(size.pixelWidth > 0, "Decoded tiny thumb should have positive width")
        assertTrue(size.pixelHeight > 0, "Decoded tiny thumb should have positive height")
    }

    // =========================================================
    // Full pipeline on ALL test images
    // =========================================================

    @Test
    fun createThumbnails_allTestImages() = runTest {
        for (file in CreateImageThumbnailTest.allTestImages) {
            val bytes = ImageTestHelper.loadImage(file)
            val (naturalSize, embeddedThumb, _) = createThumbnails(bytes, "key")

            assertTrue(
                naturalSize.pixelWidth > 0,
                "createThumbnails($file): naturalSize width should be positive"
            )
            assertTrue(
                naturalSize.pixelHeight > 0,
                "createThumbnails($file): naturalSize height should be positive"
            )

            val content = embeddedThumb.content
            assertNotNull(content, "createThumbnails($file): embedded thumb content should not be null")
            assertTrue(content.isNotEmpty(), "createThumbnails($file): embedded thumb content should not be empty")

            // Decode the embedded tiny thumb and verify it's valid
            val tinyBytes = Base64.decode(content)
            assertTrue(
                tinyBytes.isNotEmpty(),
                "createThumbnails($file): decoded tiny thumb bytes should not be empty"
            )
        }
    }
}
