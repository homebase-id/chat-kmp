package id.homebase.photobase.gallery

import id.homebase.api.client.drives.files.DescriptorContentV1
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.serialization.Base64Bytes
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.photobase.ui.toViewer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionPhotoGalleryTest {
    private val still = PayloadDescriptor(
        key = PhotobaseProtocol.ImagePayloadKey,
        contentType = "image/jpeg",
        iv = Base64Bytes(ByteArray(16) { 1 }),
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
        val photo = item(still, clip(PhotobaseProtocol.LivePhotoVideoPayloadKey, original = 1))
        assertEquals(PhotobaseProtocol.ImagePayloadKey, photo.shown.key, "the grid and the viewer show the still")
        assertEquals(PhotobaseProtocol.LivePhotoVideoPayloadKey, photo.motion?.key, "without a copy, photo_live itself plays")
    }

    @Test
    fun anUntouchedPairedVideoPlaysItsCopy() {
        val photo = item(still, clip(PhotobaseProtocol.LivePhotoVideoPayloadKey, original = 1, copy = "vcp_0001"), clip("vcp_0001", original = 0))
        assertEquals("vcp_0001", photo.motion?.key, "the untouched clip is for restore; the copy is what plays")
        assertEquals(PhotobaseProtocol.ImagePayloadKey, photo.shown.key)
    }

    @Test
    fun aPlainPhotoHasNoClip() {
        val photo = item(still)
        assertNull(photo.motion)
        assertTrue(assertIs<FullScreenOverlay.ViewMessageData>(photo.toViewer()).motionClips.isEmpty())
    }

    @Test
    fun theViewerGetsTheClipWithTheStillItPlaysOver() {
        val viewer = assertIs<FullScreenOverlay.ViewMessageData>(item(still, clip(PhotobaseProtocol.LivePhotoVideoPayloadKey, original = 0)).toViewer())
        assertEquals(PhotobaseProtocol.LivePhotoVideoPayloadKey, viewer.motionClips[PhotobaseProtocol.ImagePayloadKey]?.key)
    }
}
