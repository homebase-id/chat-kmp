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
import id.homebase.api.client.drives.commentedFileKeyHeader
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.peer.PeerDriveUploadProvider
import id.homebase.api.common.OdinId
import id.homebase.api.common.toSecureByteArray
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.photobase.PhotobaseProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.uuid.Uuid

@Serializable
data class PhotoCommentContent(val version: Int, val body: String)

/**
 * A photo comments and reactions go on: one of this identity's ([owner] null), or one [owner] shares with it. A
 * comment's reactions go on the comment, in the Comment file system ([forComment]).
 */
data class PhotoTarget(
    val owner: OdinId?,
    val fileId: Uuid,
    val globalTransitId: Uuid,
    val keyHeader: KeyHeader,
    val fileSystemType: FileSystemType = FileSystemType.Standard,
) {
    /** [comment] as a target of its own, on the same drive; null until it has a global transit id. */
    fun forComment(comment: PhotoComment): PhotoTarget? =
        comment.globalTransitId?.let { copy(fileId = comment.fileId, globalTransitId = it, fileSystemType = FileSystemType.Comment) }
}

data class PhotoComment(
    val id: Uuid,
    val fileId: Uuid,
    /** The photo's global transit id, which the comment's group names. */
    val photoId: Uuid,
    /** Null on our own comments. */
    val senderOdinId: OdinId?,
    val body: String,
    val userDateMs: Long,
    /** How the comment is known on the other identity's drive: a guest edits and deletes it by this. */
    val globalTransitId: Uuid? = null,
    val versionTag: Uuid? = null,
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
    private val peerUploads: PeerDriveUploadProvider,
    private val credentialsManager: CredentialsManager,
) {
    private val drive = PhotobaseProtocol.DriveAlias
    // A write here reads the photo's comments again; others' come at the next open.
    private val written = MutableSharedFlow<Uuid>(extraBufferCapacity = 8)
    // Per identity: another account signed in later in the run has its own.
    private val pending = HashMap<OdinId?, PendingComments>()
    private val pendingLock = Mutex()

    /** The photo's comments, oldest first, read again after each write from here. */
    fun commentsOf(photo: PhotoTarget): Flow<List<PhotoComment>> = flow {
        emit(read(photo))
        written.filter { it == photo.globalTransitId }.collect { emit(read(photo)) }
    }

    private suspend fun read(photo: PhotoTarget): List<PhotoComment> {
        val self = credentialsManager.getActiveCredentials()?.domain
        val stored = query.queryBatch(
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
        return if (photo.owner == null) stored else pendingLock.withLock { pendingOf(self).over(photo.globalTransitId, stored) }
    }

    suspend fun post(photo: PhotoTarget, body: String, commentId: Uuid = Uuid.random()) {
        val keyHeader = photo.commentKeyHeader()
        val guest = photo.owner != null
        val now = Clock.System.now().toEpochMilliseconds()
        val metadata = commentMetadata(commentId, photo.globalTransitId, body, now, sentToOwner = guest)
        val sent = uploads.uploadFile(
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
        if (guest) {
            val posted = PhotoComment(commentId, Uuid.NIL, photo.globalTransitId, senderOdinId = null, body, now, globalTransitId = sent?.globalTransitId)
            pendingLock.withLock { pendingOf(activeIdentity()).posted(posted) }
        }
        written.emit(photo.globalTransitId)
    }

    /** Rewrites one of this identity's comments, on its own photo or, over peer, on the photo's owner's drive. */
    suspend fun edit(photo: PhotoTarget, comment: PhotoComment, body: String) {
        val keyHeader = photo.commentKeyHeader()
        val owner = photo.owner
        val metadata = commentMetadata(comment.id, photo.globalTransitId, body, comment.userDateMs, sentToOwner = owner != null)
            .copy(versionTag = comment.versionTag)
            .encryptContent(keyHeader)
        if (owner != null) {
            val globalTransitId = requireNotNull(comment.globalTransitId) { "comment ${comment.id} has no global transit id" }
            uploads.updateFileOverPeerByGlobalTransitId(
                owner, drive, globalTransitId, commentedFileKeyHeader(keyHeader.iv), metadata, payloads = emptyList(),
                fileSystemType = FileSystemType.Comment,
            )
            pendingLock.withLock { pendingOf(activeIdentity()).edited(comment, body) }
        } else {
            uploads.updateFileByUniqueId(
                UpdateFileByUniqueIdRequest(
                    driveId = drive,
                    uniqueId = comment.id,
                    keyHeader = keyHeader,
                    instructions = FileUpdateInstructionSet(
                        transferIv = ByteArrayUtil.getRndByteArray(16),
                        locale = UpdateLocale.Local,
                        recipients = emptyList(),
                        manifest = UpdateManifest.build(),
                    ),
                    metadata = metadata,
                    fileSystemType = FileSystemType.Comment,
                    withCommentedFileKey = true,
                )
            )
        }
        written.emit(photo.globalTransitId)
    }

    /** Deletes one of this identity's comments: here on its own photo, or by asking the photo's owner to. */
    suspend fun delete(photo: PhotoTarget, comment: PhotoComment) {
        val owner = photo.owner
        if (owner != null) {
            val globalTransitId = requireNotNull(comment.globalTransitId) { "comment ${comment.id} has no global transit id" }
            peerUploads.sendDeleteRequest(
                owner, GlobalTransitIdFileIdentifier(globalTransitId, TargetDrive(drive, PhotobaseProtocol.DriveType)), FileSystemType.Comment,
            )
            pendingLock.withLock { pendingOf(activeIdentity()).deleted(comment) }
        } else {
            files.deleteFiles(drive, listOf(comment.fileId), fileSystemType = FileSystemType.Comment)
        }
        written.emit(photo.globalTransitId)
    }

    private fun pendingOf(identity: OdinId?) = pending.getOrPut(identity) { PendingComments() }

    private suspend fun activeIdentity(): OdinId? = credentialsManager.getActiveCredentials()?.domain

    // The photo's key under a new IV, copied because an upload may clear the key header it's given.
    private fun PhotoTarget.commentKeyHeader() = KeyHeader(ByteArrayUtil.getRndByteArray(16), keyHeader.aesKey.toByteArray().toSecureByteArray())

    private companion object {
        const val MAX_COMMENTS = 1000
    }
}

/**
 * A guest's comments on someone's photo that the owner's drive doesn't show yet: each goes there through the outbox, so
 * a read straight after doesn't have it. Each is shown as written until a read does, kept for the app's run only.
 */
internal class PendingComments {
    // Each photo's own: a read of one photo's comments says nothing of another's.
    private class OfPhoto {
        val posted = LinkedHashMap<Uuid, PhotoComment>()
        val bodies = HashMap<Uuid, String>()
        val deleted = HashSet<Uuid>()
    }

    private val photos = HashMap<Uuid, OfPhoto>()

    private fun of(comment: PhotoComment) = photos.getOrPut(comment.photoId) { OfPhoto() }

    fun posted(comment: PhotoComment) {
        of(comment).posted[comment.id] = comment
    }

    fun edited(comment: PhotoComment, body: String) = with(of(comment)) {
        if (comment.id in posted) posted[comment.id] = posted.getValue(comment.id).copy(body = body) else bodies[comment.id] = body
    }

    fun deleted(comment: PhotoComment) = with(of(comment)) {
        if (posted.remove(comment.id) == null) deleted += comment.id
        bodies -= comment.id
    }

    /** [stored], photo [photoId]'s comments as its owner's drive has them, with the writes it doesn't have yet. */
    fun over(photoId: Uuid, stored: List<PhotoComment>): List<PhotoComment> {
        val held = photos[photoId] ?: return stored
        val byId = stored.associateBy { it.id }
        held.posted.keys.removeAll { it in byId }
        held.deleted.retainAll(byId.keys)
        held.bodies.keys.removeAll { id -> byId[id]?.body.let { it == null || it == held.bodies[id] } }
        val shown = stored.filter { it.id !in held.deleted }.map { c -> held.bodies[c.id]?.let { c.copy(body = it) } ?: c }
        return (shown + held.posted.values).sortedBy { it.userDateMs }
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
        globalTransitId = fileMetadata.globalTransitId,
        versionTag = fileMetadata.versionTag,
    )
}
