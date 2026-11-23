package id.homebase.photobase.gallery

import id.homebase.api.client.drives.files.DescriptorContentV1
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.serialization.Base64Bytes
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.photobase.ui.MotionPlayback
import id.homebase.photobase.ui.motionClip
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionPhotoGalleryTest {
    private val still = PayloadDescriptor(
        key = PhotobaseProtocol.ImagePayloadKey,
        contentType = "image/jpeg",
        iv = Base64Bytes(ByteArray(16) { 1 }),
        thumbnails = listOf(ThumbnailDescriptor(pixelWidth = 320, pixelHeight = 240), ThumbnailDescriptor(pixelWidth = 2048, pixelHeight = 1536)),
    )

    private fun clip(key: String, original: Int, copy: String? = null) = PayloadDescriptor(
        key = key,
        contentType = "video/mp4",
        iv = Base64Bytes(ByteArray(16) { 7 }),
        descriptorContent = DescriptorContentV1(
            kind = DescriptorContentV1.Kind.VIDEO,
            original = original,
            video = copy?.let { DescriptorContentV1.Video(playableCopyKey = it) },
        ).encode(),
    )

    private fun item(vararg payloads: PayloadDescriptor): PhotoGalleryItem =
        galleryTestFile(1000).let { it.copy(fileMetadata = it.fileMetadata.copy(payloads = payloads.toList())) }.toGalleryItem()!!

    @Test
    fun aLiveOrMotionPhotoShowsItsStillAndPlaysItsClip() {
        val photo = item(still, clip(PhotobaseProtocol.LivePhotoVideoPayloadKey, original = 0))
        assertEquals(PhotobaseProtocol.ImagePayloadKey, photo.shown.key, "the grid and the viewer show the still")
        assertEquals(PhotobaseProtocol.LivePhotoVideoPayloadKey, photo.motion?.key)
    }

    @Test
    fun anUntouchedPairedVideoPlaysItsCopy() {
        val photo = item(still, clip(PhotobaseProtocol.LivePhotoVideoPayloadKey, original = 1, copy = "vcp_0001"), clip("vcp_0001", original = 0))
        assertEquals("vcp_0001", photo.motion?.key, "the untouched clip is for restore; the copy is what plays")
        assertEquals(PhotobaseProtocol.ImagePayloadKey, photo.shown.key)
    }

    @Test
    fun anOlderLivePhotoPlaysItsUntouchedClip() {
        val photo = item(still, clip(PhotobaseProtocol.LivePhotoVideoPayloadKey, original = 1))
        assertEquals(PhotobaseProtocol.LivePhotoVideoPayloadKey, photo.motion?.key, "backed up before copies existed")
    }

    @Test
    fun aPlainPhotoHasNoClip() {
        val photo = item(still)
        assertNull(photo.motion)
        assertNull(photo.motionClip())
    }

    @Test
    fun theClipIsDecryptedWithItsOwnIv() {
        val player = assertNotNull(item(still, clip(PhotobaseProtocol.LivePhotoVideoPayloadKey, original = 0)).motionClip()).player
        assertEquals(PhotobaseProtocol.LivePhotoVideoPayloadKey, player.payloadKey)
        assertContentEquals(ByteArray(16) { 7 }, player.keyHeader.iv)
    }

    @Test
    fun theClipFillsTheStillsFrame() {
        val live = clip(PhotobaseProtocol.LivePhotoVideoPayloadKey, original = 0)
        assertEquals(4f / 3f, item(still, live).motionClip()?.aspectRatio)
        assertNull(item(still.copy(thumbnails = null), live).motionClip(), "without a size to go by, no clip is laid over it")
    }

    @Test
    fun aClipShowsFromItsFirstFrameAndHandsBackOnce() {
        val playback = MotionPlayback(play = true)
        assertTrue(playback.playing)
        assertFalse(playback.visible, "the still shows while the clip loads")
        playback.onFirstFrame()
        assertTrue(playback.visible)
        playback.stop()
        assertFalse(playback.playing || playback.visible)
        playback.onFirstFrame()
        assertFalse(playback.visible, "a late first frame after a touch doesn't bring it back")
        assertFalse(MotionPlayback(play = false).playing, "a neighbouring page doesn't play")
    }
}
