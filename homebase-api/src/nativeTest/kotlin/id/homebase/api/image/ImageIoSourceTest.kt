package id.homebase.api.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * [SkiaImageSource] takes over silently when ImageIO can't read an image, and the contract tests
 * pass either way. This is what proves iOS decodes through ImageIO at all.
 */
class ImageIoSourceTest {
    private val png = ImageUtils.encodeArgbToPng(ArgbImage(IntArray(30 * 20) { 0xFF336699.toInt() }, 30, 20))

    @Test
    fun aReadableImageGoesThroughImageIo() {
        openImageSource(png).use { assertFalse(it is SkiaImageSource, "ImageIO should have read this PNG") }
    }

    @Test
    fun imageIoReportsTheSizeAndDecodesAsAsked() {
        openImageSource(png).use { source ->
            assertEquals(ImageSize(30, 20), source.displayedSize)
            source.decode(15).use { bitmap -> assertEquals(15 to 10, bitmap.width to bitmap.height) }
        }
    }
}
