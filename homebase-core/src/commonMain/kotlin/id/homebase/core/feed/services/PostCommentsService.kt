package id.homebase.core.feed.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.SendContents
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.QueryBatch
import id.homebase.api.sync.database.enqueued
import id.homebase.chat.services.PayloadBundleEncryptor
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.MessageAttachmentBuilder
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.config.feedLabeledDrive
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

/**
 * Per-post view of comments + the comment send/edit/remove path. Mirrors
 * [id.homebase.core.moments.services.MomentCommentsService] (per-parent `StateFlow`, lazy
 * cold-load + shared event subscription) and the Moments comment send.
 *
 * Comments are `fileType = [FeedProtocol.CommentFileType]` (801). Threading is one level:
 *  - a **top-level** comment has `groupId = postId`,
 *  - a **reply** has `groupId = parentCommentId`.
 *
 * So `commentsFor(postId)` cold-loads `groupId = postId` (top-level) and, when a reply's parent is
 * known, replies are queried by `groupId = parentCommentId`. The mapper tags a file whose `groupId`
 * differs from the queried post as a reply ([PostCommentItem.replyToId]).
 *
 * Comments live on the post's drive. For posts the user reads in their home feed that is the
 * FeedDrive; the channel drive is also covered because both are queried by the same `groupId`.
 */
@OptIn(ExperimentalEncodingApi::class)
class PostCommentsService(
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val eventBus: EventBus,
    private val outboxSync: OutboxSync,
    private val payloadBundleEncryptor: PayloadBundleEncryptor,
    private val optimisticWriter: OptimisticWriter,
    private val fileOps: FileOperationsProvider,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "PostCommentsService"
        private const val ColdLoadPageSize = 1000
    }

    // Comments land on the post's drive. The home feed reads the FeedDrive; the channel drive
    // shares the same groupId index, so cold-loading the FeedDrive covers feed reads. Sends target
    // the post's own drive (resolved from where the post row lives).
    private val feedDrive = feedLabeledDrive.drive.alias
    private val channelDrive = SystemDriveConstants.publicPostChannelDrive.alias
    private val sourceDrives = setOf(feedDrive, channelDrive)

    private class PerPostState {
        val byId = mutableMapOf<Uuid, PostCommentItem>()
        val flow = MutableStateFlow<List<PostCommentItem>>(emptyList())
    }

    private val lock = SynchronizedObject()
    private val perPost = mutableMapOf<Uuid, PerPostState>()
    private var subscriptionStarted = false

    fun commentsFor(postId: Uuid): StateFlow<List<PostCommentItem>> {
        val (state, isFirstObserver) = stateFor(postId)
        if (isFirstObserver) {
            scope.launch { coldLoad(postId, state) }
            ensureSubscription()
        }
        return state.flow.asStateFlow()
    }

    private fun stateFor(postId: Uuid): Pair<PerPostState, Boolean> = synchronized(lock) {
        val existing = perPost[postId]
        if (existing != null) return@synchronized existing to false
        val fresh = PerPostState()
        perPost[postId] = fresh
        fresh to true
    }

    private fun ensureSubscription() {
        synchronized(lock) {
            if (subscriptionStarted) return
            subscriptionStarted = true
        }
        scope.launch {
            eventBus.events.collect { event ->
                if (event !is BackendEvent.DataEvent.BatchReceived) return@collect
                if (event.driveId !in sourceDrives) return@collect
                processIncrementalBatch(event.batchData)
            }
        }
    }

    private suspend fun coldLoad(postId: Uuid, state: PerPostState) {
        try {
            val active = credentialsManager.getActiveCredentials() ?: return
            val identityId = active.getIdentityId()

            // Top-level comments + replies both surface here: replies carry the parent comment id
            // as their groupId, and the parent comment's id is in this post's comment set. We pull
            // the full comment tree for the post by querying every groupId we know belongs to it —
            // for the cold-load that is the postId (top-level) plus each loaded comment's id.
            val groupIds = mutableListOf(postId)
            for (drive in sourceDrives) {
                var roundGroupIds = groupIds.toList()
                // Two passes: first top-level (groupId=postId), then replies (groupId=commentId).
                repeat(2) {
                    val result = QueryBatch(identityId).queryBatchAsync(
                        dbm = databaseManager,
                        driveId = drive,
                        noOfItems = ColdLoadPageSize,
                        cursor = null,
                        sortOrder = QueryBatchSortOrder.NewestFirst,
                        sortField = QueryBatchSortField.UserDate,
                        fileSystemType = 0,
                        filetypesAnyOf = listOf(FeedProtocol.CommentFileType),
                        groupIdAnyOf = roundGroupIds,
                    )
                    for (file in result.records) {
                        if (file.isSoftDeleted()) continue
                        val item = file.toCommentItem(topLevelPostId = postId) ?: continue
                        state.byId[item.id] = item
                    }
                    // Next pass queries replies whose parent is any top-level comment just found.
                    roundGroupIds = state.byId.values
                        .filter { it.replyToId == null }
                        .map { it.id }
                        .filterNot { it in groupIds }
                    if (roundGroupIds.isEmpty()) return@repeat
                }
            }
            emitSorted(state)
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Cold-load failed for post=$postId: ${e.message}" }
        }
    }

    private fun processIncrementalBatch(files: List<HomebaseFile>) {
        val comments = files.filter {
            it.fileMetadata.appData.fileType == FeedProtocol.CommentFileType
        }
        if (comments.isEmpty()) return

        for (file in comments) {
            val groupId = file.fileMetadata.appData.groupId ?: continue
            val uniqueId = file.fileMetadata.appData.uniqueId ?: continue
            // A comment's groupId is either the post (top-level) or a parent comment (reply). Route
            // it to whichever observed post owns that group: postId directly, or the post that has
            // a comment whose id == groupId (the reply's parent).
            val ownerPostId = perPost.keys.firstOrNull { it == groupId }
                ?: perPost.entries.firstOrNull { (_, s) -> s.byId.containsKey(groupId) }?.key
                ?: continue
            val state = perPost[ownerPostId] ?: continue

            if (file.isSoftDeleted()) {
                if (state.byId.remove(uniqueId) != null) emitSorted(state)
                continue
            }
            val item = file.toCommentItem(topLevelPostId = ownerPostId) ?: continue
            state.byId[uniqueId] = item
            emitSorted(state)
        }
    }

    private fun emitSorted(state: PerPostState) {
        state.flow.value = state.byId.values.sortedBy { it.userDateMs }
    }

    /**
     * Post a comment (or one-level reply) against [postId]. A top-level comment uses
     * `groupId = postId`; a reply uses `groupId = replyToCommentId`. Recipients are the post's
     * audience minus self.
     */
    suspend fun postComment(
        postId: Uuid,
        body: String,
        attachment: AttachmentInput? = null,
        replyToCommentId: Uuid? = null,
        commentUniqueId: Uuid = Uuid.random(),
    ): PostCommentResult {
        val drive = resolvePostDrive(postId)
        val recipients = resolveCommentRecipients(drive, postId)
        val groupId = replyToCommentId ?: postId

        val attachments = listOfNotNull(attachment)
        val bundle = MessageAttachmentBuilder.build(
            attachments = attachments,
            fileOperationsProvider = fileOps,
        ) { _, _ -> FeedProtocol.CommentMediaPayloadKey }

        val keyHeader = KeyHeader.newRandom16()
        val encrypted = payloadBundleEncryptor.encryptBundle(
            uniqueId = commentUniqueId,
            bundle = bundle,
            aesKey = keyHeader.aesKey,
            scope = scope,
        )

        val isLocalOnly = recipients.isEmpty()
        val mediaKey = encrypted.payloads.firstOrNull()?.key
        val content = OdinSystemSerializer.serialize(
            PostCommentContent(
                version = FeedProtocol.CommentVersion,
                body = body,
                mediaPayloadKey = mediaKey,
            )
        )

        val metadata = UploadFileMetadata(
            allowDistribution = !isLocalOnly,
            isEncrypted = true,
            appData = UploadAppFileMetaData(
                uniqueId = commentUniqueId,
                groupId = groupId,
                fileType = FeedProtocol.CommentFileType,
                userDate = UnixTimeUtc.now().milliseconds,
                content = content,
                previewThumbnail = encrypted.previewThumbs.minByOrNull { it.pixelWidth },
            ),
        )

        val request = UploadFileRequest(
            driveId = drive,
            keyHeader = keyHeader,
            metadata = metadata.encryptContent(keyHeader),
            payloads = encrypted.payloads,
            thumbnails = encrypted.thumbnails,
            transitOptions = TransitOptions(
                recipients = recipients,
                sendContents = SendContents.All,
                useAppNotification = false,
            ),
        )

        val enqueued = outboxSync.tryEnqueue(request)
        if (!enqueued.enqueued) error("Failed to enqueue comment for upload (outbox: $enqueued)")
        Logger.d(tag = TAG) { "postComment: enqueued comment=$commentUniqueId groupId=$groupId" }

        val payloadDescriptors = encrypted.payloads.map { payload ->
            PayloadDescriptor(
                key = payload.key,
                contentType = payload.contentType.ifEmpty { null },
                iv = payload.iv?.let { Base64.encode(it) },
                descriptorContent = payload.descriptorContent,
                previewThumbnail = payload.previewThumbnail?.let {
                    ThumbnailDescriptor(
                        pixelWidth = it.pixelWidth,
                        pixelHeight = it.pixelHeight,
                        contentType = it.contentType,
                        content = it.content,
                    )
                },
            )
        }.ifEmpty { null }

        try {
            optimisticWriter.writeNewFile(
                driveId = drive,
                keyHeader = keyHeader,
                unecryptedMetadata = metadata,
                originalRecipientCount = recipients.size,
                fileSystemType = FileSystemType.Standard,
                payloadDescriptors = payloadDescriptors,
            )
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) {
                "postComment: optimistic write failed (non-fatal) comment=$commentUniqueId"
            }
        }

        return PostCommentResult(uniqueId = commentUniqueId)
    }

    /** Edit a comment body. AES key reused, empty manifest, replaceEnqueue — mirrors Moments. */
    suspend fun updateComment(
        commentUniqueId: Uuid,
        versionTag: Uuid,
        body: String,
    ): PostCommentResult {
        val credentials = credentialsManager.requireActiveCredentials()
        val (drive, existing) = findCommentLocally(commentUniqueId)
            ?: throw IllegalArgumentException("comment not found locally: $commentUniqueId")
        if (existing.fileMetadata.versionTag != versionTag) error("VersionTag mismatch")

        val groupId = existing.fileMetadata.appData.groupId
            ?: throw IllegalStateException("comment $commentUniqueId missing groupId")
        val existingContent = existing.fileMetadata.appData.content?.let { raw ->
            runCatching { OdinSystemSerializer.deserialize<PostCommentContent>(raw) }.getOrNull()
        }

        val keyHeader = KeyHeader(iv = ByteArrayUtil.getRndByteArray(16), aesKey = existing.keyHeader.aesKey)
        val recipients = resolveCommentRecipients(drive, groupId)
        val isLocalOnly = recipients.isEmpty()

        val content = OdinSystemSerializer.serialize(
            PostCommentContent(
                version = FeedProtocol.CommentVersion,
                body = body,
                mediaPayloadKey = existingContent?.mediaPayloadKey,
            )
        )

        val metadata = UploadFileMetadata(
            allowDistribution = !isLocalOnly,
            isEncrypted = true,
            versionTag = versionTag,
            appData = UploadAppFileMetaData(
                uniqueId = commentUniqueId,
                groupId = groupId,
                fileType = FeedProtocol.CommentFileType,
                userDate = existing.fileMetadata.appData.userDate,
                content = content,
                previewThumbnail = existing.fileMetadata.appData.previewThumbnail,
            ),
        )

        val request = UpdateFileByUniqueIdRequest(
            driveId = drive,
            uniqueId = commentUniqueId,
            keyHeader = keyHeader,
            instructions = FileUpdateInstructionSet(
                transferIv = ByteArrayUtil.getRndByteArray(16),
                locale = UpdateLocale.Local,
                recipients = recipients,
                manifest = UpdateManifest.build(
                    payloads = null,
                    toDeletePayloads = null,
                    thumbnails = null,
                    generatePayloadIv = false,
                ),
                useAppNotification = false,
                appNotificationOptions = null,
            ),
            metadata = metadata.encryptContent(keyHeader),
            payloads = emptyList(),
            thumbnails = emptyList(),
        )

        val enqueued = outboxSync.replaceEnqueue(request)
        if (!enqueued.enqueued) error("Failed to enqueue comment update (outbox: $enqueued)")

        try {
            optimisticWriter.writeUpdate(
                driveId = drive,
                keyHeader = keyHeader,
                unecryptedMetadata = metadata,
            )
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) {
                "updateComment: optimistic write failed (non-fatal) comment=$commentUniqueId"
            }
        }

        return PostCommentResult(uniqueId = commentUniqueId)
    }

    /** Soft-delete a comment. Recipients = the parent post/comment audience minus self. */
    suspend fun removeComment(commentUniqueId: Uuid) {
        val (drive, existing) = findCommentLocally(commentUniqueId) ?: run {
            Logger.w(tag = TAG) { "removeComment: comment $commentUniqueId not found locally" }
            return
        }
        val groupId = existing.fileMetadata.appData.groupId
        val recipients = groupId?.let { resolveCommentRecipients(drive, it) }.orEmpty()

        val original = optimisticWriter.writeDelete(drive, commentUniqueId) ?: return
        try {
            val enqueued = outboxSync.tryEnqueue(
                request = id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest(
                    driveId = drive,
                    fileIds = listOf(original.fileId),
                    recipients = recipients.ifEmpty { null },
                    hardDelete = false,
                ),
            )
            if (!enqueued.enqueued) {
                optimisticWriter.rollbackWrite(drive, original)
            }
        } catch (t: Throwable) {
            Logger.e(throwable = t, tag = TAG) { "removeComment failed to enqueue: ${t.message}" }
            runCatching { optimisticWriter.rollbackWrite(drive, original) }
        }
    }

    // -------------------- HELPERS --------------------

    /** Find which source drive holds a comment locally, returning (drive, file). */
    private suspend fun findCommentLocally(commentUniqueId: Uuid): Pair<Uuid, HomebaseFile>? {
        val credentials = credentialsManager.requireActiveCredentials()
        for (drive in sourceDrives) {
            val file = databaseManager.driveMainIndex.selectHomebaseFileByUnique(
                credentials.getIdentityId(), drive, commentUniqueId,
            )
            if (file != null) return drive to file
        }
        return null
    }

    /** Which drive a post lives on locally — defaults to the channel drive (the author's own). */
    private suspend fun resolvePostDrive(postId: Uuid): Uuid {
        val credentials = credentialsManager.requireActiveCredentials()
        for (drive in sourceDrives) {
            val file = databaseManager.driveMainIndex.selectHomebaseFileByUnique(
                credentials.getIdentityId(), drive, postId,
            )
            if (file != null) return drive
        }
        return channelDrive
    }

    /**
     * Resolve who a comment should be sent to: the post's author (`senderOdinId`) minus self.
     * A feed [PostContent] carries no explicit recipient list (unlike a moment), so the audience is
     * the post author for an inbound post, or empty for the user's own post (server distributes via
     * the follower system). Reads the post header locally — never a server round-trip.
     */
    private suspend fun resolveCommentRecipients(drive: Uuid, postOrCommentId: Uuid): List<OdinId> {
        val credentials = credentialsManager.requireActiveCredentials()
        val file = databaseManager.driveMainIndex.selectHomebaseFileByUnique(
            credentials.getIdentityId(), drive, postOrCommentId,
        ) ?: return emptyList()
        val self = credentials.domain
        val author = file.fileMetadata.senderOdinId
        return listOfNotNull(author).filterNot { it == self }
    }
}

data class PostCommentResult(val uniqueId: Uuid)
