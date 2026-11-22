package id.homebase.api.file

import kotlin.test.Test
import kotlin.test.assertEquals

class ContentTypesTest {

    // Issue #905: a gallery pick whose filename lacks a usable extension must still
    // resolve to its real image type when the platform MIME type is available,
    // instead of falling through to application/octet-stream (which renders as a file).
    @Test
    fun platformMimeTypeWinsOverExtensionlessFilename() {
        assertEquals(
            "image/jpeg",
            resolveContentType(fileName = "IMG_1234", platformMimeType = "image/jpeg"),
        )
    }

    @Test
    fun fallsBackToExtensionWhenPlatformMimeIsMissing() {
        assertEquals("image/png", resolveContentType(fileName = "photo.png", platformMimeType = null))
    }

    @Test
    fun genericOctetStreamPlatformMimeIsIgnoredInFavourOfExtension() {
        assertEquals(
            "image/png",
            resolveContentType(fileName = "photo.png", platformMimeType = "application/octet-stream"),
        )
    }

    private fun isoFile(brand: String) = byteArrayOf(0, 0, 0, 24) + "ftyp$brand".encodeToByteArray() + ByteArray(12)

    @Test
    fun aVideoTheLibraryGaveNoTypeIsTypedByItsContainersBrand() {
        assertEquals("video/quicktime", videoContentTypeFromBytes(isoFile("qt  ")))
        assertEquals("video/mp4", videoContentTypeFromBytes(isoFile("isom")))
        assertEquals("video/mp4", videoContentTypeFromBytes(isoFile("mp42")))
        assertEquals("application/octet-stream", videoContentTypeFromBytes(isoFile("heic")), "a HEIF image isn't a video")
        assertEquals("video/3gpp", videoContentTypeFromBytes(isoFile("3gp4")))
        val miaf = byteArrayOf(0, 0, 0, 24) + "ftypmiaf".encodeToByteArray() + byteArrayOf(0, 0, 0, 0) + "heicmif1".encodeToByteArray() + ByteArray(8)
        assertEquals("application/octet-stream", videoContentTypeFromBytes(miaf), "an image brand among the compatible ones")
        val oldQuickTime = byteArrayOf(0, 0, 0, 8) + "wide".encodeToByteArray() + byteArrayOf(0, 0, 0, 16) + "mdat".encodeToByteArray() + ByteArray(8)
        assertEquals("video/quicktime", videoContentTypeFromBytes(oldQuickTime), "a QuickTime file with no ftyp")
        assertEquals("application/octet-stream", videoContentTypeFromBytes("not a video at all".encodeToByteArray()))
        assertEquals("application/octet-stream", videoContentTypeFromBytes(ByteArray(4)))
    }

    @Test
    fun returnsOctetStreamWhenNothingIsResolvable() {
        assertEquals(
            "application/octet-stream",
            resolveContentType(fileName = "IMG_1234", platformMimeType = null),
        )
    }
}
