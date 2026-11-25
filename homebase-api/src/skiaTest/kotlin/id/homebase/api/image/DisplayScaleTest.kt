package id.homebase.api.image

import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisplayScaleTest {

    private fun stripes(width: Int): ByteArray {
        val pixels = IntArray(width * HEIGHT) { if ((it % width) % 2 == 0) BLACK else WHITE }
        return ImageUtils.encodeArgbToPng(ArgbImage(pixels, width, HEIGHT))
    }

    private fun Image.png(): ByteArray = encodeToData(EncodedImageFormat.PNG)!!.bytes

    @Test
    fun onePixelStripesTurnGreyAtEveryStep() = runTest {
        // 1.97x is Lanczos alone, 10x halves twice first, and 1.25x is where Lanczos-3 has least room.
        val limits = listOf(788 to 400 to 5.0, 4000 to 400 to 5.0, 2560 to 2048 to 30.0)
        val failures = limits.mapNotNull { (sizes, limit) ->
            val (from, to) = sizes
            val image = decodeForDisplay(stripes(from)) { ImageSize(to, max(1, HEIGHT * to / from)) }
            val spread = try {
                assertEquals(to, image.width)
                deviation(middleGreens(image.png()))
            } finally {
                image.close()
            }
            "$from -> $to deviated by $spread".takeIf { spread > limit }
        }
        assertTrue(failures.isEmpty(), failures.joinToString())
    }

    @Test
    fun aLargeDecodeWaitsItsTurnButKeepsLanczos() = runTest {
        val image = decodeForDisplay(stripes(788), largePixels = 1) { ImageSize(400, 4) }
        val spread = try {
            assertEquals(400, image.width)
            deviation(middleGreens(image.png()))
        } finally {
            image.close()
        }
        assertTrue(spread < 5, "stripes deviated by $spread")
    }

    @Test
    fun aLargerTargetLeavesTheDecodedSize() = runTest {
        val image = decodeForDisplay(stripes(40)) { ImageSize(400, 80) }
        try {
            assertEquals(40, image.width)
            assertEquals(HEIGHT, image.height)
        } finally {
            image.close()
        }
    }

    private companion object {
        const val HEIGHT = 8
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
