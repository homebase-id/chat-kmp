package id.homebase.core.feed.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.SecurityGroupType
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
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.MessageAttachmentBuilder
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.config.feedLabeledDrive
import id.homebase.upload.MediaUploadSpec
import id.homebase.upload.UploadOutcome
import id.homebase.upload.UploadService
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

// Threading is one level: a top-level comment has groupId = postId, a reply has groupId = parentCommentId.
@OptIn(ExperimentalEncodingApi::class)
class PostCommentsService(
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val eventBus: EventBus,
    private val outboxSync: OutboxSync,
    private val uploadService: UploadService,
    private val optimisticWriter: OptimisticWriter,
    private val fileOps: FileOperationsProvider,
    private val driveQueryProvider: DriveQueryProvider,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "PostCommentsService"
        private const val ColdLoadPageSize = 1000
        private const val MaxPendingComments = 200
    }

    // The FeedDrive and the channel drive share the same groupId index, so cold-loading the FeedDrive covers both.
    private val feedDrive = feedLabeledDrive.drive.alias
    private val channelDrive = SystemDriveConstants.publicPostChannelDrive.alias
    private val sourceDrives = setOf(feedDrive, channelDrive)

    private class PerPostState {
        val byId = mutableMapOf<Uuid, PostCommentItem>()
        val flow = MutableStateFlow<List<PostCommentItem>>(emptyList())
    }

    private val lock = SynchronizedObject()
    private val perPost = mutableMapOf<Uuid, PerPostState>()
    // Replies whose parent comment hasn't been observed yet, keyed by the reply's groupId. Bounded: a groupId
    // naming a POST the user never opens has no drain at all, so unbounded this leaks a header per synced comment.
    private val pendingByGroupId = mutableMapOf<Uuid, MutableList<HomebaseFile>>()
    private var pendingCount = 0
    private var subscriptionStarted = false

    // Takes the whole post, not just its id: routing a peer cold-load needs its globalTransitId and channel.
    fun commentsFor(post: FeedPostItem): StateFlow<List<PostCommentItem>> {
        val (state, isFirstObserver) = stateFor(post.id)
        if (isFirstObserver) {
            scope.launch { coldLoad(post, state) }
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
                when (event) {
                    is BackendEvent.SessionEnded -> reset()
                    is BackendEvent.DataEvent.BatchReceived -> {
                        if (event.driveId !in sourceDrives) return@collect
                        processIncrementalBatch(event.batchData)
                    }
                    else -> {}
                }
            }
        }
    }

    // subscriptionStarted stays set — the collector is app-scoped and re-fills per post.
    fun reset() {
        synchronized(lock) {
            perPost.values.forEach { it.flow.value = emptyList() }
            perPost.clear()
            pendingByGroupId.clear()
            pendingCount = 0
        }
    }

    /** Caller must hold [lock]. */
    private fun bufferPendingLocked(groupId: Uuid, file: HomebaseFile) {
        pendingByGroupId.getOrPut(groupId) { mutableListOf() }.add(file)
        pendingCount++
        while (pendingCount > MaxPendingComments) {
            val oldest = pendingByGroupId.entries.first()
            oldest.value.removeAt(0)
            pendingCount--
            if (oldest.value.isEmpty()) pendingByGroupId.remove(oldest.key)
        }
    }

    private suspend fun coldLoad(post: FeedPostItem, state: PerPostState) {
        val postId = post.id
        try {
            val active = credentialsManager.getActiveCredentials() ?: return
            val identityId = active.getIdentityId()

            // Two passes: top-level (groupId == postId), then replies (groupId in the discovered comment ids).
            // NEVER call queryBatchAsync with an empty group set — QueryBatch drops an empty groupIdAnyOf and
            // returns every comment on the drive (cross-post leak).
            for (drive in sourceDrives) {
                val seenGroupIds = mutableSetOf(postId)
                var roundGroupIds = listOf(postId)
                repeat(2) {
                    if (roundGroupIds.isEmpty()) return@repeat   // nothing to query this pass — skip
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
                    val items = result.records
                        .filterNot { it.isSoftDeleted() }
                        .mapNotNull { it.toCommentItem(topLevelPostId = postId) }
                    val newTopLevelIds = synchronized(lock) {
                        items.forEach { state.byId[it.id] = it }
                        state.byId.values.filter { it.replyToId == null }.map { it.id }
                    }
                    roundGroupIds = newTopLevelIds.filterNot { it in seenGroupIds }
                    seenGroupIds.addAll(newTopLevelIds)
                }
            }
            // A followed post's thread lives on the AUTHOR's drive, so the local passes above find nothing.
            loadPeerComments(post, state)
            emitSorted(state)
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Cold-load failed for post=$postId: ${e.message}" }
        }
    }

    // Brokered through our own server with the author as ownerOdinId. Comments are the Comment file system
    // (fileType 801), selected by the X-ODIN-FILE-SYSTEM-TYPE header.
    private suspend fun loadPeerComments(post: FeedPostItem, state: PerPostState) {
        val author = post.senderOdinId ?: return
        val gtid = post.globalTransitId ?: return
        val channelDrive = runCatching { Uuid.parse(post.channelId) }.getOrNull() ?: return

        val seenGroupIds = mutableSetOf(gtid)
        var roundGroupIds = listOf(gtid)
        repeat(2) {
            if (roundGroupIds.isEmpty()) return@repeat
            val response = try {
                driveQueryProvider.queryBatch(
                    driveId = channelDrive,
                    request = QueryBatchRequest(
                        queryParams = FileQueryParams(
                            fileType = listOf(FeedProtocol.CommentFileType),
                            groupId = roundGroupIds,
                        ),
                        resultOptionsRequest = QueryBatchResultOptionsRequest(
                            maxRecords = ColdLoadPageSize,
                            includeMetadataHeader = true,
                            ordering = QueryBatchSortOrder.NewestFirst,
                            sorting = QueryBatchSortField.UserDate,
                        ),
                    ),
                    ownerOdinId = author,
                    fileSystemType = FileSystemType.Comment,
                )
            } catch (e: Exception) {
                Logger.w(throwable = e, tag = TAG) {
                    "loadPeerComments: query failed post=${post.id} author=$author: ${e.message}"
                }
                return
            }
            val items = response.searchResults
                .filterNot { it.isSoftDeleted() }
                .mapNotNull { it.toCommentItem(topLevelPostId = post.id) }
            val newTopLevelIds = synchronized(lock) {
                items.forEach { state.byId[it.id] = it }
                state.byId.values.filter { it.replyToId == null }.map { it.id }
            }
            roundGroupIds = newTopLevelIds.filterNot { it in seenGroupIds }
            seenGroupIds.addAll(newTopLevelIds)
        }
    }

    private fun processIncrementalBatch(files: List<HomebaseFile>) {
        val comments = files.filter {
            it.fileMetadata.appData.fileType == FeedProtocol.CommentFileType
        }
        if (comments.isEmpty()) return

        // Emit OUTSIDE the lock.
        val touched = synchronized(lock) {
            val dirty = mutableSetOf<PerPostState>()
            for (file in comments) {
                val groupId = file.fileMetadata.appData.groupId ?: continue
                val uniqueId = file.fileMetadata.appData.uniqueId ?: continue
                val ownerPostId = perPost.keys.firstOrNull { it == groupId }
                    ?: perPost.entries.firstOrNull { (_, s) -> s.byId.containsKey(groupId) }?.key

                if (ownerPostId == null) {
                    // Owning post not observed yet. Buffer a reply so a later top-level insert can flush it;
                    // a comment whose groupId is an unobserved POST has no drain and just ages out of the cap.
                    if (!file.isSoftDeleted()) {
                        bufferPendingLocked(groupId, file)
                        Logger.d(tag = TAG) {
                            "processIncrementalBatch: buffering comment=$uniqueId with " +
                                "unresolved parent groupId=$groupId"
                        }
                    }
                    continue
                }
                val state = perPost[ownerPostId] ?: continue

                if (file.isSoftDeleted()) {
                    if (state.byId.remove(uniqueId) != null) dirty.add(state)
                    continue
                }
                val item = file.toCommentItem(topLevelPostId = ownerPostId) ?: continue
                state.byId[uniqueId] = item
                dirty.add(state)

                if (item.replyToId == null) {
                    val waiting = pendingByGroupId.remove(uniqueId)?.also { pendingCount -= it.size }
                    waiting?.forEach { reply ->
                        val replyId = reply.fileMetadata.appData.uniqueId ?: return@forEach
                        if (reply.isSoftDeleted()) {
                            if (state.byId.remove(replyId) != null) dirty.add(state)
                        } else {
                            val replyItem = reply.toCommentItem(topLevelPostId = ownerPostId)
                                ?: return@forEach
                            state.byId[replyId] = replyItem
                            dirty.add(state)
                        }
                    }
                }
            }
            dirty.toList()
        }
        touched.forEach { emitSorted(it) }
    }

    private fun emitSorted(state: PerPostState) {
        val sorted = synchronized(lock) { state.byId.values.sortedBy { it.userDateMs } }
        state.flow.value = sorted
    }

    suspend fun postComment(
        postId: Uuid,
        body: String,
        attachment: AttachmentInput? = null,
        replyToCommentId: Uuid? = null,
        commentUniqueId: Uuid = Uuid.random(),
    ): PostCommentResult {
        // The post header drives target drive, recipients AND encryption. Refuse rather than guess: an
        // unresolved header used to produce an encrypted, recipient-less comment that looked posted but
        // never reached the author.
        val (drive, post) = findPostLocally(postId)
            ?: error("Parent post $postId not found locally; refusing to post a comment")
        val recipients = recipientsFromPost(post)
        val groupId = replyToCommentId ?: postId

        // Deliberate divergence from dotyoucore-js, which forces AutoConnected on every peer-written comment:
        // on a public post that would hide the comment from exactly the audience the post was written for.
        val isPublic = !post.fileMetadata.isEncrypted
        val isEncrypted = !isPublic
        val keyHeader = if (isEncrypted) KeyHeader.newRandom16() else KeyHeader.empty()
        val accessControlList = AccessControlList(
            requiredSecurityGroup = if (isPublic) {
                SecurityGroupType.Anonymous.value
            } else {
                SecurityGroupType.AutoConnected.value
            },
        )

        val attachments = listOfNotNull(attachment)
        // Encryption preserves payload keys and passes preview thumbs through, so both paths derive them here.
        val bundle = MessageAttachmentBuilder.build(
            attachments = attachments,
            fileOperationsProvider = fileOps,
        ) { _, _ -> FeedProtocol.CommentMediaPayloadKey }

        val isLocalOnly = recipients.isEmpty()
        val mediaKey = bundle.payloads.firstOrNull()?.key
        val content = OdinSystemSerializer.serialize(
            PostCommentContent(
                version = FeedProtocol.CommentVersion,
                body = body,
                mediaPayloadKey = mediaKey,
            )
        )

        val metadata = UploadFileMetadata(
            allowDistribution = !isLocalOnly,
            isEncrypted = isEncrypted,
            accessControlList = accessControlList,
            appData = UploadAppFileMetaData(
                uniqueId = commentUniqueId,
                groupId = groupId,
                fileType = FeedProtocol.CommentFileType,
                userDate = UnixTimeUtc.now().milliseconds,
                content = content,
                previewThumbnail = bundle.previewThumbs.minByOrNull { it.pixelWidth },
            ),
        )

        val transit = TransitOptions(
            recipients = recipients,
            sendContents = SendContents.All,
            useAppNotification = false,
        )

        if (isEncrypted) {
            val outcome = uploadService.upload(
                MediaUploadSpec(
                    driveId = drive,
                    uniqueId = commentUniqueId,
                    keyHeader = keyHeader,
                    bundle = bundle,
                    metadata = metadata,
                    transit = transit,
                    originalRecipientCount = recipients.size,
                ),
                scope = scope,
            )
            if (outcome !is UploadOutcome.Enqueued) {
                error("Failed to enqueue comment for upload (outbox: $outcome)")
            }
        } else {
            // Public comment → plaintext payloads, so the request is built directly with no encryptBundle.
            val request = UploadFileRequest(
                driveId = drive,
                keyHeader = keyHeader,
                metadata = metadata,
                payloads = bundle.payloads,
                thumbnails = bundle.thumbnails,
                transitOptions = transit,
            )
            val enqueued = outboxSync.tryEnqueue(request)
            if (!enqueued.enqueued) error("Failed to enqueue comment for upload (outbox: $enqueued)")

            val payloadDescriptors = bundle.payloads.map { payload ->
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
        }

        Logger.d(tag = TAG) {
            "postComment: enqueued comment=$commentUniqueId groupId=$groupId encrypted=$isEncrypted"
        }
        return PostCommentResult(uniqueId = commentUniqueId)
    }

    /** AES key reused, empty manifest, replaceEnqueue — mirrors Moments. */
    suspend fun updateComment(
        commentUniqueId: Uuid,
        versionTag: Uuid,
        body: String,
    ): PostCommentResult {
        val (drive, existing) = findCommentLocally(commentUniqueId)
            ?: throw IllegalArgumentException("comment not found locally: $commentUniqueId")
        if (existing.fileMetadata.versionTag != versionTag) error("VersionTag mismatch")

        val groupId = existing.fileMetadata.appData.groupId
            ?: throw IllegalStateException("comment $commentUniqueId missing groupId")
        val existingContent = existing.fileMetadata.appData.content?.let { raw ->
            runCatching { OdinSystemSerializer.deserialize<PostCommentContent>(raw) }.getOrNull()
        }

        // Resolve against the OWNING POST (hop a reply's groupId), never the parent commenter, so an edit to
        // a public comment stays unencrypted/Anonymous.
        val post = resolveOwningPost(drive, groupId)
            ?: error("Owning post for comment $commentUniqueId not found locally; refusing to edit")
        val recipients = recipientsFromPost(post)
        val isLocalOnly = recipients.isEmpty()

        val isPublic = !post.fileMetadata.isEncrypted
        val isEncrypted = !isPublic
        val keyHeader = if (isEncrypted) {
            KeyHeader(iv = ByteArrayUtil.getRndByteArray(16), aesKey = existing.keyHeader.aesKey)
        } else {
            KeyHeader.empty()
        }
        val accessControlList = AccessControlList(
            requiredSecurityGroup = if (isPublic) {
                SecurityGroupType.Anonymous.value
            } else {
                SecurityGroupType.AutoConnected.value
            },
        )

        val content = OdinSystemSerializer.serialize(
            PostCommentContent(
                version = FeedProtocol.CommentVersion,
                body = body,
                mediaPayloadKey = existingContent?.mediaPayloadKey,
            )
        )

        val metadata = UploadFileMetadata(
            allowDistribution = !isLocalOnly,
            isEncrypted = isEncrypted,
            accessControlList = accessControlList,
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
            metadata = if (isEncrypted) metadata.encryptContent(keyHeader) else metadata,
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

    private suspend fun findPostLocally(postId: Uuid): Pair<Uuid, HomebaseFile>? {
        for (drive in sourceDrives) {
            readPostHeader(drive, postId)?.let { return drive to it }
        }
        return null
    }

    // [id] is a uniqueId for our own files, but a followed post is aggregated with NO uniqueId and falls back
    // to its globalTransitId — so the uniqueId select can never hit it.
    private suspend fun readPostHeader(drive: Uuid, id: Uuid): HomebaseFile? {
        val identityId = credentialsManager.requireActiveCredentials().getIdentityId()
        val index = databaseManager.driveMainIndex
        index.selectHomebaseFileByUnique(identityId, drive, id)?.let { return it }
        return index.selectByIdentityAndDriveAndGlobal(identityId, drive, id)
            ?.let { OdinSystemSerializer.deserialize<HomebaseFile>(it.jsonHeader) }
    }

    // groupId is either the post id (top-level) or a parent COMMENT id (a reply); hop the reply to the post so
    // recipients/encryption always resolve against the post.
    private suspend fun resolveOwningPost(drive: Uuid, groupId: Uuid): HomebaseFile? {
        val direct = readPostHeader(drive, groupId) ?: return null
        if (direct.fileMetadata.appData.fileType == FeedProtocol.CommentFileType) {
            val parentGroupId = direct.fileMetadata.appData.groupId ?: return null
            return readPostHeader(drive, parentGroupId)
        }
        return direct
    }

    // Use originalAuthor first: on a follower's copy the server STRIPS senderOdinId, so resolving from it alone
    // yields empty recipients and the comment never reaches the author. Own posts are distributed by the server.
    private suspend fun recipientsFromPost(post: HomebaseFile?): List<OdinId> {
        if (post == null) return emptyList()
        val self = credentialsManager.getActiveCredentials()?.domain
        val author = post.fileMetadata.originalAuthor ?: post.fileMetadata.senderOdinId
        return listOfNotNull(author).filterNot { it == self }
    }

    // Follows a reply's groupId to the post, reading the header locally — never a server round-trip.
    private suspend fun resolveCommentRecipients(drive: Uuid, postOrCommentId: Uuid): List<OdinId> =
        recipientsFromPost(resolveOwningPost(drive, postOrCommentId))
}

data class PostCommentResult(val uniqueId: Uuid)
