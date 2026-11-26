package id.homebase.photobase.social

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.files.reactions.GetGroupReactionCountsResponse
import id.homebase.api.client.drives.files.reactions.GroupReactionCount
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.photobase.gallery.galleryTestFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class PhotoCommentTest {
    private val photoGtid = Uuid.random()
    private val commentId = Uuid.random()
    private val frodo = OdinId("frodo.test")
    private val sam = OdinId("sam.test")

    private fun comment(content: String? = OdinSystemSerializer.serialize(PhotoCommentContent(1, "Nice light")), groupId: Uuid? = photoGtid, author: OdinId? = null) =
        galleryTestFile(5).let { file ->
            file.copy(
                fileMetadata = file.fileMetadata.copy(
                    senderOdinId = author,
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
    fun aCommentPointsAtItsPhotoByTheGlobalTransitIdItsGroupNames() {
        val read = comment().toPhotoComment(frodo)!!
        assertEquals(commentId, read.id)
        assertEquals(photoGtid, read.photoId)
        assertEquals("Nice light", read.body)
        assertNull(read.senderOdinId, "our own")
    }

    @Test
    fun aGuestsCommentNamesThem() {
        assertEquals(sam, comment(author = sam).toPhotoComment(frodo)!!.senderOdinId)
        assertNull(comment(author = frodo).toPhotoComment(frodo)!!.senderOdinId, "ours, as received back")
    }

    @Test
    fun aPhotoOrACommentWithNoPhotoIsNoComment() {
        assertNull(galleryTestFile(5).toPhotoComment(frodo))
        assertNull(comment(groupId = null).toPhotoComment(frodo))
    }

    @Test
    fun aCommentsReactionsGoOnTheCommentInTheCommentFileSystemOnThePhotosDrive() {
        val commentGtid = Uuid.random()
        val read = comment().let { it.copy(fileMetadata = it.fileMetadata.copy(globalTransitId = commentGtid)) }.toPhotoComment(frodo)!!
        val photo = PhotoTarget(owner = sam, fileId = Uuid.random(), globalTransitId = photoGtid, keyHeader = KeyHeader.newRandom16())

        val target = photo.forComment(read)!!

        assertEquals(commentGtid, read.globalTransitId)
        assertEquals(sam, target.owner, "still on the photo owner's drive")
        assertEquals(read.fileId, target.fileId)
        assertEquals(commentGtid, target.globalTransitId)
        assertEquals(FileSystemType.Comment, target.fileSystemType)
        assertNull(photo.forComment(read.copy(globalTransitId = null)), "a comment not yet sent has nowhere to react")
    }

    @Test
    fun unreadableContentStillShowsTheComment() {
        assertEquals("", comment(content = "not json").toPhotoComment(frodo)!!.body)
    }

    @Test
    fun aCommentsGroupAndReferencedFileAreBothThePhotosGlobalTransitIdAsOdinCoreAsksOfAGuest() {
        val metadata = commentMetadata(commentId, photoGtid, "Nice light", 1234, sentToOwner = true)

        assertEquals(photoGtid, metadata.appData.groupId)
        assertEquals(photoGtid, metadata.referencedFile?.globalTransitId)
        assertEquals(PhotobaseProtocol.DriveAlias, metadata.referencedFile?.targetDrive?.alias)
        assertEquals(PhotobaseProtocol.CommentFileType, metadata.appData.fileType)
        assertEquals("connected", metadata.accessControlList?.requiredSecurityGroup, "read by whoever reads the photo")
        assertEquals(true, metadata.allowDistribution, "a guest's goes to the owner")
        assertEquals(false, commentMetadata(commentId, photoGtid, "Nice light", 1234, sentToOwner = false).allowDistribution)
    }

    @Test
    fun aReactionSummaryCountsEachEmoji() {
        val heart = OdinSystemSerializer.serialize(id.homebase.api.client.drives.files.reactions.ReactionContent(emoji = "❤️"))
        val summary = GetGroupReactionCountsResponse(listOf(GroupReactionCount(heart, 2), GroupReactionCount("{}", 0)), total = 2).toSummary()

        assertEquals(mapOf(heart to 2), summary?.reactions?.mapValues { it.value.count })
        assertNull(GetGroupReactionCountsResponse(emptyList(), total = 0).toSummary())
    }
}
