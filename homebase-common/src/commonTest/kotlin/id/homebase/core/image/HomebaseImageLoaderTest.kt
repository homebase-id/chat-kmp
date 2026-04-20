package id.homebase.core.image

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.upload.EmbeddedThumb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Unit tests for HomebaseImageLoader.
 *
 * Logic is streamlined to just data fetching, so tests focus on data models and constants.
 */
class HomebaseImageLoaderTest {

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
        val remote = HomebaseImageData(
            driveId = Uuid.random(),
            fileId = Uuid.random(),
            payloadKey = "key",
            keyHeader = KeyHeader.newRandom16()
        )
        assertFalse(remote.isPending)
    }

    @Test
    fun `HomebaseImageData contentTypeHint from preview thumbnail`() {
        val data = HomebaseImageData(
            driveId = Uuid.random(),
            fileId = Uuid.random(),
            payloadKey = "key",
            keyHeader = KeyHeader.newRandom16(),
            previewThumbnail = EmbeddedThumb(
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
        val data = HomebaseImageData(
            driveId = Uuid.random(),
            fileId = Uuid.random(),
            payloadKey = "key",
            keyHeader = KeyHeader.newRandom16()
        )
        assertNull(data.contentTypeHint)
    }

    @Test
    fun `ImageSize presets are correctly defined`() {
        assertEquals(320, ImageSize.THUMB_SMALL.pixelWidth)
        assertEquals(640, ImageSize.THUMB_MEDIUM.pixelWidth)
        assertEquals(1080, ImageSize.THUMB_LARGE.pixelWidth)
        assertEquals(1600, ImageSize.THUMB_XLARGE.pixelWidth)
    }

    @Test
    fun `THUMBLESS_CONTENT_TYPES contains svg and gif`() {
        assertTrue(HomebaseImageLoader.THUMBLESS_CONTENT_TYPES.contains("image/svg+xml"))
        assertTrue(HomebaseImageLoader.THUMBLESS_CONTENT_TYPES.contains("image/gif"))
    }

    @Test
    fun `buildImageLoadFailureMessage includes drive file key size and cause for thumbs`() {
        val drive = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val file = Uuid.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val msg = buildImageLoadFailureMessage(
            kind = "thumb",
            driveId = drive,
            fileId = file,
            payloadKey = "photo",
            size = ImageSize(320, 320),
            lastModified = 1776703690500L,
            causeClass = "NullPointerException"
        )

        assertTrue(msg.startsWith("thumb load failed"), "kind should be first: $msg")
        assertTrue(msg.contains("drive=$drive"), "missing drive: $msg")
        assertTrue(msg.contains("file=$file"), "missing file: $msg")
        assertTrue(msg.contains("key=photo"), "missing payload key: $msg")
        assertTrue(msg.contains("size=320x320"), "missing size: $msg")
        assertTrue(msg.contains("lastMod=1776703690500"), "missing lastModified: $msg")
        assertTrue(msg.contains("cause=NullPointerException"), "missing cause: $msg")
    }

    @Test
    fun `buildImageLoadFailureMessage renders full size when size is null`() {
        val drive = Uuid.random()
        val file = Uuid.random()
        val msg = buildImageLoadFailureMessage(
            kind = "payload",
            driveId = drive,
            fileId = file,
            payloadKey = "photo",
            size = null,
            lastModified = null,
            causeClass = "IOException"
        )
        assertTrue(msg.contains("size=full"), "null size must render as full: $msg")
        assertTrue(msg.contains("lastMod=null"), "null lastModified must render as null: $msg")
    }
}
