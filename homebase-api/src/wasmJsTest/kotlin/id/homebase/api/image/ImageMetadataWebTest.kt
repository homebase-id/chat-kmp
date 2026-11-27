package id.homebase.api.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ImageMetadataWebTest {
    @Test
    fun aJpegsCameraSettingsAreReadOnTheWeb() {
        val exif = assertNotNull(readImageMetadata(CameraExifFixture.inJpeg(CameraExifFixture.tiff(littleEndian = true))))

        assertEquals(CameraExifFixture.MODEL, exif.cameraModel)
        assertEquals(1.8, exif.fNumber)
        assertEquals(400, exif.iso)
    }

    @Test
    fun anythingButAJpegWithExifIsNull() {
        assertNull(readImageMetadata(ByteArray(64) { it.toByte() }))
    }
}
