package id.homebase.core.image

import id.homebase.api.client.drives.upload.EmbeddedThumb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Unit tests for HomebaseImageLoader cache logic.
 *
 * Note: Tests for actual API calls require integration testing with a mocked DriveFileProvider or
 * actual server.
 */
class HomebaseImageLoaderTest {

    // ==================== HomebaseImageData Tests ====================

    @Test
    fun `ImageSize comparison works correctly`() {
        val small = ImageSize(320, 320)
        val medium = ImageSize(640, 640)
        val large = ImageSize(1080, 1080)

        assertTrue(medium.isLargerOrEqualTo(small))
        assertTrue(large.isLargerOrEqualTo(medium))
        assertTrue(small.isLargerOrEqualTo(small))
        assertFalse(small.isLargerOrEqualTo(medium))
    }

    @Test
    fun `ImageSize pixel count calculated correctly`() {
        val size = ImageSize(100, 200)
        assertEquals(20000, size.pixelCount)
    }

    @Test
    fun `HomebaseImageData isPending returns false for remote files`() {
        val remote =
                HomebaseImageData(
                        driveId = Uuid.random(),
                        fileId = Uuid.random(),
                        payloadKey = "key"
                )
        assertFalse(remote.isPending)
    }

    @Test
    fun `HomebaseImageData contentTypeHint from preview thumbnail`() {
        val data =
                HomebaseImageData(
                        driveId = Uuid.random(),
                        fileId = Uuid.random(),
                        payloadKey = "key",
                        previewThumbnail =
                                EmbeddedThumb(
                                        pixelWidth = 20,
                                        pixelHeight = 20,
                                        contentType = "image/webp",
                                        content = "base64data"
                                )
                )
        assertEquals("image/webp", data.contentTypeHint)
    }

    @Test
    fun `HomebaseImageData contentTypeHint is null when no preview`() {
        val data =
                HomebaseImageData(
                        driveId = Uuid.random(),
                        fileId = Uuid.random(),
                        payloadKey = "key"
                )
        assertNull(data.contentTypeHint)
    }

    // ==================== CachedImage Tests ====================

    @Test
    fun `CachedImage equality based on content`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val cache1 = CachedImage(bytes.copyOf(), "image/png", ImageSize(100, 100))
        val cache2 = CachedImage(bytes.copyOf(), "image/png", ImageSize(100, 100))
        val cache3 = CachedImage(bytes.copyOf(), "image/jpeg", ImageSize(100, 100))

        assertEquals(cache1, cache2)
        assertFalse(cache1 == cache3)
    }

    // ==================== ImageSize Presets Tests ====================

    @Test
    fun `ImageSize presets are correctly defined`() {
        assertEquals(320, ImageSize.THUMB_SMALL.pixelWidth)
        assertEquals(640, ImageSize.THUMB_MEDIUM.pixelWidth)
        assertEquals(1080, ImageSize.THUMB_LARGE.pixelWidth)
        assertEquals(1600, ImageSize.THUMB_XLARGE.pixelWidth)
    }

    // ==================== Content Type Detection Tests ====================

    @Test
    fun `THUMBLESS_CONTENT_TYPES contains svg and gif`() {
        assertTrue(HomebaseImageLoader.THUMBLESS_CONTENT_TYPES.contains("image/svg+xml"))
        assertTrue(HomebaseImageLoader.THUMBLESS_CONTENT_TYPES.contains("image/gif"))
    }
}
