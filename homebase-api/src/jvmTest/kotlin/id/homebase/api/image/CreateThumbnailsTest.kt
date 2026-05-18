package id.homebase.api.image

import kotlin.io.encoding.Base64
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        assertNotNull(embeddedThumb)
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
        assertNotNull(embeddedThumb)
        assertTrue(embeddedThumb.pixelWidth <= 20, "Embedded thumb width ${embeddedThumb.pixelWidth} > 20")
        assertTrue(embeddedThumb.pixelHeight <= 20, "Embedded thumb height ${embeddedThumb.pixelHeight} > 20")
        assertEquals("image/webp", embeddedThumb.contentType)
    }

    @Test
    fun standardJpeg_embeddedThumbBase64Decodable() = runTest {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val (_, embeddedThumb, _) = createThumbnails(bytes, "key")
        assertNotNull(embeddedThumb)
        val decoded = Base64.decode(embeddedThumb.content!!)
        assertTrue(decoded.isNotEmpty(), "Decoded embedded thumb should not be empty")
        assertTrue(decoded.size <= tinyThumbSize.maxBytes, "Decoded embedded thumb ${decoded.size} > ${tinyThumbSize.maxBytes} bytes")
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
        assertNotNull(embeddedThumb)
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
        assertNotNull(embeddedThumb)
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
        assertNotNull(embeddedThumb)
        assertEquals("image/webp", embeddedThumb.contentType)
    }

    @Test
    fun gif_embeddedThumbDimensionsAreNaturalSize() = runTest {
        // GIF embedded thumb uses natural image dimensions, NOT tiny thumb dimensions
        val bytes = ImageTestHelper.loadImage("mountain_800.gif")
        val (naturalSize, embeddedThumb, _) = createThumbnails(bytes, "key")
        assertNotNull(embeddedThumb)
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

    // SVG is rasterized through ImageUtils.rasterizeSvg into the same
    // shape as any other image: a tiny embedded webp + the regular
    // 320/640/1080/1600 px webp set. Receivers display the bitmap
    // thumbs natively; no SVG decoder required on the receiver side.

    @Test
    fun svg_withDimensions_producesRasterizedThumbs() = runTest {
        val svgBytes = ImageTestHelper.makeSvgBytes(200, 100)
        val (naturalSize, embeddedThumb, thumbList) = createThumbnails(svgBytes, "key")
        assertEquals(200, naturalSize.pixelWidth)
        assertEquals(100, naturalSize.pixelHeight)
        assertNotNull(embeddedThumb)
        assertEquals("image/webp", embeddedThumb.contentType)
        assertTrue(thumbList.isNotEmpty(), "SVG should produce additional raster thumbs")
        for (thumb in thumbList) {
            assertEquals("image/webp", thumb.contentType)
            ImageTestHelper.assertValidWebp(thumb.thumbnailBytes)
        }
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
    fun svg_embeddedThumbIsWebp() = runTest {
        val svgBytes = ImageTestHelper.makeSvgBytes(200, 100)
        val (_, embeddedThumb, _) = createThumbnails(svgBytes, "key")
        assertNotNull(embeddedThumb)
        assertEquals("image/webp", embeddedThumb.contentType)
        assertTrue(embeddedThumb.pixelWidth <= 20)
        assertTrue(embeddedThumb.pixelHeight <= 20)
        // The embedded thumb content is base64 of a webp; decoded size
        // must respect tinyThumbSize budget so the upload stays under
        // the server's MaxEmbeddedThumbBytes cap.
        val decoded = Base64.decode(embeddedThumb.content!!)
        assertTrue(
            decoded.size <= tinyThumbSize.maxBytes,
            "tiny embedded SVG-rasterized thumb is ${decoded.size} bytes > ${tinyThumbSize.maxBytes}"
        )
    }

    @Test
    fun svg_producesAdditionalRasterThumbs() = runTest {
        val svgBytes = ImageTestHelper.makeSvgBytes(2000, 1000, "<rect width=\"2000\" height=\"1000\"/>")
        val (_, _, thumbList) = createThumbnails(svgBytes, "key")
        assertTrue(thumbList.isNotEmpty(), "Large SVG should rasterize into the standard thumb set")
    }

    /**
     * Regression: services.msgsndr.com returns Google Calendar's SVG
     * logo inline as the link-preview imageUrl. Phase 1 dropped these
     * to keep the outbox unblocked (server rejected the raw SVG with
     * "Thumbnail size of 1634 exceeds 1024"). Phase 3 rasterizes them
     * through ImageUtils.rasterizeSvg so the bubble shows a real image.
     * See /home/seifert/odin/chat-kmp/homebase.log lines 21565-21585.
     */
    @Test
    fun svg_googleCalendarFavicon_producesRealBitmapThumb() = runTest {
        val svgBytes = ImageTestHelper.loadImage("google_calendar_logo.svg")
        val (_, embeddedThumb, thumbList) = createThumbnails(svgBytes, "chat_links")

        assertNotNull(embeddedThumb)
        assertEquals("image/webp", embeddedThumb.contentType)
        val tinyDecoded = Base64.decode(embeddedThumb.content!!)
        ImageTestHelper.assertValidWebp(tinyDecoded)
        assertTrue(
            tinyDecoded.size <= tinyThumbSize.maxBytes,
            "Google Calendar SVG tiny is ${tinyDecoded.size} bytes > ${tinyThumbSize.maxBytes}"
        )

        assertTrue(thumbList.isNotEmpty(), "Should produce additional raster thumbs")
        for (thumb in thumbList) {
            assertEquals("chat_links", thumb.key)
            assertEquals("image/webp", thumb.contentType)
            ImageTestHelper.assertValidWebp(thumb.thumbnailBytes)
        }
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
        assertNotNull(embeddedThumb)
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
            assertNotNull(embeddedThumb, "Failed for $file: embedded thumb missing")
            assertNotNull(embeddedThumb.content, "Failed for $file: embedded thumb content")
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
        assertNotNull(embeddedThumb)
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

            assertNotNull(embeddedThumb, "createThumbnails($file): embedded thumb should not be null")
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
