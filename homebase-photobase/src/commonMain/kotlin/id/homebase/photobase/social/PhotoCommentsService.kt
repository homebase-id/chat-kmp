package id.homebase.photobase.social

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.GlobalTransitIdFileIdentifier
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.TargetDrive
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.OdinId
import id.homebase.api.common.toSecureByteArray
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.photobase.PhotobaseProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.uuid.Uuid

@Serializable
data class PhotoCommentContent(val version: Int, val body: String)

/** A photo comments and reactions go on: one of this identity's ([owner] null), or one [owner] shares with it. */
data class PhotoTarget(val owner: OdinId?, val fileId: Uuid, val globalTransitId: Uuid, val keyHeader: KeyHeader)

data class PhotoComment(
    val id: Uuid,
    val fileId: Uuid,
    /** The photo's global transit id, which the comment's group names. */
    val photoId: Uuid,
    /** Null on our own comments. */
    val senderOdinId: OdinId?,
    val body: String,
    val userDateMs: Long,
)

/**
 * Comments on photos, as the feed and Filesync have them: each is a file in the Comment file system on the photo
 * owner's drive, pointing at the photo by global transit id and encrypted with the photo's own key, so whoever reads the
 * photo reads its comments. A guest's comment goes to the owner over peer, let in through a share row with Comment.
 */
class PhotoCommentsService(
    private val query: DriveQueryProvider,
    private val uploads: DriveUploadProvider,
    private val files: DriveFileProvider,
    private val credentialsManager: CredentialsManager,
) {
    private val drive = PhotobaseProtocol.DriveAlias
    // A write here reads the photo's comments again; others' come at the next open.
    private val written = MutableSharedFlow<Uuid>(extraBufferCapacity = 8)

    /** The photo's comments, oldest first, read again after each write from here. */
    fun commentsOf(photo: PhotoTarget): Flow<List<PhotoComment>> = flow {
        emit(read(photo))
        written.filter { it == photo.globalTransitId }.collect { emit(read(photo)) }
    }

    private suspend fun read(photo: PhotoTarget): List<PhotoComment> {
        val self = credentialsManager.getActiveCredentials()?.domain
        return query.queryBatch(
            driveId = drive,
            request = QueryBatchRequest(
                queryParams = FileQueryParams(fileType = listOf(PhotobaseProtocol.CommentFileType), groupId = listOf(photo.globalTransitId)),
                resultOptionsRequest = QueryBatchResultOptionsRequest(
                    maxRecords = MAX_COMMENTS,
                    includeMetadataHeader = true,
                    ordering = QueryBatchSortOrder.OldestFirst,
                    sorting = QueryBatchSortField.UserDate,
                ),
            ),
            ownerOdinId = photo.owner,
            fileSystemType = FileSystemType.Comment,
            // A comment is encrypted with its photo's key, under its own IV.
            externalFileKey = { _, _, _, _ -> photo.keyHeader.aesKey },
        ).searchResults.filter { it.fileState == FileState.Active }.mapNotNull { it.toPhotoComment(self) }
    }

    suspend fun post(photo: PhotoTarget, body: String, commentId: Uuid = Uuid.random()) {
        // A copy: the upload may clear its key header, and this one's key is the photo's.
        val keyHeader = KeyHeader(ByteArrayUtil.getRndByteArray(16), photo.keyHeader.aesKey.toByteArray().toSecureByteArray())
        val guest = photo.owner != null
        val metadata = commentMetadata(commentId, photo.globalTransitId, body, Clock.System.now().toEpochMilliseconds(), sentToOwner = guest)
        uploads.uploadFile(
            UploadFileRequest(
                driveId = if (guest) SystemDriveConstants.transientTempDrive.alias else drive,
                keyHeader = keyHeader,
                metadata = metadata.encryptContent(keyHeader),
                transitOptions = photo.owner?.let {
                    TransitOptions(recipients = listOf(it), isTransient = true, remoteTargetDrive = TargetDrive(drive, PhotobaseProtocol.DriveType))
                },
                fileSystemType = FileSystemType.Comment,
                withCommentedFileKey = true,
            )
        )
        written.emit(photo.globalTransitId)
    }

    /** Deletes one of this identity's comments on one of its own photos. */
    suspend fun delete(photo: PhotoTarget, comment: PhotoComment) {
        check(photo.owner == null) { "a comment on ${photo.owner}'s photo is deleted on their drive" }
        files.deleteFiles(drive, listOf(comment.fileId), fileSystemType = FileSystemType.Comment)
        written.emit(photo.globalTransitId)
    }

    private companion object {
        const val MAX_COMMENTS = 1000
    }
}

/**
 * A comment's header: its group and referenced file both the photo's global transit id, which odin-core asks of a
 * member's comment. Connected, so whoever reads the photo through a share reads it too.
 */
internal fun commentMetadata(commentId: Uuid, photoGlobalTransitId: Uuid, body: String, userDate: Long, sentToOwner: Boolean) = UploadFileMetadata(
    allowDistribution = sentToOwner,
    isEncrypted = true,
    accessControlList = AccessControlList(requiredSecurityGroup = SecurityGroupType.Connected.value),
    appData = UploadAppFileMetaData(
        uniqueId = commentId,
        groupId = photoGlobalTransitId,
        fileType = PhotobaseProtocol.CommentFileType,
        userDate = userDate,
        content = OdinSystemSerializer.serialize(PhotoCommentContent(PhotobaseProtocol.CommentVersion, body)),
    ),
    referencedFile = GlobalTransitIdFileIdentifier(photoGlobalTransitId, TargetDrive(PhotobaseProtocol.DriveAlias, PhotobaseProtocol.DriveType)),
)

internal fun HomebaseFile.toPhotoComment(self: OdinId?): PhotoComment? {
    val appData = fileMetadata.appData
    if (appData.fileType != PhotobaseProtocol.CommentFileType) return null
    val photoId = appData.groupId ?: fileMetadata.referencedFile?.globalTransitId ?: return null
    val content = appData.content?.let { runCatching { OdinSystemSerializer.deserialize<PhotoCommentContent>(it) }.getOrNull() }
    val author = (fileMetadata.originalAuthor ?: fileMetadata.senderOdinId)?.takeIf { it != self }
    return PhotoComment(
        id = appData.uniqueId ?: fileId,
        fileId = fileId,
        photoId = photoId,
        senderOdinId = author,
        body = content?.body.orEmpty(),
        userDateMs = appData.userDate ?: fileMetadata.created.milliseconds,
    )
}
