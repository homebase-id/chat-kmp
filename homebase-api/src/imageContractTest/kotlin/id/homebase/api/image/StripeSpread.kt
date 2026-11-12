package id.homebase.api.image

import kotlin.math.sqrt
import kotlin.test.assertNotNull

// Skips the edges, where the filter's window runs off the image.
internal fun middleGreens(bytes: ByteArray): List<Int> {
    val image = assertNotNull(ImageUtils.decodeToArgb(bytes), "Output must decode")
    val row = image.height / 2
    return (image.width / 8 until image.width * 7 / 8).map { (image.pixels[row * image.width + it] shr 8) and 0xFF }
}

internal fun deviation(values: List<Int>): Double {
    val mean = values.average()
    return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
}
