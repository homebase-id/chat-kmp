package id.homebase.api.image

import kotlinx.coroutines.test.runTest
import kotlin.math.abs
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
    }
}
