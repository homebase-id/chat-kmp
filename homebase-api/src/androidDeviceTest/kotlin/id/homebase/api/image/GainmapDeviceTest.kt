package id.homebase.api.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Gainmap
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THUMBNAILS.md, "HDR": every image the pipeline encodes from an Ultra HDR photo is WebP with no gain map. A source
 * kept as its own final image is the file itself, so it keeps its gain map; that choice is made at upload.
 */
@RunWith(AndroidJUnit4::class)
class GainmapDeviceTest {

    // Android 14 is where Bitmap carries a gain map and JPEG encoding writes one (Ultra HDR).
    private fun ultraHdrJpeg(width: Int, height: Int): ByteArray {
        val base = Bitmap.createBitmap(IntArray(width * height) { i -> (0xFF shl 24) or ((i % width) * 255 / width shl 16) or ((i / width) * 255 / height shl 8) or 80 }, width, height, Bitmap.Config.ARGB_8888)
        val map = Bitmap.createBitmap(IntArray((width / 4) * (height / 4)) { 0xFF808080.toInt() }, width / 4, height / 4, Bitmap.Config.ARGB_8888)
        base.gainmap = Gainmap(map).apply { setRatioMax(4f, 4f, 4f); setDisplayRatioForFullHdr(4f) }
        return ByteArrayOutputStream().also { base.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun hasGainmap(bytes: ByteArray): Boolean =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.hasGainmap() == true || bytes.decodeToString().contains("hdrgm")

    @Test
    fun theImagesEncodedFromAnUltraHdrPhotoCarryNoGainmap() = runTest {
        assumeTrue("gain maps need Android 14", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        // Smaller than every final image, so the final is encoded at the source's own size; and one that is resized.
        for ((width, height) in listOf(1600 to 1200, 4000 to 3000)) {
            val source = ultraHdrJpeg(width, height)
            assertTrue(hasGainmap(source), "the fixture is Ultra HDR")
            for (setting in listOf(MediaQuality.STANDARD, MediaQuality.HD, MediaQuality.ORIGINAL)) {
                val images = createMediaImages(source, "k", setting)
                val outputs = images.thumbnails.map { "thumbnail ${it.pixelWidth}" to it.thumbnailBytes } +
                    listOfNotNull(images.finalImage?.let { "final image" to it.bytes })
                for ((name, bytes) in outputs) {
                    Log.i("Gainmap", "${width}x$height $setting $name: gain map ${hasGainmap(bytes)}")
                    assertFalse(hasGainmap(bytes), "${width}x$height $setting $name")
                }
            }
        }
    }

    // Chat's Original strips metadata; the gain map must survive it.
    @Test
    fun scrubbingAnUltraHdrJpegKeepsItsGainmap() {
        assumeTrue("gain maps need Android 14", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val source = ultraHdrJpeg(1600, 1200)
        assertTrue(hasGainmap(source), "the fixture is Ultra HDR")
        val scrubbed = ImageMetadataScrubber.scrub(source)
        assertTrue(BitmapFactory.decodeByteArray(scrubbed, 0, scrubbed.size)!!.hasGainmap())
    }
}
