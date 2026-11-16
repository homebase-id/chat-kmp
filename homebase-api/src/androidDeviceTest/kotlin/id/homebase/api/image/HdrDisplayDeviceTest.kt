package id.homebase.api.image

import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** THUMBNAILS.md, "HDR": what the full-screen viewer draws from an Ultra HDR photo still carries its gain map. */
@RunWith(AndroidJUnit4::class)
class HdrDisplayDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun coilsScreenSizedDecodeKeepsTheGainmap() = runBlocking {
        assumeTrue("gain maps need Android 14", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val result = ImageLoader(context).execute(ImageRequest.Builder(context).data(ultraHdrJpeg(4000, 3000)).size(1080, 2400).build())
        val bitmap = ((result as SuccessResult).image as BitmapImage).bitmap
        Log.i("HdrDisplay", "coil: ${bitmap.width}x${bitmap.height} ${bitmap.config} gainmap=${bitmap.hasGainmap()}")
        assertTrue(bitmap.hasGainmap(), "Coil's bitmap has the gain map")
    }

    /** Not a check: with `-e hdrFixture true`, puts a generated Ultra HDR photo in the gallery to open in the app. */
    @Test
    fun putAnUltraHdrPhotoInTheGallery() {
        if (InstrumentationRegistry.getArguments().getString("hdrFixture") != "true") return
        assumeTrue("gain maps need Android 14", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val width = InstrumentationRegistry.getArguments().getString("hdrWidth")?.toInt() ?: 3000
        val height = InstrumentationRegistry.getArguments().getString("hdrHeight")?.toInt() ?: 2000
        val base = android.graphics.Bitmap.createBitmap(IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            (0xFF shl 24) or ((x * 255 / width) shl 16) or ((y * 255 / height) shl 8) or 0x60
        }, width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val map = android.graphics.Bitmap.createBitmap(IntArray(width / 4 * (height / 4)) { i ->
            val bright = if ((i % (width / 4)) > width / 8) 0xFF else 0x20
            (0xFF shl 24) or (bright shl 16) or (bright shl 8) or bright
        }, width / 4, height / 4, android.graphics.Bitmap.Config.ARGB_8888)
        base.gainmap = android.graphics.Gainmap(map).apply { setRatioMax(4f, 4f, 4f); setDisplayRatioForFullHdr(4f) }
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "hdr_test_${System.currentTimeMillis()}.jpg")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HomebaseTest")
        }
        val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { base.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
        Log.i("HdrDisplay", "gallery: $uri")
    }

    @Test
    fun aZoomTileKeepsTheGainmap() {
        assumeTrue("gain maps need Android 14", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val bytes = ultraHdrJpeg(4000, 3000)
        val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        val tile = decoder.decodeRegion(Rect(1000, 1000, 2024, 2024), BitmapFactory.Options().apply { inSampleSize = 2 })
        Log.i("HdrDisplay", "tile: ${tile.width}x${tile.height} ${tile.config} gainmap=${tile.hasGainmap()}")
        val display = context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
        Log.i("HdrDisplay", "display: hdr types ${display?.hdrCapabilities?.supportedHdrTypes?.toList()} hdr/sdr ratio available ${display?.isHdrSdrRatioAvailable} ratio ${display?.hdrSdrRatio}")
        assertTrue(tile.hasGainmap(), "the tile has the gain map")
    }
}
