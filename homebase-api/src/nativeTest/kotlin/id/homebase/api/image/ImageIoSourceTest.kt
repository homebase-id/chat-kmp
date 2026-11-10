package id.homebase.api.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * [SkiaImageSource] takes over silently when ImageIO can't read an image, and the contract tests
 * pass either way. This is what proves iOS decodes through ImageIO at all.
 */
class ImageIoSourceTest {
    private val png = ImageUtils.encodeArgbToPng(ArgbImage(IntArray(300 * 200) { 0xFF336699.toInt() }, 300, 200))

    @Test
    fun aReadableImageGoesThroughImageIo() {
        openImageSource(png).use { assertFalse(it is SkiaImageSource, "ImageIO should have read this PNG") }
    }

    @Test
    fun imageIoReportsTheSizeAndOnlyDoesTheCoarseScaling() {
        openImageSource(png).use { source ->
            assertEquals(ImageSize(300, 200), source.displayedSize)
            source.decode(15).use { bitmap -> assertEquals(30 to 20, bitmap.width to bitmap.height) }
        }
    }
}
