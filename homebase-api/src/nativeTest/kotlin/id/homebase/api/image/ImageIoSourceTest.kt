package id.homebase.api.image

import id.homebase.api.file.IOSFileOperationsProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
    fun aFileOpensThroughImageIoWithoutReadingIt() = runTest {
        val ops = IOSFileOperationsProvider()
        val path = ops.writeBytesToTempFile(png, "imageio_file_", ".png")
        try {
            assertNotNull(openImageFile(path), "ImageIO should have opened the file").use { source ->
                assertFalse(source is SkiaImageSource)
                assertEquals(ImageSize(300, 200), source.displayedSize)
            }
            assertNull(openImageFile("ph://not-a-file"))
        } finally {
            ops.deleteTempFile(path)
        }
    }

    @Test
    fun imageIoReportsTheSizeAndOnlyDoesTheCoarseScaling() {
        openImageSource(png).use { source ->
            assertEquals(ImageSize(300, 200), source.displayedSize)
            source.decode(15).use { bitmap -> assertEquals(30 to 20, bitmap.width to bitmap.height) }
            source.decode(100).use { bitmap -> assertEquals(150 to 100, bitmap.width to bitmap.height) }
            source.decode(200).use { bitmap -> assertEquals(300 to 200, bitmap.width to bitmap.height) }
        }
    }
}
