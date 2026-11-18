package id.homebase.core.ui.screens.profile

import id.homebase.core.contains
import id.homebase.core.ui.screens.contactbook.contactPhotoToSend
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Contact, profile and card photos go out without the picked photo's EXIF (UPLOADS.md section 5).
class PhotoMetadataStripTest {

    private val gpsMarker = "GPS-7749".encodeToByteArray()

    private fun image(format: String): ByteArray {
        val img = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 64) for (y in 0 until 48) img.setRGB(x, y, (x * 4) shl 16 or (y * 5))
        return ByteArrayOutputStream().also { ImageIO.write(img, format, it) }.toByteArray()
    }

    /** A real JPEG with an EXIF segment carrying a GPS-like marker, right after SOI. */
    private fun geotaggedJpeg(): ByteArray {
        val jpeg = image("jpg")
        val tiff = byteArrayOf(0x49, 0x49, 0x2A, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val payload = "Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff + gpsMarker
        val len = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte()) + payload
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    @Test
    fun aContactPhotoGoesOutWithoutItsExif() = runTest {
        val picked = geotaggedJpeg()
        assertTrue(picked.contains(gpsMarker))

        val (sent, type) = contactPhotoToSend(picked, "image/jpeg")

        assertEquals("image/jpeg", type)
        assertFalse(sent.contains(gpsMarker))
    }

    @Test
    fun aContactPhotoWithANonImageTypeIsStillTypedAndStripped() = runTest {
        val (sent, type) = contactPhotoToSend(geotaggedJpeg(), "binary/octet-stream")

        assertEquals("image/jpeg", type)
        assertFalse(sent.contains(gpsMarker))
    }

    @Test
    fun aContactPhotoWithNoTypeIsTypedFromItsBytes() = runTest {
        assertEquals("image/png", contactPhotoToSend(image("png"), "application/octet-stream").second)
    }

    @Test
    fun aProfilePhotoGoesOutWithoutItsExifWhateverItIsEncodedAs() = runTest {
        val photo = avatarPhoto(geotaggedJpeg())

        assertFalse(photo.content.contains(gpsMarker))
        assertFalse(photo.thumbnails.single().thumbnailBytes.contains(gpsMarker))
        assertFalse(photo.tiny.bytes.contains(gpsMarker))
    }
}
