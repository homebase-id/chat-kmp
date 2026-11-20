package id.homebase.api.image

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class ImageIoStripesTest {
    private fun stripes(period: Int, height: Int) =
        ImageUtils.encodeArgbToPng(ArgbImage(IntArray(WIDTH * height) { if (it % period == 0) BLACK else WHITE }, WIDTH, height))

    private suspend fun spreadAt(target: Int, period: Int = 2, height: Int = HEIGHT): Double {
        val image = renderImages(stripes(period, height)) { listOf(ImageOutput(target, 2, 100, Int.MAX_VALUE, ImageFormat.PNG)) }.images.single()
        return deviation(middleGreens(image.bytes))
    }

    @Test
    fun onePixelStripesTurnGreyFrom48Mp() = runTest(timeout = TIMEOUT) {
        val spread = spreadAt(2048)
        assertTrue(spread <= 5.0, "$WIDTH -> 2048 deviated by $spread")
    }

    // HD from 48 MP: no 2x step for ImageIO, so it decodes in full and Lanczos makes the whole 1.97x.
    @Test
    fun onePixelStripesTurnGreyAtHdFrom48Mp() = runTest(timeout = TIMEOUT) {
        val spread = spreadAt(4096)
        assertTrue(spread <= 5.0, "$WIDTH -> 4096 deviated by $spread")
    }

    // A 2x2 average can't cancel these, as it can 1-pixel stripes.
    @Test
    fun threePixelStripesTurnGreyFrom24Mp() = runTest(timeout = TIMEOUT) {
        val spread = spreadAt(2048, period = 3, height = 3000)
        assertTrue(spread <= 8.0, "$WIDTH -> 2048 deviated by $spread")
    }

    private companion object {
        // Encoding and decoding 48 MP on the simulator; runTest's default is 60 s.
        val TIMEOUT = 5.minutes
        const val WIDTH = 8064
        const val HEIGHT = 6048
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
