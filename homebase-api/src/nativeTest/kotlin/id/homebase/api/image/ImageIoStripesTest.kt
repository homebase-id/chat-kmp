package id.homebase.api.image

import kotlinx.coroutines.test.runTest
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class ImageIoStripesTest {
    // 48 MP, so the decode goes through the memory guard.
    private fun stripes(period: Int) =
        ImageUtils.encodeArgbToPng(ArgbImage(IntArray(WIDTH * HEIGHT) { if (it % period == 0) BLACK else WHITE }, WIDTH, HEIGHT))

    private suspend fun spreadAt(target: Int, period: Int = 2): Double {
        val image = renderImages(stripes(period)) { listOf(ImageOutput(target, 2, 100, Int.MAX_VALUE, ImageFormat.PNG)) }.images.single()
        val pixels = assertNotNull(ImageUtils.decodeToArgb(image.bytes))
        val greens = (pixels.width / 8 until pixels.width * 7 / 8).map { (pixels.pixels[it] shr 8) and 0xFF }
        val mean = greens.average()
        return sqrt(greens.sumOf { (it - mean) * (it - mean) } / greens.size)
    }

    @Test
    fun onePixelStripesTurnGreyThroughTheMemoryGuard() = runTest(timeout = TIMEOUT) {
        val spread = spreadAt(2048)
        assertTrue(spread <= 5.0, "$WIDTH -> 2048 deviated by $spread")
    }

    // HD's 4096 can't get a 2x step under the guard, so ImageIO makes a 1.43x one and aliases until resizing goes native.
    @Test
    fun theGuardsSubTwoTimesStepStillShrinksTheStripes() = runTest(timeout = TIMEOUT) {
        val spread = spreadAt(4096)
        assertTrue(spread <= 30.0, "$WIDTH -> 4096 deviated by $spread")
    }

    // Over the guard, ImageIO still halves 48 MP to 4032 before Lanczos makes 2048: its 2x is the one block shrink left
    // that isn't followed by 2x of Lanczos. At worst it's a 2x2 average (about 60).
    @Test
    fun threePixelStripesThroughImageIosHalfUnderTheGuard() = runTest(timeout = TIMEOUT) {
        val spread = spreadAt(2048, period = 3)
        println("ImageIO 3-px stripes $WIDTH -> 2048 under the guard: spread $spread")
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
