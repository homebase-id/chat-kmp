package id.homebase.core.image

import coil3.BitmapImage
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.decode.DecodeResult
import coil3.decode.ImageSource
import coil3.decode.SkiaImageDecoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Precision
import coil3.size.Size
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisplayImageDecoderTest {

    private val factory = DisplayImageDecoder.Factory()
    private val imageLoader = ImageLoader.Builder(PlatformContext.INSTANCE).build()

    // 788 -> 400 is 1.97x, the ratio a 12 MP photo's 2048 px image makes from its source.
    private val shrink = Options(PlatformContext.INSTANCE, size = Size(400, 400))

    private fun sourceResult(bytes: ByteArray) =
        SourceFetchResult(ImageSource(Buffer().write(bytes), FileSystem.SYSTEM), null, DataSource.MEMORY)

    private fun stripesPng(width: Int, height: Int): ByteArray {
        val surface = Surface.makeRasterN32Premul(width, height)
        surface.canvas.clear(Color.WHITE)
        val black = Paint().apply { color = Color.BLACK }
        for (x in 0 until width step 2) surface.canvas.drawRect(Rect.makeXYWH(x.toFloat(), 0f, 1f, height.toFloat()), black)
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    // 0 is flat grey; about 127 is stripes at full contrast.
    private fun stripeSpread(result: DecodeResult): Double {
        val bitmap = (result.image as BitmapImage).bitmap
        val y = bitmap.height / 2
        val row = (4 until bitmap.width - 4).map { Color.getR(bitmap.getColor(it, y)).toDouble() }
        val mean = row.average()
        return sqrt(row.sumOf { (it - mean) * (it - mean) } / row.size)
    }

    @Test
    fun `one-pixel stripes shrink to grey`() = runTest {
        val result = assertNotNull(assertNotNull(factory.create(sourceResult(stripesPng(788, 8)), shrink, imageLoader)).decode())

        assertEquals(400, (result.image as BitmapImage).bitmap.width)
        assertTrue(result.isSampled)
        val spread = stripeSpread(result)
        assertTrue(spread < 10, "stripes came through with spread $spread")
    }

    @Test
    fun `Coil's own decoder lets the stripes through, which is why ours is registered`() = runTest {
        val spread = stripeSpread(assertNotNull(SkiaImageDecoder(sourceResult(stripesPng(788, 8)).source, shrink).decode()))

        assertTrue(spread > 50, "Coil's decoder now filters (spread $spread): this decoder may no longer be needed")
    }

    @Test
    fun `an image at its own size keeps its pixels`() = runTest {
        val png = stripesPng(40, 8)
        val noUpscale = Options(PlatformContext.INSTANCE, size = Size(400, 400), precision = Precision.INEXACT)
        val result = assertNotNull(assertNotNull(factory.create(sourceResult(png), noUpscale, imageLoader)).decode())

        val bitmap = (result.image as BitmapImage).bitmap
        assertEquals(40, bitmap.width)
        assertEquals(Color.BLACK, bitmap.getColor(0, 4))
        assertEquals(Color.WHITE, bitmap.getColor(1, 4))
    }

    @Test
    fun `leaves SVG to the SVG decoder`() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"/>""".encodeToByteArray()
        assertNull(factory.create(sourceResult(svg), shrink, imageLoader))
    }

    @Test
    fun `leaves HEIC to the HEIC decoder`() {
        val heic = byteArrayOf(0, 0, 0, 0x18) + "ftypheic".encodeToByteArray() + ByteArray(20)
        assertNull(factory.create(sourceResult(heic), shrink, imageLoader))
    }
}
