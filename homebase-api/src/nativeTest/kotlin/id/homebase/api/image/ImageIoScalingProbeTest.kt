package id.homebase.api.image

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.fail

// TEMPORARY: maps ImageIO's own stripe spread against its reduction ratio. Remove before merge.
class ImageIoScalingProbeTest {
    @Test
    fun probe() {
        val width = 8064
        val png = ImageUtils.encodeArgbToPng(ArgbImage(IntArray(width * 8) { if (it % 2 == 0) 0xFF000000.toInt() else -1 }, width, 8))
        val rows = listOf(7800, 7000, 6000, 5657, 5000, 4500, 4096, 4032, 3800, 3500, 3000, 2688, 2400, 2016, 1613, 806).map { edge ->
            openImageSource(png).use { source ->
                source.decode(edge / 2 + edge % 2).use { bitmap ->
                    val px = bitmap.readPixels()
                    val row = bitmap.height / 2
                    val greens = (bitmap.width / 8 until bitmap.width * 7 / 8).map { px[(row * bitmap.width + it) * 4 + 1].toInt() and 0xFF }
                    val mean = greens.average()
                    val sd = sqrt(greens.sumOf { (it - mean) * (it - mean) } / greens.size)
                    "${bitmap.width}(${(width * 100 / bitmap.width) / 100.0}x)=${(sd * 10).toInt() / 10.0}"
                }
            }
        }
        fail("PROBE " + rows.joinToString(" "))
    }
}
