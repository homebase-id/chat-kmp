package id.homebase.api.image

import kotlinx.coroutines.test.runTest
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImageIoStripesTest {
    // 48 MP, so the decode goes through the memory guard.
    private val stripes = ImageUtils.encodeArgbToPng(
        ArgbImage(IntArray(WIDTH * HEIGHT) { if (it % 2 == 0) BLACK else WHITE }, WIDTH, HEIGHT),
    )

    private suspend fun spreadAt(target: Int): Double {
        val image = renderImages(stripes) { listOf(ImageOutput(target, 2, 100, Int.MAX_VALUE, ImageFormat.PNG)) }.images.single()
        val pixels = assertNotNull(ImageUtils.decodeToArgb(image.bytes))
        val greens = (pixels.width / 8 until pixels.width * 7 / 8).map { (pixels.pixels[it] shr 8) and 0xFF }
        val mean = greens.average()
        return sqrt(greens.sumOf { (it - mean) * (it - mean) } / greens.size)
    }

    @Test
    fun onePixelStripesTurnGreyThroughTheMemoryGuard() = runTest {
        for (target in listOf(2048, 4096)) {
            val spread = spreadAt(target)
            assertTrue(spread <= 5.0, "$WIDTH -> $target deviated by $spread")
        }
    }

    private companion object {
        const val WIDTH = 8064
        const val HEIGHT = 6048
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
