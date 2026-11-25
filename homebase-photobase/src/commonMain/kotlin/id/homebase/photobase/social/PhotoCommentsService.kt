package id.homebase.photobase.social

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.client.drives.upload.SendContents
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.upload.MediaUpdateSpec
import id.homebase.upload.MediaUploadSpec
import id.homebase.upload.UploadOutcome
import id.homebase.upload.UploadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class PhotoCommentContent(val version: Int, val body: String)

data class PhotoComment(
    val id: Uuid,
    val photoId: Uuid,
    /** Null on our own comments: the server stamps a sender only on what it receives. */
    val senderOdinId: OdinId?,
    val body: String,
    val userDateMs: Long,
    /** Null until the server has the comment; it can't be edited before then. */
    val versionTag: Uuid?,
    val reactionPreview: ReactionSummary?,
    val ownReactions: List<String>,
)

/** Who a photo's comments and reactions go to, ourselves left out. */
fun interface PhotoAudience {
    suspend fun of(photoId: Uuid): List<OdinId>

    companion object {
        // Every photo is owner-only until albums can be shared.
        val OwnerOnly = PhotoAudience { emptyList() }
    }
}

/**
 * Comments on photos, as moments have them: each is its own encrypted file on the Photos drive, its groupId the
 * photo's unique id, sent to the photo's [audience].
 */
class PhotoCommentsService(
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val eventBus: EventBus,
    private val uploadService: UploadService,
    private val optimisticWriter: OptimisticWriter,
    private val scope: CoroutineScope,
    private val audience: PhotoAudience = PhotoAudience.OwnerOnly,
) {
    private val drive = PhotobaseProtocol.DriveAlias

    /** The photo's comments, oldest first, read again whenever one of them changes. */
    fun commentsOf(photoId: Uuid): Flow<List<PhotoComment>> = flow {
        emit(read(photoId))
        eventBus.events
            .filter { event ->
                event is BackendEvent.DataEvent.BatchReceived && event.driveId == drive &&
                    event.batchData.any { it.isCommentOn(photoId) }
            }
            .collect { emit(read(photoId)) }
    }

    private suspend fun read(photoId: Uuid): List<PhotoComment> {
        val identityId = credentialsManager.getActiveCredentials()?.getIdentityId() ?: return emptyList()
        return QueryBatch(identityId).queryBatchAsync(
            dbm = databaseManager,
            driveId = drive,
            noOfItems = MAX_COMMENTS,
            sortOrder = QueryBatchSortOrder.OldestFirst,
            sortField = QueryBatchSortField.UserDate,
            fileSystemType = 0,
            filetypesAnyOf = listOf(PhotobaseProtocol.CommentFileType),
            groupIdAnyOf = listOf(photoId),
        ).records.filterNot { it.isSoftDeleted() }.mapNotNull { it.toPhotoComment() }
    }

    suspend fun post(photoId: Uuid, body: String, commentId: Uuid = Uuid.random()) {
        val recipients = audience.of(photoId)
        val outcome = uploadService.upload(
            MediaUploadSpec(
                driveId = drive,
                uniqueId = commentId,
                keyHeader = KeyHeader.newRandom16(),
                bundle = null,
                metadata = metadata(commentId, photoId, body, UnixTimeUtc.now().milliseconds, recipients, versionTag = null),
                transit = recipients.takeIf { it.isNotEmpty() }?.let { TransitOptions(recipients = it, sendContents = SendContents.All) },
                originalRecipientCount = recipients.size,
                seedCache = false,
            ),
            scope = scope,
        )
        check(outcome is UploadOutcome.Enqueued) { "comment not queued: $outcome" }
    }

    suspend fun edit(commentId: Uuid, versionTag: Uuid, body: String) {
        val existing = local(commentId) ?: throw IllegalArgumentException("comment not found locally: $commentId")
        check(existing.fileMetadata.versionTag == versionTag) { "VersionTag mismatch" }
        val photoId = existing.fileMetadata.appData.groupId ?: error("comment $commentId has no photo")
        val recipients = audience.of(photoId)
        val userDate = existing.fileMetadata.appData.userDate ?: existing.fileMetadata.created.milliseconds
        val outcome = uploadService.updateFile(
            MediaUpdateSpec(
                driveId = drive,
                uniqueId = commentId,
                keyHeader = KeyHeader(iv = ByteArrayUtil.getRndByteArray(16), aesKey = existing.keyHeader.aesKey),
                metadata = metadata(commentId, photoId, body, userDate, recipients, versionTag),
                recipients = recipients,
                replace = true,
            ),
            scope = scope,
        )
        check(outcome is UploadOutcome.Enqueued) { "comment edit not queued: $outcome" }
    }

    suspend fun delete(commentId: Uuid) {
        val photoId = local(commentId)?.fileMetadata?.appData?.groupId
        optimisticWriter.deleteFile(drive, commentId, photoId?.let { audience.of(it) }?.takeIf { it.isNotEmpty() })
    }

    private suspend fun local(commentId: Uuid): HomebaseFile? {
        val identityId = credentialsManager.requireActiveCredentials().getIdentityId()
        return databaseManager.driveMainIndex.selectHomebaseFileByUnique(identityId, drive, commentId)
    }

    private fun metadata(commentId: Uuid, photoId: Uuid, body: String, userDate: Long, recipients: List<OdinId>, versionTag: Uuid?) =
        UploadFileMetadata(
            allowDistribution = recipients.isNotEmpty(),
            isEncrypted = true,
            versionTag = versionTag,
            appData = UploadAppFileMetaData(
                uniqueId = commentId,
                groupId = photoId,
                fileType = PhotobaseProtocol.CommentFileType,
                userDate = userDate,
                content = OdinSystemSerializer.serialize(PhotoCommentContent(PhotobaseProtocol.CommentVersion, body)),
            ),
        )

    private companion object {
        const val MAX_COMMENTS = 1000
    }
}

internal fun HomebaseFile.isCommentOn(photoId: Uuid): Boolean =
    fileMetadata.appData.fileType == PhotobaseProtocol.CommentFileType && fileMetadata.appData.groupId == photoId

internal fun HomebaseFile.toPhotoComment(): PhotoComment? {
    val appData = fileMetadata.appData
    if (appData.fileType != PhotobaseProtocol.CommentFileType) return null
    val content = appData.content?.let { runCatching { OdinSystemSerializer.deserialize<PhotoCommentContent>(it) }.getOrNull() }
    return PhotoComment(
        id = appData.uniqueId ?: return null,
        photoId = appData.groupId ?: return null,
        senderOdinId = fileMetadata.senderOdinId,
        body = content?.body.orEmpty(),
        userDateMs = sqlUserDateMs(),
        versionTag = fileMetadata.versionTag,
        reactionPreview = fileMetadata.reactionPreview,
        ownReactions = fileMetadata.localAppData?.localReactions.orEmpty(),
    )
}
