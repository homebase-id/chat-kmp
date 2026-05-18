package id.homebase.api.image

import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ImageUtilsTest {

    // =========================================================
    // getNaturalSize
    // =========================================================

    @Test
    fun getNaturalSize_jpeg800x600() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val size = ImageUtils.getNaturalSize(bytes)
        assertEquals(800, size.pixelWidth)
        assertEquals(600, size.pixelHeight)
    }

    @Test
    fun getNaturalSize_highResJpeg() {
        val bytes = ImageTestHelper.loadImage("5760_x_4320.jpg")
        val size = ImageUtils.getNaturalSize(bytes)
        assertEquals(5760, size.pixelWidth)
        assertEquals(4320, size.pixelHeight)
    }

    @Test
    fun getNaturalSize_png() {
        val bytes = ImageTestHelper.loadImage("dice.png")
        val size = ImageUtils.getNaturalSize(bytes)
        assertTrue(size.pixelWidth > 0, "PNG width should be positive")
        assertTrue(size.pixelHeight > 0, "PNG height should be positive")
    }

    @Test
    fun getNaturalSize_tinyPng32x32() {
        val bytes = ImageTestHelper.loadImage("pngsuite/basn6a08.png")
        val size = ImageUtils.getNaturalSize(bytes)
        assertEquals(32, size.pixelWidth)
        assertEquals(32, size.pixelHeight)
    }

    @Test
    fun getNaturalSize_webpLossy() {
        val bytes = ImageTestHelper.loadImage("1_webp_a.webp")
        val size = ImageUtils.getNaturalSize(bytes)
        assertTrue(size.pixelWidth > 0)
        assertTrue(size.pixelHeight > 0)
    }

    @Test
    fun getNaturalSize_webpLossless() {
        val bytes = ImageTestHelper.loadImage("1_webp_ll.webp")
        val size = ImageUtils.getNaturalSize(bytes)
        assertTrue(size.pixelWidth > 0)
        assertTrue(size.pixelHeight > 0)
    }

    @Test
    fun getNaturalSize_gif() {
        val bytes = ImageTestHelper.loadImage("mountain_800.gif")
        val size = ImageUtils.getNaturalSize(bytes)
        assertTrue(size.pixelWidth > 0)
        assertTrue(size.pixelHeight > 0)
    }

    @Test
    fun getNaturalSize_transparentPng() {
        val bytes = ImageTestHelper.loadImage("shirt_transparent.png")
        val size = ImageUtils.getNaturalSize(bytes)
        assertTrue(size.pixelWidth > 0)
        assertTrue(size.pixelHeight > 0)
    }

    @Test
    fun getNaturalSize_cmykJpeg_doesNotCrash() {
        val bytes = ImageTestHelper.loadImage("cmyk_logo.jpg")
        val size = ImageUtils.getNaturalSize(bytes)
        assertTrue(size.pixelWidth > 0)
        assertTrue(size.pixelHeight > 0)
    }

    @Test
    fun getNaturalSize_colorProfileErrorJpeg_doesNotCrash() {
        val bytes = ImageTestHelper.loadImage("color_profile_error.jpg")
        val size = ImageUtils.getNaturalSize(bytes)
        assertTrue(size.pixelWidth > 0)
        assertTrue(size.pixelHeight > 0)
    }

    @Test
    fun getNaturalSize_allOrientationLandscapes_sameRawDimensions() {
        // Skia decodes raw pixels — all 8 EXIF orientations should yield identical w x h
        var firstWidth = 0
        var firstHeight = 0
        for (i in 1..8) {
            val bytes = ImageTestHelper.loadImage("orientation/Landscape_$i.jpg")
            val size = ImageUtils.getNaturalSize(bytes)
            if (i == 1) {
                firstWidth = size.pixelWidth
                firstHeight = size.pixelHeight
                assertTrue(firstWidth > 0)
                assertTrue(firstHeight > 0)
            } else {
                assertEquals(firstWidth, size.pixelWidth, "Landscape_$i width mismatch")
                assertEquals(firstHeight, size.pixelHeight, "Landscape_$i height mismatch")
            }
        }
    }

    @Test
    fun getNaturalSize_allOrientationPortraits_sameRawDimensions() {
        var firstWidth = 0
        var firstHeight = 0
        for (i in 1..8) {
            val bytes = ImageTestHelper.loadImage("orientation/Portrait_$i.jpg")
            val size = ImageUtils.getNaturalSize(bytes)
            if (i == 1) {
                firstWidth = size.pixelWidth
                firstHeight = size.pixelHeight
                assertTrue(firstWidth > 0)
                assertTrue(firstHeight > 0)
            } else {
                assertEquals(firstWidth, size.pixelWidth, "Portrait_$i width mismatch")
                assertEquals(firstHeight, size.pixelHeight, "Portrait_$i height mismatch")
            }
        }
    }

    // =========================================================
    // resizePreserveAspect
    // =========================================================

    @Test
    fun resize_downscale_landscapeJpeg() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.resizePreserveAspect(bytes, 400, 400)
        assertEquals(400, result.size.pixelWidth)
        assertEquals(300, result.size.pixelHeight)
        assertEquals(800, result.naturalSize.pixelWidth)
        assertEquals(600, result.naturalSize.pixelHeight)
        assertTrue(result.bytes.isNotEmpty())
    }

    @Test
    fun resize_noResizeNeeded_alreadyFits() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.resizePreserveAspect(bytes, 1000, 1000)
        assertEquals(800, result.size.pixelWidth)
        assertEquals(600, result.size.pixelHeight)
    }

    @Test
    fun resize_toSquareTarget() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.resizePreserveAspect(bytes, 100, 100)
        assertEquals(100, result.size.pixelWidth)
        assertEquals(75, result.size.pixelHeight)
    }

    @Test
    fun resize_highRes_to1600() {
        val bytes = ImageTestHelper.loadImage("5760_x_4320.jpg")
        val result = ImageUtils.resizePreserveAspect(bytes, 1600, 1600)
        assertEquals(1600, result.size.pixelWidth)
        assertEquals(1200, result.size.pixelHeight)
    }

    @Test
    fun resize_tinyTarget20x20() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.resizePreserveAspect(bytes, 20, 20)
        assertTrue(max(result.size.pixelWidth, result.size.pixelHeight) <= 20)
        assertTrue(result.bytes.isNotEmpty())
    }

    @Test
    fun resize_pngToJpeg() {
        val bytes = ImageTestHelper.loadImage("dice.png")
        val result = ImageUtils.resizePreserveAspect(bytes, 100, 100, outputFormat = ImageFormat.JPEG)
        ImageTestHelper.assertValidJpeg(result.bytes)
    }

    @Test
    fun resize_pngToWebp() {
        val bytes = ImageTestHelper.loadImage("dice.png")
        val result = ImageUtils.resizePreserveAspect(bytes, 100, 100, outputFormat = ImageFormat.WEBP)
        ImageTestHelper.assertValidWebp(result.bytes)
    }

    @Test
    fun resize_pngToPng() {
        val bytes = ImageTestHelper.loadImage("dice.png")
        val result = ImageUtils.resizePreserveAspect(bytes, 100, 100, outputFormat = ImageFormat.PNG)
        ImageTestHelper.assertValidPng(result.bytes)
    }

    @Test
    fun resize_webpToJpeg() {
        val bytes = ImageTestHelper.loadImage("1_webp_a.webp")
        val result = ImageUtils.resizePreserveAspect(bytes, 100, 100, outputFormat = ImageFormat.JPEG)
        ImageTestHelper.assertValidJpeg(result.bytes)
    }

    @Test
    fun resize_gifToWebp() {
        val bytes = ImageTestHelper.loadImage("mountain_800.gif")
        val result = ImageUtils.resizePreserveAspect(bytes, 200, 200, outputFormat = ImageFormat.WEBP)
        ImageTestHelper.assertValidWebp(result.bytes)
    }

    @Test
    fun resize_transparentPngToWebp() {
        val bytes = ImageTestHelper.loadImage("shirt_transparent.png")
        val result = ImageUtils.resizePreserveAspect(bytes, 100, 100, outputFormat = ImageFormat.WEBP)
        ImageTestHelper.assertValidWebp(result.bytes)
        assertTrue(result.size.pixelWidth > 0)
        assertTrue(result.size.pixelHeight > 0)
    }

    @Test
    fun resize_32x32pngInto20x20() {
        val bytes = ImageTestHelper.loadImage("pngsuite/basn6a08.png")
        val result = ImageUtils.resizePreserveAspect(bytes, 20, 20)
        assertTrue(max(result.size.pixelWidth, result.size.pixelHeight) <= 20)
    }

    @Test
    fun resize_qualityParameter_affectsFileSize() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val lowQ = ImageUtils.resizePreserveAspect(bytes, 400, 400, outputFormat = ImageFormat.JPEG, quality = 10)
        val highQ = ImageUtils.resizePreserveAspect(bytes, 400, 400, outputFormat = ImageFormat.JPEG, quality = 95)
        assertTrue(
            lowQ.bytes.size < highQ.bytes.size,
            "Low quality (${lowQ.bytes.size}) should be smaller than high quality (${highQ.bytes.size})"
        )
    }

    @Test
    fun resize_outputIsDecodable() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.resizePreserveAspect(bytes, 200, 200)
        val decoded = ImageUtils.getNaturalSize(result.bytes)
        assertEquals(result.size.pixelWidth, decoded.pixelWidth)
        assertEquals(result.size.pixelHeight, decoded.pixelHeight)
    }

    @Test
    fun resize_maxWidthZero_unconstrainedWidth() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.resizePreserveAspect(bytes, 0, 300)
        assertEquals(300, result.size.pixelHeight)
    }

    @Test
    fun resize_maxHeightZero_unconstrainedHeight() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.resizePreserveAspect(bytes, 400, 0)
        assertEquals(400, result.size.pixelWidth)
    }

    @Test
    fun resize_bmpFallback_outputIsPng() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.resizePreserveAspect(bytes, 100, 100, outputFormat = ImageFormat.BMP)
        // BMP maps to PNG internally in JVM impl
        ImageTestHelper.assertValidPng(result.bytes)
    }

    // =========================================================
    // compressOnly
    // =========================================================

    @Test
    fun compressOnly_jpeg_reducesSize() {
        val bytes = ImageTestHelper.loadImage("waterhouse.jpg")
        val result = ImageUtils.compressOnly(bytes, outputFormat = ImageFormat.JPEG, quality = 50)
        assertTrue(
            result.bytes.size < bytes.size,
            "Compressed (${result.bytes.size}) should be smaller than original (${bytes.size})"
        )
    }

    @Test
    fun compressOnly_preservesDimensions() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.compressOnly(bytes)
        assertEquals(800, result.size.pixelWidth)
        assertEquals(600, result.size.pixelHeight)
        assertEquals(result.naturalSize, result.size)
    }

    @Test
    fun compressOnly_toWebp() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.compressOnly(bytes, outputFormat = ImageFormat.WEBP)
        ImageTestHelper.assertValidWebp(result.bytes)
    }

    @Test
    fun compressOnly_toPng() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.compressOnly(bytes, outputFormat = ImageFormat.PNG)
        ImageTestHelper.assertValidPng(result.bytes)
    }

    @Test
    fun compressOnly_quality1_verySmall() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val q1 = ImageUtils.compressOnly(bytes, outputFormat = ImageFormat.JPEG, quality = 1)
        val q95 = ImageUtils.compressOnly(bytes, outputFormat = ImageFormat.JPEG, quality = 95)
        assertTrue(q1.bytes.size < q95.bytes.size, "Quality 1 should be smaller than quality 95")
    }

    @Test
    fun compressOnly_webpInput() {
        val bytes = ImageTestHelper.loadImage("1_webp_a.webp")
        val result = ImageUtils.compressOnly(bytes, outputFormat = ImageFormat.WEBP)
        ImageTestHelper.assertValidWebp(result.bytes)
    }

    @Test
    fun compressOnly_pngToJpeg() {
        val bytes = ImageTestHelper.loadImage("dice.png")
        val result = ImageUtils.compressOnly(bytes, outputFormat = ImageFormat.JPEG)
        ImageTestHelper.assertValidJpeg(result.bytes)
    }

    // =========================================================
    // crop
    // =========================================================

    @Test
    fun crop_centerSquare() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.crop(bytes, 300, 200, 200, 200)
        assertEquals(200, result.size.pixelWidth)
        assertEquals(200, result.size.pixelHeight)
        assertEquals(800, result.naturalSize.pixelWidth)
        assertEquals(600, result.naturalSize.pixelHeight)
    }

    @Test
    fun crop_topLeftCorner() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.crop(bytes, 0, 0, 100, 100)
        assertEquals(100, result.size.pixelWidth)
        assertEquals(100, result.size.pixelHeight)
    }

    @Test
    fun crop_bottomRightCorner() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.crop(bytes, 700, 500, 100, 100)
        assertEquals(100, result.size.pixelWidth)
        assertEquals(100, result.size.pixelHeight)
    }

    @Test
    fun crop_fullImage() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.crop(bytes, 0, 0, 800, 600)
        assertEquals(800, result.size.pixelWidth)
        assertEquals(600, result.size.pixelHeight)
    }

    @Test
    fun crop_negativeXY_clamped() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.crop(bytes, -10, -5, 100, 100)
        assertTrue(result.size.pixelWidth > 0)
        assertTrue(result.size.pixelHeight > 0)
    }

    @Test
    fun crop_exceedingWidth_clamped() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.crop(bytes, 700, 0, 200, 100)
        assertEquals(100, result.size.pixelWidth) // clamped: 800 - 700 = 100
    }

    @Test
    fun crop_exceedingHeight_clamped() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.crop(bytes, 0, 500, 100, 200)
        assertEquals(100, result.size.pixelHeight) // clamped: 600 - 500 = 100
    }

    @Test
    fun crop_singlePixel() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.crop(bytes, 0, 0, 1, 1)
        assertEquals(1, result.size.pixelWidth)
        assertEquals(1, result.size.pixelHeight)
    }

    @Test
    fun crop_outputFormatWebp() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.crop(bytes, 0, 0, 200, 200, outputFormat = ImageFormat.WEBP)
        ImageTestHelper.assertValidWebp(result.bytes)
    }

    @Test
    fun crop_outputIsDecodable() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.crop(bytes, 100, 100, 300, 200)
        val decoded = ImageUtils.getNaturalSize(result.bytes)
        assertEquals(result.size.pixelWidth, decoded.pixelWidth)
        assertEquals(result.size.pixelHeight, decoded.pixelHeight)
    }

    // =========================================================
    // rotate
    // =========================================================

    @Test
    fun rotate_0degrees_noop() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.rotate(bytes, 0)
        assertEquals(800, result.size.pixelWidth)
        assertEquals(600, result.size.pixelHeight)
    }

    @Test
    fun rotate_90degrees_dimensionsSwapped() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.rotate(bytes, 90)
        assertEquals(600, result.size.pixelWidth)
        assertEquals(800, result.size.pixelHeight)
    }

    @Test
    fun rotate_180degrees_dimensionsPreserved() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.rotate(bytes, 180)
        assertEquals(800, result.size.pixelWidth)
        assertEquals(600, result.size.pixelHeight)
    }

    @Test
    fun rotate_270degrees_dimensionsSwapped() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.rotate(bytes, 270)
        assertEquals(600, result.size.pixelWidth)
        assertEquals(800, result.size.pixelHeight)
    }

    @Test
    fun rotate_360degrees_normalizesToZero() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.rotate(bytes, 360)
        assertEquals(800, result.size.pixelWidth)
        assertEquals(600, result.size.pixelHeight)
    }

    @Test
    fun rotate_negative90_normalizes() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.rotate(bytes, -90)
        assertEquals(600, result.size.pixelWidth)
        assertEquals(800, result.size.pixelHeight)
    }

    @Test
    fun rotate_negative180_normalizes() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.rotate(bytes, -180)
        assertEquals(800, result.size.pixelWidth)
        assertEquals(600, result.size.pixelHeight)
    }

    @Test
    fun rotate_720degrees_normalizesToZero() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.rotate(bytes, 720)
        assertEquals(800, result.size.pixelWidth)
        assertEquals(600, result.size.pixelHeight)
    }

    @Test
    fun rotate_90degrees_squareImage() {
        val bytes = ImageTestHelper.loadImage("pngsuite/basn6a08.png")
        val result = ImageUtils.rotate(bytes, 90)
        assertEquals(32, result.size.pixelWidth)
        assertEquals(32, result.size.pixelHeight)
    }

    @Test
    fun rotate_90degrees_naturalSizePreserved() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.rotate(bytes, 90)
        assertEquals(800, result.naturalSize.pixelWidth)
        assertEquals(600, result.naturalSize.pixelHeight)
        assertEquals(600, result.size.pixelWidth)
        assertEquals(800, result.size.pixelHeight)
    }

    @Test
    fun rotate_outputFormatWebp() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.rotate(bytes, 90, outputFormat = ImageFormat.WEBP)
        ImageTestHelper.assertValidWebp(result.bytes)
    }

    @Test
    fun rotate_outputIsDecodable() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val result = ImageUtils.rotate(bytes, 90)
        val decoded = ImageUtils.getNaturalSize(result.bytes)
        assertEquals(result.size.pixelWidth, decoded.pixelWidth)
        assertEquals(result.size.pixelHeight, decoded.pixelHeight)
    }

    // =========================================================
    // toImageBitmap
    // =========================================================

    @Test
    fun toImageBitmap_validJpeg_returnsNonNull() {
        val bytes = ImageTestHelper.loadImage("roof_test_800x600.jpg")
        val bitmap = bytes.toImageBitmap()
        assertNotNull(bitmap)
        assertEquals(800, bitmap.width)
        assertEquals(600, bitmap.height)
    }

    @Test
    fun toImageBitmap_validPng_returnsNonNull() {
        val bytes = ImageTestHelper.loadImage("dice.png")
        val bitmap = bytes.toImageBitmap()
        assertNotNull(bitmap)
    }

    @Test
    fun toImageBitmap_validWebp_returnsNonNull() {
        val bytes = ImageTestHelper.loadImage("1_webp_a.webp")
        val bitmap = bytes.toImageBitmap()
        assertNotNull(bitmap)
    }

    @Test
    fun toImageBitmap_invalidBytes_returnsNull() {
        val bytes = ByteArray(100) { it.toByte() }
        val bitmap = bytes.toImageBitmap()
        assertNull(bitmap)
    }

    @Test
    fun toImageBitmap_emptyBytes_returnsNull() {
        val bitmap = ByteArray(0).toImageBitmap()
        assertNull(bitmap)
    }

    // =========================================================
    // Edge cases
    // =========================================================

    @Test
    fun resize_cmykJpeg_doesNotCrash() {
        val bytes = ImageTestHelper.loadImage("cmyk_logo.jpg")
        val result = ImageUtils.resizePreserveAspect(bytes, 100, 100)
        assertTrue(result.bytes.isNotEmpty())
        assertTrue(result.size.pixelWidth > 0)
    }

    @Test
    fun resize_colorProfileErrorJpeg_doesNotCrash() {
        val bytes = ImageTestHelper.loadImage("color_profile_error.jpg")
        val result = ImageUtils.resizePreserveAspect(bytes, 100, 100)
        assertTrue(result.bytes.isNotEmpty())
        assertTrue(result.size.pixelWidth > 0)
    }

    @Test
    fun getNaturalSize_allFormats_succeed() {
        val files = listOf(
            "roof_test_800x600.jpg",
            "dice.png",
            "1_webp_a.webp",
            "mountain_800.gif",
            "shirt_transparent.png",
            "1_webp_ll.webp"
        )
        for (file in files) {
            val bytes = ImageTestHelper.loadImage(file)
            val size = ImageUtils.getNaturalSize(bytes)
            assertTrue(size.pixelWidth > 0, "Width should be positive for $file")
            assertTrue(size.pixelHeight > 0, "Height should be positive for $file")
        }
    }

    // =========================================================
    // rasterizeSvg
    // =========================================================

    @Test
    fun rasterizeSvg_googleCalendar_tinyWebp_isValidAndUnderBudget() = runTest {
        val svg = ImageTestHelper.loadImage("google_calendar_logo.svg")
        val result = ImageUtils.rasterizeSvg(
            svgBytes = svg,
            maxDim = tinyThumbSize.maxPixelDimension,
            outputFormat = ImageFormat.WEBP,
            quality = tinyThumbSize.quality,
        )
        ImageTestHelper.assertValidWebp(result.bytes)
        assertEquals(20, max(result.size.pixelWidth, result.size.pixelHeight),
            "tiny thumb max dim should match tinyThumbSize.maxPixelDimension")
        assertTrue(result.size.pixelWidth > 0)
        assertTrue(result.size.pixelHeight > 0)
        assertTrue(
            result.bytes.size <= tinyThumbSize.maxBytes,
            "tiny rasterized SVG (${result.bytes.size} bytes) exceeds tinyThumbSize.maxBytes (${tinyThumbSize.maxBytes})"
        )
    }

    @Test
    fun rasterizeSvg_googleCalendar_320px_preservesAspect() = runTest {
        val svg = ImageTestHelper.loadImage("google_calendar_logo.svg")
        val result = ImageUtils.rasterizeSvg(
            svgBytes = svg,
            maxDim = 320,
            outputFormat = ImageFormat.WEBP,
            quality = 84,
        )
        ImageTestHelper.assertValidWebp(result.bytes)
        // Google Calendar's logo SVG is 192×192 intrinsic. Scaled to fit
        // 320×320 box → exactly 320×320 (square aspect preserved trivially).
        assertEquals(320, result.size.pixelWidth)
        assertEquals(320, result.size.pixelHeight)
        assertEquals(192, result.naturalSize.pixelWidth)
        assertEquals(192, result.naturalSize.pixelHeight)
    }

    @Test
    fun rasterizeSvg_googleCalendar_640px_doesNotExceedBox() = runTest {
        val svg = ImageTestHelper.loadImage("google_calendar_logo.svg")
        val result = ImageUtils.rasterizeSvg(
            svgBytes = svg,
            maxDim = 640,
            outputFormat = ImageFormat.WEBP,
        )
        ImageTestHelper.assertValidWebp(result.bytes)
        assertTrue(result.size.pixelWidth <= 640)
        assertTrue(result.size.pixelHeight <= 640)
    }

    @Test
    fun rasterizeSvg_malformedInput_throws() = runTest {
        val notSvg = "<not-actually-svg><foo></foo></not-actually-svg>".toByteArray()
        assertFails {
            ImageUtils.rasterizeSvg(notSvg, maxDim = 20)
        }
    }

    /**
     * Cross-corpus rasterization: exercise every SVG fixture (real-world
     * Google Calendar logo + a synthetic corpus covering geometric,
     * path, gradient, multipath, viewBox-only, percent-dimensions,
     * landscape and portrait aspect ratios) at three target sizes.
     *
     * Each rasterized output must be a valid webp, the tiny variant
     * must stay under [tinyThumbSize.maxBytes] so it can ride as an
     * EmbeddedThumb without exceeding the server's MaxEmbeddedThumbBytes
     * cap, and aspect ratio must be preserved.
     */
    @Test
    fun rasterizeSvg_corpus_allFixturesProduceValidWebpAtEverySize() = runTest {
        val corpus = listOf(
            "google_calendar_logo.svg",   // real-world (the original bug)
            "svg/geometric_square.svg",   // rect + circle, square
            "svg/path_heart.svg",         // path-only, with viewBox
            "svg/viewbox_only_arrow.svg", // viewBox without width/height attrs
            "svg/gradient_circle.svg",    // radialGradient
            "svg/wide_banner.svg",        // 5:1 landscape
            "svg/tall_pillar.svg",        // 1:5 portrait
            "svg/multipath_star.svg",     // multi-element with stroke and fill
            "svg/percent_dimensions.svg", // width/height="100%" + viewBox
        )

        for (fixture in corpus) {
            val svg = ImageTestHelper.loadImage(fixture)

            // Tiny
            val tiny = ImageUtils.rasterizeSvg(
                svgBytes = svg,
                maxDim = tinyThumbSize.maxPixelDimension,
                outputFormat = ImageFormat.WEBP,
                quality = tinyThumbSize.quality,
            )
            ImageTestHelper.assertValidWebp(tiny.bytes)
            assertTrue(
                max(tiny.size.pixelWidth, tiny.size.pixelHeight) <= tinyThumbSize.maxPixelDimension,
                "[$fixture] tiny max dim ${max(tiny.size.pixelWidth, tiny.size.pixelHeight)} > ${tinyThumbSize.maxPixelDimension}"
            )
            assertTrue(
                tiny.bytes.size <= tinyThumbSize.maxBytes,
                "[$fixture] tiny rasterized SVG is ${tiny.bytes.size} bytes > ${tinyThumbSize.maxBytes}"
            )
            assertAspectPreserved(fixture, "tiny", tiny)

            // Mid (320 px)
            val mid = ImageUtils.rasterizeSvg(svg, maxDim = 320, outputFormat = ImageFormat.WEBP, quality = 84)
            ImageTestHelper.assertValidWebp(mid.bytes)
            assertTrue(
                max(mid.size.pixelWidth, mid.size.pixelHeight) <= 320,
                "[$fixture] mid max dim ${max(mid.size.pixelWidth, mid.size.pixelHeight)} > 320"
            )
            assertAspectPreserved(fixture, "mid", mid)

            // Large (1080 px)
            val large = ImageUtils.rasterizeSvg(svg, maxDim = 1080, outputFormat = ImageFormat.WEBP)
            ImageTestHelper.assertValidWebp(large.bytes)
            assertTrue(
                max(large.size.pixelWidth, large.size.pixelHeight) <= 1080,
                "[$fixture] large max dim ${max(large.size.pixelWidth, large.size.pixelHeight)} > 1080"
            )
            assertAspectPreserved(fixture, "large", large)
        }
    }

    /**
     * Helper: rendered (w, h) must have the same w:h ratio as natural
     * (w, h) to within ±1 px of rounding error, since rasterizeSvg
     * scales aspect-preservingly to fit inside the maxDim box.
     */
    private fun assertAspectPreserved(fixture: String, tag: String, r: ImageResult) {
        val natW = r.naturalSize.pixelWidth.toDouble()
        val natH = r.naturalSize.pixelHeight.toDouble()
        val renW = r.size.pixelWidth.toDouble()
        val renH = r.size.pixelHeight.toDouble()
        val expectedRatio = natW / natH
        val actualRatio = renW / renH
        val drift = kotlin.math.abs(actualRatio - expectedRatio) / expectedRatio
        assertTrue(
            drift < 0.1,
            "[$fixture/$tag] aspect drift $drift (rendered ${renW.toInt()}x${renH.toInt()} vs natural ${natW.toInt()}x${natH.toInt()})"
        )
    }
}
