package id.homebase.api.image

import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The pipeline contract on the real decoders, scalers and encoders of each platform. */
class ImagePipelineContractTest {

    private fun png(width: Int, height: Int, pixel: (x: Int, y: Int) -> Int): ByteArray {
        val pixels = IntArray(width * height) { pixel(it % width, it / width) }
        return ImageUtils.encodeArgbToPng(ArgbImage(pixels, width, height))
    }

    private fun decoded(bytes: ByteArray) = assertNotNull(ImageUtils.decodeToArgb(bytes), "Output must decode")

    private fun output(width: Int, height: Int, quality: Int = 90, maxBytes: Int = Int.MAX_VALUE) =
        ImageOutput(width, height, quality, maxBytes)

    @Test
    fun aOnePixelCheckerboardAveragesToGrey() = runTest {
        val checkerboard = png(400, 400) { x, y -> if ((x + y) % 2 == 0) BLACK else WHITE }

        val image = renderImages(checkerboard) { listOf(ImageOutput(40, 40, 100, Int.MAX_VALUE, ImageFormat.PNG)) }
            .images.single()

        val centre = decoded(image.bytes).let { it.pixels[it.width * 20 + 20] }
        for (shift in listOf(16, 8, 0)) {
            val channel = (centre shr shift) and 0xFF
            assertTrue(abs(channel - 128) <= 12, "Expected mid-grey, got channel $channel")
        }
    }

    @Test
    fun outputsKeepThePlansOrderAndExactSizes() = runTest {
        val source = png(800, 600) { x, y -> opaque(x % 256, y % 256, 0) }

        val result = renderImages(source) {
            assertEquals(ImageSize(800, 600), it)
            listOf(output(20, 15), output(640, 480), output(320, 240))
        }

        assertEquals(listOf(20, 640, 320), result.images.map { it.width })
        assertEquals(listOf(15, 480, 240), result.images.map { it.height })
        result.images.forEach { image ->
            val pixels = decoded(image.bytes)
            assertEquals(image.width to image.height, pixels.width to pixels.height)
        }
    }

    @Test
    fun transparencySurvivesScaling() = runTest {
        val halfClear = png(400, 400) { x, _ -> if (x < 200) 0x00000000 else opaque(255, 0, 0) }

        val image = renderImages(halfClear) { listOf(ImageOutput(50, 50, 100, Int.MAX_VALUE, ImageFormat.PNG)) }
            .images.single()

        val pixels = decoded(image.bytes)
        assertEquals(0, pixels.pixels[25 * 50 + 2].ushr(24), "Left side stays transparent")
        assertEquals(255, pixels.pixels[25 * 50 + 47].ushr(24), "Right side stays opaque")
    }

    @Test
    fun theWorstCaseTinyFitsItsLimit() = runTest {
        val random = Random(2221)
        val transparentNoise = png(400, 400) { _, _ -> random.nextInt() }

        val result = renderImages(transparentNoise) {
            listOf(ImageOutput(20, 20, 76, TINY_LIMIT, minQuality = 1))
        }.images.single()

        assertTrue(result.bytes.size <= TINY_LIMIT, "Tiny was ${result.bytes.size} bytes")
        decoded(result.bytes)
    }

    @Test
    fun anImageOverItsLimitLowersQualityFromThePixelsBeforeShrinking() = runTest {
        val random = Random(1)
        val noise = png(400, 400) { _, _ -> opaque(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        val full = renderImages(noise) { listOf(output(400, 400)) }.images.single()

        val limited = renderImages(noise) { listOf(output(400, 400, maxBytes = full.bytes.size * 8 / 10)) }.images.single()

        assertTrue(limited.quality < 90, "Quality should have dropped, was ${limited.quality}")
        assertEquals(400, limited.width, "A small overage shouldn't cost any pixels")
        assertTrue(limited.bytes.size <= full.bytes.size * 8 / 10)
    }

    @Test
    fun anUnreachableLimitEndsAtOnePixelInsteadOfLooping() = runTest {
        val source = png(100, 100) { x, y -> opaque(x, y, 0) }

        val image = renderImages(source) { listOf(output(100, 100, maxBytes = 1)) }.images.single()

        assertEquals(1 to 1, image.width to image.height)
    }

    @Test
    fun aValidHeaderOverACorruptBodyThrowsInsteadOfMakingBlankImages() = runTest {
        val good = ImageUtils.resizePreserveAspect(png(64, 64) { x, y -> opaque(x, y, 0) }, 64, 64, ImageFormat.JPEG, 90).bytes
        val random = Random(3)
        val corrupt = good.copyOf(400).also { for (i in 200 until it.size) it[i] = random.nextInt().toByte() }

        val result = runCatching { renderImages(corrupt) { listOf(ImageOutput(32, 32, 90, Int.MAX_VALUE, ImageFormat.PNG)) } }

        val image = result.getOrNull()?.images?.single() ?: return@runTest
        val pixels = decoded(image.bytes).pixels
        assertTrue(pixels.any { it.ushr(24) != 0 }, "A decoder may salvage a corrupt body, but never as a blank image")
    }

    @Test
    fun aVeryThinImageNeverGoesBelowOnePixel() = runTest {
        val thin = png(4000, 2) { x, _ -> opaque(x % 256, 0, 0) }

        val image = renderImages(thin) { size ->
            val (w, h) = calculateTargetDimensions(size.pixelWidth, size.pixelHeight, 320, 320)
            listOf(output(w, h))
        }.images.single()

        assertEquals(320 to 1, image.width to image.height)
    }

    @Test
    fun resizeUsesTheSameFilteredPath() {
        val checkerboard = png(400, 400) { x, y -> if ((x + y) % 2 == 0) BLACK else WHITE }

        val result = ImageUtils.resizePreserveAspect(checkerboard, 40, 40, ImageFormat.PNG, 100)

        assertEquals(ImageSize(40, 40), result.size)
        val centre = decoded(result.bytes).let { it.pixels[it.width * 20 + 20] }
        val channel = (centre shr 8) and 0xFF
        assertTrue(abs(channel - 128) <= 12, "Expected mid-grey, got channel $channel")
    }

    private fun stripes(width: Int, half: Int) = png(width, STRIPES_HEIGHT) { x, _ -> if ((x / half) % 2 == 0) BLACK else WHITE }

    // Skips the edges, where the filter's window runs off the image.
    private fun middleGreens(bytes: ByteArray): List<Int> {
        val image = decoded(bytes)
        val row = image.height / 2
        return (image.width / 8 until image.width * 7 / 8).map { (image.pixels[row * image.width + it] shr 8) and 0xFF }
    }

    private fun deviation(values: List<Int>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    private suspend fun stripesAfter(source: ByteArray, sourceWidth: Int, target: Int, viaDecoder: Boolean): ByteArray {
        val outputs = buildList {
            // A full-size output first forces a full decode, so the pipeline's own scaler does the work.
            if (!viaDecoder) add(ImageOutput(sourceWidth, STRIPES_HEIGHT, 1, Int.MAX_VALUE))
            add(ImageOutput(target, 2, 100, Int.MAX_VALUE, ImageFormat.PNG))
        }
        return renderImages(source) { outputs }.images.last().bytes
    }

    private suspend fun assertStripesTurnGrey(viaDecoder: Boolean) {
        val limits = listOf(4000 to 400 to 5.0, 4032 to 2048 to 5.0, 2048 to 1080 to 5.0, 1080 to 640 to 5.0, 4000 to 2667 to 5.0, 3000 to 2048 to 5.0, 2560 to 2048 to 30.0)
        for ((sizes, limit) in limits) {
            val (from, to) = sizes
            val spread = deviation(middleGreens(stripesAfter(stripes(from, 1), from, to, viaDecoder)))
            assertTrue(spread <= limit, "$from -> $to deviated by $spread")
        }
    }

    @Test
    fun onePixelStripesTurnGreyWhenThePipelineScales() = runTest { assertStripesTurnGrey(viaDecoder = false) }

    @Test
    fun onePixelStripesTurnGreyWhenTheDecoderScales() = runTest { assertStripesTurnGrey(viaDecoder = true) }

    @Test
    fun coarseStripesKeepTheirContrast() = runTest {
        val greens = middleGreens(stripesAfter(stripes(1080, 8), 1080, 640, viaDecoder = false))
        assertTrue(greens.min() <= 20 && greens.max() >= 235, "Range ${greens.min()}-${greens.max()}")
    }

    @Test
    fun aSourceSizeOutputIsPixelIdentical() = runTest {
        val random = Random(6)
        val source = png(300, 200) { _, _ -> opaque(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }

        val image = renderImages(source) { listOf(ImageOutput(300, 200, 100, Int.MAX_VALUE, ImageFormat.PNG)) }.images.single()

        assertTrue(decoded(source).pixels.contentEquals(decoded(image.bytes).pixels))
    }

    private fun withOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val tiff = byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        val body = "Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff
        val length = body.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + body
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    @Test
    fun everyExifOrientationComesOutUpright() = runTest {
        val leftRed = ImageUtils.resizePreserveAspect(
            png(120, 60) { x, _ -> if (x < 60) opaque(255, 0, 0) else opaque(0, 0, 255) }, 120, 60, ImageFormat.JPEG, 95,
        ).bytes
        // Where the left (red) half of the stored pixels ends up on screen.
        val redSide = mapOf(1 to "left", 2 to "right", 3 to "right", 4 to "left", 5 to "top", 6 to "top", 7 to "bottom", 8 to "bottom")
        for ((orientation, side) in redSide) {
            val image = renderImages(withOrientation(leftRed, orientation)) { size ->
                listOf(ImageOutput(size.pixelWidth, size.pixelHeight, 100, Int.MAX_VALUE, ImageFormat.PNG))
            }.images.single()
            val pixels = decoded(image.bytes)
            val sideways = orientation >= 5
            assertEquals(if (sideways) 60 to 120 else 120 to 60, pixels.width to pixels.height, "Orientation $orientation size")
            val (x, y) = when (side) {
                "left" -> 10 to pixels.height / 2
                "right" -> pixels.width - 10 to pixels.height / 2
                "top" -> pixels.width / 2 to 10
                else -> pixels.width / 2 to pixels.height - 10
            }
            val red = (pixels.pixels[y * pixels.width + x] shr 16) and 0xFF
            assertTrue(red > 200, "Orientation $orientation: expected red on the $side, got $red")
        }
    }

    private fun hasIccProfile(webp: ByteArray) = webp.decodeToString().contains("ICCP")

    @Test
    fun sRgbOutputsCarryNoColourProfile() = runTest {
        val source = png(400, 300) { x, y -> opaque(x % 256, y % 256, 90) }

        val media = createMediaImages(source, "key", MediaQuality.HD)

        assertTrue(!hasIccProfile(assertNotNull(media.tiny?.content).bytes), "Tiny has a profile")
        media.thumbnails.forEach { assertTrue(!hasIccProfile(it.thumbnailBytes), "${it.pixelWidth} has a profile") }
        assertTrue(!hasIccProfile(assertNotNull(media.finalImage).bytes), "Final image has a profile")
        decoded(media.tiny!!.content!!.bytes)
        decoded(media.thumbnails.first().thumbnailBytes)
    }

    @Test
    fun anOverLimitThumbnailShrinksAtTheSameQuality() = runTest {
        val random = Random(4)
        val noise = png(400, 400) { _, _ -> opaque(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        val full = renderImages(noise) { listOf(output(400, 400, quality = 80)) }.images.single()
        val limit = full.bytes.size * 8 / 10

        val limited = renderImages(noise) {
            listOf(ImageOutput(400, 400, 80, limit, overLimit = OverLimit.SHRINK))
        }.images.single()

        assertEquals(80, limited.quality)
        assertTrue(limited.width < 400, "Should have shrunk")
        assertTrue(limited.bytes.size <= limit)
        assertEquals(limited.width to limited.height, decoded(limited.bytes).let { it.width to it.height })
    }

    @Test
    fun anEnlargedThumbnailOverItsLimitGoesBackToItsRowSize() = runTest {
        val random = Random(5)
        val noise = png(400, 400) { _, _ -> opaque(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        val atRow = renderImages(noise) { listOf(output(380, 380, quality = 80)) }.images.single()
        val full = renderImages(noise) { listOf(output(400, 400, quality = 80)) }.images.single()
        val limit = (atRow.bytes.size + full.bytes.size) / 2

        val limited = renderImages(noise) {
            listOf(ImageOutput(400, 400, 80, limit, overLimit = OverLimit.SHRINK, rowSize = ImageSize(380, 380)))
        }.images.single()

        assertEquals(380 to 380, limited.width to limited.height)
        assertEquals(80, limited.quality)
    }

    private fun opaque(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val TINY_LIMIT = 768
        const val STRIPES_HEIGHT = 8
    }
}
