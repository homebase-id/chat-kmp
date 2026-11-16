package id.homebase.api.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Gainmap
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import id.homebase.api.file.contentTypeFromBytes
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** An Ultra HDR JPEG as Android 14's camera writes one: an SDR image and a quarter-size gain map. */
internal fun ultraHdrJpeg(width: Int, height: Int): ByteArray {
    val base = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF6699CC.toInt()) }
    val map = Bitmap.createBitmap(width / 4, height / 4, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF808080.toInt()) }
    base.gainmap = Gainmap(map).apply { setRatioMax(4f, 4f, 4f); setDisplayRatioForFullHdr(4f) }
    return ByteArrayOutputStream().also { base.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
}

/** THUMBNAILS.md, "HDR". */
@RunWith(AndroidJUnit4::class)
class GainmapDeviceTest {

    private fun hasGainmap(bytes: ByteArray): Boolean =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.hasGainmap() == true || bytes.decodeToString().contains("hdrgm")

    @Test
    fun atHdAndOriginalTheImagePeopleLookAtKeepsTheGainmapAndNothingElseDoes() = runTest {
        assumeTrue("gain maps need Android 14", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        // Smaller than every final image, so the final is encoded at the source's own size; and one that is resized.
        for ((width, height) in listOf(1600 to 1200, 4000 to 3000)) {
            val source = ultraHdrJpeg(width, height)
            assertTrue(hasGainmap(source), "the fixture is Ultra HDR")
            for (setting in listOf(MediaQuality.STANDARD, MediaQuality.HD, MediaQuality.ORIGINAL)) {
                val images = createMediaImages(source, "k", setting)
                // The final image, or at Original, where viewers never step up to the file, the largest thumbnail.
                val viewed = images.finalImage?.bytes ?: images.thumbnails.maxBy { it.pixelWidth }.thumbnailBytes
                val label = "${width}x$height $setting"
                val decoded = assertNotNull(BitmapFactory.decodeByteArray(viewed, 0, viewed.size))
                if (setting == MediaQuality.STANDARD) {
                    // Standard is for small and fast: everything stays SDR WebP.
                    assertFalse(decoded.hasGainmap(), "$label: Standard stays SDR")
                    assertEquals("image/webp", contentTypeFromBytes(viewed), label)
                    continue
                }
                assertTrue(decoded.hasGainmap(), "$label: the viewed image keeps the gain map")
                assertEquals("image/jpeg", contentTypeFromBytes(viewed), label)
                // Scaled with the image: a quarter of its size, as the fixture's is.
                assertEquals(decoded.width / 4, decoded.gainmap!!.gainmapContents.width, "$label: gain map width")
                val others = images.thumbnails.map { it.thumbnailBytes }.filter { it !== viewed }
                for (bytes in others) assertFalse(hasGainmap(bytes), "$label: a smaller thumbnail stays SDR WebP")
            }
        }
    }

    @Test
    fun scrubbingAnUltraHdrJpegKeepsItsGainmap() {
        assumeTrue("gain maps need Android 14", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val source = ultraHdrJpeg(1600, 1200)
        assertTrue(hasGainmap(source), "the fixture is Ultra HDR")
        val scrubbed = ImageMetadataScrubber.scrub(source)
        assertTrue(BitmapFactory.decodeByteArray(scrubbed, 0, scrubbed.size)!!.hasGainmap())
    }

    private fun ByteArray.indexOfSub(needle: ByteArray): Int =
        (0..size - needle.size).first { at -> needle.indices.all { this[at + it] == needle[it] } }

    private fun segmentAt(bytes: ByteArray, payload: ByteArray): IntRange {
        val start = bytes.indexOfSub(payload) - 4
        val length = ((bytes[start + 2].toInt() and 0xFF) shl 8) or (bytes[start + 3].toInt() and 0xFF)
        return start until start + 2 + length
    }

    private fun u32(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun putU32(b: ByteArray, at: Int, v: Int) {
        for (k in 0 until 4) b[at + k] = (v ushr (24 - 8 * k)).toByte()
    }

    private val xmpHeader = "http://ns.adobe.com/xap/1.0/".encodeToByteArray()
    private val mpfHeader = "MPF".encodeToByteArray() + byteArrayOf(0)

    // The XMP's directory gains a motion photo's video, which the scrubber leaves out, so only MPF points at the gain map.
    private fun withMotionPhoto(source: ByteArray): ByteArray {
        val xmp = segmentAt(source, xmpHeader)
        val payload = source.copyOfRange(xmp.first + 4, xmp.last + 1).decodeToString().replace(
            "</rdf:Seq>",
            "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"MotionPhoto\" Item:Mime=\"video/mp4\" Item:Length=\"0\"/></rdf:li></rdf:Seq>",
        ).encodeToByteArray()
        val length = payload.size + 2
        return source.copyOfRange(0, xmp.first) + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload +
            source.copyOfRange(xmp.last + 1, source.size)
    }

    @Test
    fun aMotionPhotoDirectoryIsLeftOutAndTheGainmapIsFoundThroughMpf() {
        assumeTrue("gain maps need Android 14", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val scrubbed = ImageMetadataScrubber.scrub(withMotionPhoto(ultraHdrJpeg(1600, 1200)))
        assertFalse("Container:Directory" in scrubbed.copyOfRange(0, scrubbed.indexOfSub(mpfHeader)).decodeToString())
        assertTrue(BitmapFactory.decodeByteArray(scrubbed, 0, scrubbed.size)!!.hasGainmap())
    }

    @Test
    fun anMpfIndexBeforeTheXmpStillFindsTheGainmapAfterScrubbing() {
        assumeTrue("gain maps need Android 14", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val source = withMotionPhoto(ultraHdrJpeg(1600, 1200))
        val xmp = segmentAt(source, xmpHeader)
        val mpf = segmentAt(source, mpfHeader)
        assertTrue(xmp.first < mpf.first, "Android writes the XMP first")
        // Move the index in front of the XMP; the gain map is then as much further from it as the index moved.
        val moved = source.copyOfRange(mpf.first, mpf.last + 1)
        val tiff = 8
        val ifd = tiff + u32(moved, tiff + 4)
        val entries = (0 until (((moved[ifd].toInt() and 0xFF) shl 8) or (moved[ifd + 1].toInt() and 0xFF))).map { ifd + 2 + it * 12 }
            .first { ((moved[it].toInt() and 0xFF) shl 8) or (moved[it + 1].toInt() and 0xFF) == 0xB002 }
        val first = tiff + u32(moved, entries + 8)
        putU32(moved, first + 16 + 8, u32(moved, first + 16 + 8) + (mpf.first - xmp.first))
        val reordered = source.copyOfRange(0, xmp.first) + moved + source.copyOfRange(xmp.first, mpf.first) + source.copyOfRange(mpf.last + 1, source.size)
        assertTrue(BitmapFactory.decodeByteArray(reordered, 0, reordered.size)!!.hasGainmap(), "the reordered file is still Ultra HDR")

        val scrubbed = ImageMetadataScrubber.scrub(reordered)
        assertTrue(scrubbed.size < reordered.size, "the XMP shrank after the index")
        assertTrue(BitmapFactory.decodeByteArray(scrubbed, 0, scrubbed.size)!!.hasGainmap())
    }
}
