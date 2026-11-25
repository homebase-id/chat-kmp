package id.homebase.photobase.social

import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.photobase.gallery.galleryTestFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PhotoCommentTest {
    private val photoId = Uuid.random()
    private val commentId = Uuid.random()

    private fun comment(content: String? = OdinSystemSerializer.serialize(PhotoCommentContent(1, "Nice light")), groupId: Uuid? = photoId) =
        galleryTestFile(5).let { file ->
            file.copy(
                fileMetadata = file.fileMetadata.copy(
                    appData = file.fileMetadata.appData.copy(
                        fileType = PhotobaseProtocol.CommentFileType,
                        uniqueId = commentId,
                        groupId = groupId,
                        content = content,
                    ),
                ),
            )
        }

    @Test
    fun aCommentPointsAtItsPhotoByGroupId() {
        val read = comment().toPhotoComment()!!
        assertEquals(commentId, read.id)
        assertEquals(photoId, read.photoId)
        assertEquals("Nice light", read.body)
        assertTrue(comment().isCommentOn(photoId))
        assertFalse(comment().isCommentOn(Uuid.random()))
    }

    @Test
    fun aPhotoOrACommentWithNoPhotoIsNoComment() {
        assertNull(galleryTestFile(5).toPhotoComment())
        assertFalse(galleryTestFile(5).isCommentOn(photoId))
        assertNull(comment(groupId = null).toPhotoComment())
    }

    @Test
    fun unreadableContentStillShowsTheComment() {
        assertEquals("", comment(content = "not json").toPhotoComment()!!.body)
    }
}
