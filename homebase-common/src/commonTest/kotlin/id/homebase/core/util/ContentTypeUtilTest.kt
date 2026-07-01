package id.homebase.core.util

import kotlin.test.Test
import kotlin.test.assertEquals

class ContentTypeUtilTest {

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

    @Test
    fun returnsOctetStreamWhenNothingIsResolvable() {
        assertEquals(
            "application/octet-stream",
            resolveContentType(fileName = "IMG_1234", platformMimeType = null),
        )
    }
}
