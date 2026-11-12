package id.homebase.api.image

import kotlinx.coroutines.test.runTest
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Sources over 24 MP, so every decode goes through the memory guard.
class ImageIoStripesTest {
    private fun stripes(width: Int, height: Int) =
        ImageUtils.encodeArgbToPng(ArgbImage(IntArray(width * height) { if (it % 2 == 0) BLACK else WHITE }, width, height))

    private suspend fun spread(source: ByteArray, target: Int): Double {
        val image = renderImages(source) { listOf(ImageOutput(target, 2, 100, Int.MAX_VALUE, ImageFormat.PNG)) }.images.single()
        val pixels = assertNotNull(ImageUtils.decodeToArgb(image.bytes))
        val greens = (pixels.width / 8 until pixels.width * 7 / 8).map { (pixels.pixels[it] shr 8) and 0xFF }
        val mean = greens.average()
        return sqrt(greens.sumOf { (it - mean) * (it - mean) } / greens.size)
    }

    private suspend fun assertGrey(width: Int, height: Int, vararg targets: Int) {
        val source = stripes(width, height)
        val failures = targets.map { it to spread(source, it) }.filter { it.second > 5.0 }
        assertTrue(failures.isEmpty(), failures.joinToString { "$width -> ${it.first} deviated by ${it.second}" })
    }

    @Test
    fun a48MegapixelPhotoTurnsGreyAtStandardAndHd() = runTest { assertGrey(8064, 6048, 2048, 4096) }

    // An iPhone's default 24 MP photo is 24.5 MP, just over the guard's limit.
    @Test
    fun a24MegapixelPhotoTurnsGreyAtStandardAndHd() = runTest { assertGrey(5712, 4284, 2048, 4096) }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
