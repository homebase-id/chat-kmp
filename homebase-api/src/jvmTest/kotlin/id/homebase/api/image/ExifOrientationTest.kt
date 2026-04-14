package id.homebase.api.image

import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedOrigin
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests that verify EXIF orientation is properly handled during image processing.
 *
 * The orientation test images (Landscape_1-8, Portrait_1-8) each show the same waterfall
 * scene with text labels ("top", "bottom", "left", "right") and an orientation number.
 * The pixel data is transformed inversely to the EXIF tag, so a correct decoder should
 * display them all with "top" at top, flowers at bottom-left, etc.
 *
 * Note: These images have different embedded numbers (1-8) so pixel-identical comparison
 * is NOT possible. Instead we verify:
 * - Skia reports correct EXIF-corrected display dimensions
 * - Codec raw dims differ from display dims for orientations 5-8 (proving EXIF is applied)
 * - Landmark pixel regions (red flowers at bottom-left) are in the correct position
 */
class ExifOrientationTest {

    // =========================================================
    // Verify test images have all 8 distinct EXIF orientations
    // =========================================================

    @Test
    fun landscapeImages_haveDifferentExifOrientations() {
        val origins = mutableSetOf<EncodedOrigin>()
        for (i in 1..8) {
            val bytes = ImageTestHelper.loadImage("orientation/Landscape_$i.jpg")
            val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
            origins.add(codec.encodedOrigin)
        }
        assertEquals(8, origins.size, "All 8 EXIF orientations should be distinct")
    }

    @Test
    fun portraitImages_haveDifferentExifOrientations() {
        val origins = mutableSetOf<EncodedOrigin>()
        for (i in 1..8) {
            val bytes = ImageTestHelper.loadImage("orientation/Portrait_$i.jpg")
            val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
            origins.add(codec.encodedOrigin)
        }
        assertEquals(8, origins.size, "All 8 EXIF orientations should be distinct")
    }

    // =========================================================
    // Skia applies EXIF correction: display dims correct for all
    // =========================================================

    @Test
    fun landscape_allOrientations_displayDimensionsAreLandscape() {
        // After EXIF correction, ALL landscape images should decode as landscape (wider than tall)
        for (i in 1..8) {
            val bytes = ImageTestHelper.loadImage("orientation/Landscape_$i.jpg")
            val size = ImageUtils.getNaturalSize(bytes)
            assertEquals(600, size.pixelWidth, "Landscape_$i: expected display width 600")
            assertEquals(450, size.pixelHeight, "Landscape_$i: expected display height 450")
        }
    }

    @Test
    fun portrait_allOrientations_displayDimensionsArePortrait() {
        // After EXIF correction, ALL portrait images should decode as portrait (taller than wide)
        for (i in 1..8) {
            val bytes = ImageTestHelper.loadImage("orientation/Portrait_$i.jpg")
            val size = ImageUtils.getNaturalSize(bytes)
            assertEquals(450, size.pixelWidth, "Portrait_$i: expected display width 450")
            assertEquals(600, size.pixelHeight, "Portrait_$i: expected display height 600")
        }
    }

    // =========================================================
    // Orientations 5-8 have swapped codec dims (proves EXIF is applied)
    // =========================================================

    @Test
    fun landscape_orientations5to8_haveSwappedRawDimensions() {
        // Orientations 5-8 store the image rotated 90° — raw codec dims should be 450x600
        // while the decoded Image should be 600x450 (Skia auto-corrects)
        for (i in 5..8) {
            val bytes = ImageTestHelper.loadImage("orientation/Landscape_$i.jpg")
            val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
            val image = Image.makeFromEncoded(bytes)

            assertEquals(450, codec.width, "Landscape_$i: raw codec width should be 450")
            assertEquals(600, codec.height, "Landscape_$i: raw codec height should be 600")
            assertEquals(600, image.width, "Landscape_$i: decoded image width should be 600 (EXIF-corrected)")
            assertEquals(450, image.height, "Landscape_$i: decoded image height should be 450 (EXIF-corrected)")
        }
    }

    @Test
    fun landscape_orientations1to4_haveNormalRawDimensions() {
        // Orientations 1-4 don't swap dims — raw and decoded should match
        for (i in 1..4) {
            val bytes = ImageTestHelper.loadImage("orientation/Landscape_$i.jpg")
            val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
            val image = Image.makeFromEncoded(bytes)

            assertEquals(600, codec.width, "Landscape_$i: raw codec width should be 600")
            assertEquals(450, codec.height, "Landscape_$i: raw codec height should be 450")
            assertEquals(600, image.width, "Landscape_$i: decoded width should be 600")
            assertEquals(450, image.height, "Landscape_$i: decoded height should be 450")
        }
    }

    // =========================================================
    // Landmark pixel test: red flowers are at bottom-left
    // in the correctly oriented image. Verify they're there
    // for all 8 orientations after Skia's EXIF correction.
    // =========================================================

    /**
     * Get the average color (R, G, B) of a region in the decoded image.
     */
    private fun getRegionAvgColor(
        image: Image, x: Int, y: Int, w: Int, h: Int
    ): Triple<Int, Int, Int> {
        val info = ImageInfo.makeN32(image.width, image.height, ColorAlphaType.UNPREMUL)
        val bitmap = Bitmap()
        bitmap.allocPixels(info)
        val canvas = Canvas(bitmap)
        canvas.drawImage(image, 0f, 0f)
        val pixels = bitmap.readPixels(info, info.minRowBytes, 0, 0)!!
        val rowBytes = image.width * 4 // RGBA, 4 bytes per pixel

        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        var count = 0
        for (row in y until (y + h).coerceAtMost(image.height)) {
            for (col in x until (x + w).coerceAtMost(image.width)) {
                val offset = row * rowBytes + col * 4
                // N32 on little-endian is BGRA
                totalB += pixels[offset].toInt() and 0xFF
                totalG += pixels[offset + 1].toInt() and 0xFF
                totalR += pixels[offset + 2].toInt() and 0xFF
                count++
            }
        }
        return Triple(
            (totalR / count).toInt(),
            (totalG / count).toInt(),
            (totalB / count).toInt()
        )
    }

    @Test
    fun landscape_allOrientations_redFlowersAtBottomLeft() {
        // In the correctly oriented waterfall image, red flowers are in the bottom-left quadrant.
        // The bottom-left should have distinctly higher red channel values than the top-right (green trees).
        for (i in 1..8) {
            val bytes = ImageTestHelper.loadImage("orientation/Landscape_$i.jpg")
            val image = Image.makeFromEncoded(bytes)

            // Sample bottom-left region (flowers area)
            val (blR, blG, blB) = getRegionAvgColor(image, 0, 350, 150, 100)
            // Sample top-right region (trees area)
            val (trR, trG, trB) = getRegionAvgColor(image, 450, 0, 150, 100)

            // The bottom-left (flowers) should have a notably higher red component
            // relative to green compared to the top-right (trees)
            val blRedRatio = blR.toDouble() / (blG + 1).toDouble()
            val trRedRatio = trR.toDouble() / (trG + 1).toDouble()

            assertTrue(
                blRedRatio > trRedRatio,
                "Landscape_$i: bottom-left should be more red (ratio=${"%.2f".format(blRedRatio)}) " +
                    "than top-right (ratio=${"%.2f".format(trRedRatio)}). " +
                    "EXIF orientation may not be applied correctly."
            )
        }
    }

    // =========================================================
    // Thumbnail pipeline: all orientations produce landscape thumbnails
    // =========================================================

    @Test
    fun createThumbnails_landscape_allOrientationsProduceLandscapeThumbs() = runTest {
        for (i in 1..8) {
            val bytes = ImageTestHelper.loadImage("orientation/Landscape_$i.jpg")
            val (naturalSize, _, thumbList) = createThumbnails(bytes, "key")

            // Natural size should always be landscape
            assertEquals(600, naturalSize.pixelWidth, "Landscape_$i naturalSize width")
            assertEquals(450, naturalSize.pixelHeight, "Landscape_$i naturalSize height")

            // Every thumbnail should also be landscape (wider than tall)
            for (thumb in thumbList) {
                assertTrue(
                    thumb.pixelWidth > thumb.pixelHeight,
                    "Landscape_$i: thumb ${thumb.pixelWidth}x${thumb.pixelHeight} should be landscape"
                )
            }
        }
    }

    @Test
    fun createThumbnails_portrait_allOrientationsProducePortraitThumbs() = runTest {
        for (i in 1..8) {
            val bytes = ImageTestHelper.loadImage("orientation/Portrait_$i.jpg")
            val (naturalSize, _, thumbList) = createThumbnails(bytes, "key")

            // Natural size should always be portrait
            assertEquals(450, naturalSize.pixelWidth, "Portrait_$i naturalSize width")
            assertEquals(600, naturalSize.pixelHeight, "Portrait_$i naturalSize height")

            // Every thumbnail should also be portrait (taller than wide)
            for (thumb in thumbList) {
                assertTrue(
                    thumb.pixelHeight > thumb.pixelWidth,
                    "Portrait_$i: thumb ${thumb.pixelWidth}x${thumb.pixelHeight} should be portrait"
                )
            }
        }
    }
}
