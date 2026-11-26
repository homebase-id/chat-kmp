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
import org.jetbrains.skia.Bitmap
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

    private fun sourceResult(bytes: ByteArray, mimeType: String? = null) =
        SourceFetchResult(ImageSource(Buffer().write(bytes), FileSystem.SYSTEM), mimeType, DataSource.MEMORY)

    private suspend fun decode(bytes: ByteArray, options: Options = shrink): DecodeResult =
        assertNotNull(assertNotNull(factory.create(sourceResult(bytes), options, imageLoader)).decode())

    private val DecodeResult.bitmap: Bitmap get() = (image as BitmapImage).bitmap

    private fun stripesPng(width: Int, height: Int): ByteArray {
        val surface = Surface.makeRasterN32Premul(width, height)
        surface.canvas.clear(Color.WHITE)
        val black = Paint().apply { color = Color.BLACK }
        for (x in 0 until width step 2) surface.canvas.drawRect(Rect.makeXYWH(x.toFloat(), 0f, 1f, height.toFloat()), black)
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    // 0 is flat grey; about 127 is stripes at full contrast.
    private fun stripeSpread(bitmap: Bitmap): Double {
        val y = bitmap.height / 2
        val row = (4 until bitmap.width - 4).map { Color.getR(bitmap.getColor(it, y)).toDouble() }
        val mean = row.average()
        return sqrt(row.sumOf { (it - mean) * (it - mean) } / row.size)
    }

    @Test
    fun `one-pixel stripes shrink to grey`() = runTest {
        val result = decode(stripesPng(788, 8))

        assertEquals(400, result.bitmap.width)
        assertTrue(result.isSampled)
        val spread = stripeSpread(result.bitmap)
        assertTrue(spread < 10, "stripes came through with spread $spread")
    }

    @Test
    fun `Coil's own decoder lets the stripes through, which is why ours is registered`() = runTest {
        val result = assertNotNull(SkiaImageDecoder(sourceResult(stripesPng(788, 8)).source, shrink).decode())

        val spread = stripeSpread(result.bitmap)
        assertTrue(spread > 50, "Coil's decoder now filters (spread $spread): this decoder may no longer be needed")
    }

    @Test
    fun `an image at its own size keeps its pixels`() = runTest {
        val noUpscale = Options(PlatformContext.INSTANCE, size = Size(400, 400), precision = Precision.INEXACT)
        val result = decode(stripesPng(40, 8), noUpscale)

        assertEquals(40, result.bitmap.width)
        assertEquals(false, result.isSampled)
        assertEquals(Color.BLACK, result.bitmap.getColor(0, 4))
        assertEquals(Color.WHITE, result.bitmap.getColor(1, 4))
    }

    @Test
    fun `a sliver keeps at least one pixel on its short edge`() = runTest {
        val box = Options(PlatformContext.INSTANCE, size = Size(100, 100), precision = Precision.INEXACT)
        val bitmap = decode(stripesPng(4000, 10), box).bitmap

        assertEquals(100, bitmap.width)
        assertEquals(1, bitmap.height)
    }

    @Test
    fun `an exact-size request enlarges`() = runTest {
        assertEquals(400, decode(stripesPng(40, 8)).bitmap.width)
    }

    @Test
    fun `takes HEIC, which the pipeline decodes`() {
        assertNotNull(factory.create(sourceResult(ftypHeader("heic")), shrink, imageLoader))
        assertNotNull(factory.create(sourceResult(ftypHeader("mif1")), shrink, imageLoader))
        assertNotNull(factory.create(sourceResult(ByteArray(16), mimeType = "image/heif"), shrink, imageLoader))
    }

    @Test
    fun `takes a mif1 file that also lists avif, which the pipeline converts as HEIC`() {
        val header = byteArrayOf(0, 0, 0, 20) + "ftypmif1".encodeToByteArray() + ByteArray(4) + "avif".encodeToByteArray()
        assertNotNull(factory.create(sourceResult(header), shrink, imageLoader))
    }

    @Test
    fun `leaves SVG, video and empty sources to other decoders`() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"/>""".encodeToByteArray()
        assertNull(factory.create(sourceResult(svg), shrink, imageLoader))
        assertNull(factory.create(sourceResult(ftypHeader("mp41")), shrink, imageLoader))
        assertNull(factory.create(sourceResult(ByteArray(0)), shrink, imageLoader))
    }
}
