package id.homebase.api.image

import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class ImagePipelineColorTest {

    /** P3 (185, 71, 66), which is about sRGB (200, 60, 60). Relabelled as sRGB, it would read 185. */
    private fun p3Png(): ByteArray {
        val surface = Surface.makeRaster(ImageInfo(400, 400, ColorType.N32, ColorAlphaType.PREMUL, ColorSpace.displayP3))
        val fill = Image.makeRaster(
            ImageInfo(1, 1, ColorType.RGBA_8888, ColorAlphaType.PREMUL, ColorSpace.displayP3),
            byteArrayOf(185.toByte(), 71, 66, 255.toByte()),
            4,
        )
        surface.canvas.drawImageRect(fill, Rect.makeWH(400f, 400f), Paint())
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    /** What the pixels look like after converting to sRGB, the way a colour-managed viewer would. */
    private fun redInSrgb(encoded: ByteArray): Int {
        val surface = Surface.makeRaster(ImageInfo(1, 1, ColorType.RGBA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB))
        surface.canvas.drawImageRect(Image.makeFromEncoded(encoded), Rect.makeWH(1f, 1f), Paint())
        val pixels = surface.makeImageSnapshot().peekPixels()!!.buffer.bytes
        return pixels[0].toInt() and 0xFF
    }

    @Test
    fun displayP3SurvivesScalingAndWebpEncoding() = runTest {
        val source = p3Png()
        assertTrue(abs(redInSrgb(source) - 200) <= 3, "Fixture should read about 200, got ${redInSrgb(source)}")

        val image = renderImages(source) { listOf(ImageOutput(100, 100, 100, Int.MAX_VALUE)) }.images.single()

        val red = redInSrgb(image.bytes)
        assertTrue(abs(red - 200) <= 5, "P3 colour lost: sRGB red $red, expected about 200")
    }

    @Test
    fun aDisplayP3TinyFitsItsLimitAndKeepsItsColour() = runTest {
        val tiny = createTinyPreview(p3Png())

        val bytes = tiny.content!!.bytes
        assertTrue(bytes.size <= tinyThumbSize.maxBytes, "Tiny was ${bytes.size} bytes")
        val red = redInSrgb(bytes)
        assertTrue(abs(red - 200) <= 8, "Should be converted, not relabelled: sRGB red $red")
    }
}
