package id.homebase.api.image

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class ImageIoStripesTest {
    // Over the 24 MP memory guard, so the decode goes through it.
    private fun stripes(period: Int, height: Int) =
        ImageUtils.encodeArgbToPng(ArgbImage(IntArray(WIDTH * height) { if (it % period == 0) BLACK else WHITE }, WIDTH, height))

    private suspend fun spreadAt(target: Int, period: Int = 2, height: Int = HEIGHT): Double {
        val image = renderImages(stripes(period, height)) { listOf(ImageOutput(target, 2, 100, Int.MAX_VALUE, ImageFormat.PNG)) }.images.single()
        return deviation(middleGreens(image.bytes))
    }

    @Test
    fun onePixelStripesTurnGreyThroughTheMemoryGuard() = runTest(timeout = TIMEOUT) {
        val spread = spreadAt(2048)
        assertTrue(spread <= 5.0, "$WIDTH -> 2048 deviated by $spread")
    }

    // A 4096 output from 48 MP can't get a 2x step under the guard, so ImageIO makes a 1.43x one, and aliases.
    @Test
    fun theGuardsSubTwoTimesStepStillShrinksTheStripes() = runTest(timeout = TIMEOUT) {
        val spread = spreadAt(4096)
        assertTrue(spread <= 30.0, "$WIDTH -> 4096 deviated by $spread")
    }

    // Over the guard, ImageIO still halves a photo 2-4x too big before Lanczos: the one block shrink left without 2x of
    // Lanczos after it. 24.2 MP is just over the guard; at worst it's a 2x2 average (about 60).
    @Test
    fun threePixelStripesThroughImageIosHalfUnderTheGuard() = runTest(timeout = TIMEOUT) {
        val spread = spreadAt(2048, period = 3, height = 3000)
        println("ImageIO 3-px stripes $WIDTH x 3000 -> 2048 under the guard: spread $spread")
        assertTrue(spread <= 60.0, "$WIDTH -> 2048 deviated by $spread")
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
