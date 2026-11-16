package id.homebase.api.image

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Gainmap
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import java.io.File
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

    /**
     * Not a check: with `-e hdrBench true`, each photo in the test app's files/bench re-encoded as the final image at
     * Standard and HD, as today's SDR WebP and as an Ultra HDR JPEG, timed twice each in alternating order.
     */
    @Test
    fun benchmarkHdrFinalImages() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("hdrBench") != "true") return@runBlocking
        assumeTrue("gain maps need Android 14", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val photos = File(context.filesDir, "bench").listFiles { f -> f.extension.lowercase() == "jpg" }.orEmpty().sortedBy { it.name }
        for ((n, photo) in photos.withIndex()) {
            val bytes = photo.readBytes()
            for (setting in listOf(MediaQuality.STANDARD, MediaQuality.HD)) {
                repeat(2) { round ->
                    for (hdr in if (round == 0) listOf(false, true) else listOf(true, false)) {
                        val started = System.nanoTime()
                        val image = renderImages(bytes) { size ->
                            val final = planMediaImages(size, setting).finalImage!!
                            listOf(ImageOutput(final.width, final.height, final.quality, Int.MAX_VALUE, keepsHdr = hdr))
                        }.images.single()
                        val ms = (System.nanoTime() - started) / 1_000_000
                        Log.i("HdrBench", "${Build.MODEL} photo ${n + 1} ${bytes.size / 1000} kB -> $setting ${image.width}x${image.height} " +
                            "${image.format} q${image.quality}: ${image.bytes.size / 1000} kB, $ms ms")
                    }
                }
            }
        }
    }

    /** Not a check: with `-e hdrFixture true`, puts a generated Ultra HDR photo in the gallery to open in the app. */
    @Test
    fun putAnUltraHdrPhotoInTheGallery() {
        if (InstrumentationRegistry.getArguments().getString("hdrFixture") != "true") return
        assumeTrue("gain maps need Android 14", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val width = InstrumentationRegistry.getArguments().getString("hdrWidth")?.toInt() ?: 3000
        val height = InstrumentationRegistry.getArguments().getString("hdrHeight")?.toInt() ?: 2000
        val base = Bitmap.createBitmap(IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            (0xFF shl 24) or ((x * 255 / width) shl 16) or ((y * 255 / height) shl 8) or 0x60
        }, width, height, Bitmap.Config.ARGB_8888)
        val map = Bitmap.createBitmap(IntArray(width / 4 * (height / 4)) { i ->
            val bright = if ((i % (width / 4)) > width / 8) 0xFF else 0x20
            (0xFF shl 24) or (bright shl 16) or (bright shl 8) or bright
        }, width / 4, height / 4, Bitmap.Config.ARGB_8888)
        base.gainmap = Gainmap(map).apply { setRatioMax(4f, 4f, 4f); setDisplayRatioForFullHdr(4f) }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "hdr_test_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HomebaseTest")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { base.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        Log.i("HdrDisplay", "gallery: $uri")
    }

    @Test
    fun aZoomTileKeepsTheGainmap() {
        assumeTrue("gain maps need Android 14", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val bytes = ultraHdrJpeg(4000, 3000)
        val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        val tile = decoder.decodeRegion(Rect(1000, 1000, 2024, 2024), BitmapFactory.Options().apply { inSampleSize = 2 })
        Log.i("HdrDisplay", "tile: ${tile.width}x${tile.height} ${tile.config} gainmap=${tile.hasGainmap()}")
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        Log.i("HdrDisplay", "display: hdr types ${display?.hdrCapabilities?.supportedHdrTypes?.toList()} hdr/sdr ratio available ${display?.isHdrSdrRatioAvailable} ratio ${display?.hdrSdrRatio}")
        assertTrue(tile.hasGainmap(), "the tile has the gain map")
    }
}
