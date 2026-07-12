package id.homebase.core.clipboard

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the pure mime-selection core the Android [readClipboardImage] actual delegates to.
 * Kept stdlib-typed (no ClipData/ContentResolver) since this module has no Robolectric/mockito
 * to fake the Android SDK stubs the androidHostTest classpath ships instead.
 */
class ClipboardImageReaderAndroidTest {

    @Test
    fun imageMimeReturnsBytesUnmodified() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        val result = selectImageBytes(listOf("image/png")) { png }
        assertTrue(result != null && result.contentEquals(png))
    }

    @Test
    fun nonImageMimeReturnsNullWithoutReading() {
        var readBytesCalled = false
        val result = selectImageBytes(listOf("text/plain")) {
            readBytesCalled = true
            byteArrayOf(1, 2, 3)
        }
        assertNull(result)
        assertTrue(!readBytesCalled)
    }

    @Test
    fun imageMimeWithEmptyBytesReturnsNull() {
        val result = selectImageBytes(listOf("image/png")) { byteArrayOf() }
        assertNull(result)
    }
}
