package id.homebase.api.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** What only the real Android decoders can prove: see HeicOrientationAndroidTest for how to run. */
@RunWith(AndroidJUnit4::class)
class ImagePipelineAndroidTest {

    private fun heic(): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("test_images/Landscape_6.heic").use { it.readBytes() }

    @Test
    fun aRotatedHeicReportsItsDisplayedSizeWithoutDecoding() {
        assertEquals(ImageSize(600, 450), ImageUtils.getNaturalSize(heic()))
    }

    @Test
    fun aRotatedHeicComesOutUpright() = runTest {
        val image = renderImages(heic()) { listOf(ImageOutput(320, 240, 90, Int.MAX_VALUE)) }.images.single()
        val decoded = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
        assertEquals(320 to 240, decoded.width to decoded.height)
    }

    @Test
    fun aJpegSampledOnDecodeStillAverages() = runTest {
        val checkerboard = Bitmap.createBitmap(3200, 3200, Bitmap.Config.ARGB_8888)
        for (y in 0 until 3200) for (x in 0 until 3200) {
            checkerboard.setPixel(x, y, if ((x + y) % 2 == 0) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
        val jpeg = ByteArrayOutputStream().also { checkerboard.compress(Bitmap.CompressFormat.JPEG, 100, it) }.toByteArray()

        val image = renderImages(jpeg) { listOf(ImageOutput(200, 200, 100, Int.MAX_VALUE, ImageFormat.PNG)) }.images.single()

        val decoded = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
        val red = android.graphics.Color.red(decoded.getPixel(100, 100))
        assertTrue(abs(red - 128) <= 30, "Expected mid-grey, got $red")
    }

    @Test
    fun displayP3SurvivesScalingAndEncoding() = runTest {
        val p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        val source = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888, false, p3)
        Canvas(source).drawColor(android.graphics.Color.pack(185 / 255f, 71 / 255f, 66 / 255f, 1f, p3))
        val png = ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

        val image = renderImages(png) { listOf(ImageOutput(100, 100, 100, Int.MAX_VALUE)) }.images.single()

        val options = BitmapFactory.Options().apply { inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB) }
        val decoded = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, options)
        val red = android.graphics.Color.red(decoded.getPixel(50, 50))
        assertTrue(abs(red - 200) <= 6, "P3 colour lost: sRGB red $red, expected about 200")
    }
}
