package id.homebase.photobase.ui

import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.common.OdinId
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.photobase.gallery.galleryTestFile
import id.homebase.photobase.gallery.toGalleryItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class SharedPhotoTest {
    private val owner = OdinId("frodo.test")

    @Test
    fun aSharedVideoPlaysFromItsOwnersDrive() {
        val globalTransitId = Uuid.random()
        val file = galleryTestFile(1, payload = PayloadDescriptor(key = PhotobaseProtocol.VideoPayloadKey, contentType = "video/mp4"))
            .let { it.copy(fileMetadata = it.fileMetadata.copy(globalTransitId = globalTransitId)) }

        val player = SharedPhoto(file, file.toGalleryItem()!!, addedBy = null).player(owner)!!

        assertEquals(owner, player.remoteOdinId)
        assertEquals(globalTransitId, player.globalTransitId)
        assertEquals(PhotobaseProtocol.VideoPayloadKey, player.payloadKey)
    }

    @Test
    fun aSharedPhotoHasNoPlayer() {
        val file = galleryTestFile(1).let { it.copy(fileMetadata = it.fileMetadata.copy(globalTransitId = Uuid.random())) }

        assertNull(SharedPhoto(file, file.toGalleryItem()!!, addedBy = null).player(owner))
    }
}
