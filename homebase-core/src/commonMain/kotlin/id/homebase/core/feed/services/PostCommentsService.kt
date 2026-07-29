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
    // Encrypted comments upload through the shared pipeline (issue #844); public comments ship
    // plaintext and enqueue directly (no encryptBundle) below.
    private val uploadService: UploadService,
    private val optimisticWriter: OptimisticWriter,
    private val fileOps: FileOperationsProvider,
    // Reads a followed author's comment thread over peer (their comments live on their drive, not
    // ours). Only used when a post is a received/followed reference (non-null senderOdinId).
    private val driveQueryProvider: DriveQueryProvider,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "PostCommentsService"
        private const val ColdLoadPageSize = 1000
        private const val MaxPendingComments = 200
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
    // Replies whose parent comment hasn't been observed yet, keyed by the reply's groupId
    // (= the parent comment id). Flushed when that parent top-level comment is inserted.
    // Insertion-ordered and bounded: a groupId naming a POST the user never opens has no drain at
    // all (cold-load re-reads from the DB, not from here), so unbounded this retains a header per
    // synced comment for the whole session.
    private val pendingByGroupId = mutableMapOf<Uuid, MutableList<HomebaseFile>>()
    private var pendingCount = 0
    private var subscriptionStarted = false

    /**
     * Comments for [post]. Takes the whole post (not just its id) so a followed/received post can be
     * cold-loaded over peer: its comments live on the author's drive, addressed by the post's
     * globalTransitId on the author's channel drive. Own/connected posts still cold-load locally.
     */
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

    /**
     * Logout: drop the previous identity's comments and any buffered replies.
     * `subscriptionStarted` stays set — the collector is app-scoped and re-fills per post.
     */
    fun reset() {
        synchronized(lock) {
            perPost.values.forEach { it.flow.value = emptyList() }
            perPost.clear()
            pendingByGroupId.clear()
            pendingCount = 0
        }
    }

    /** Caller must hold [lock]. Buffers [file], evicting the oldest entries past the cap. */
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

            // Top-level comments + replies both surface here: replies carry the parent comment id
            // as their groupId, and the parent comment's id is in this post's comment set. We pull
            // the full comment tree for the post in two passes:
            //   pass 0 = top-level   (groupId == postId)
            //   pass 1 = replies     (groupId in the just-discovered top-level comment ids)
            // We NEVER call queryBatchAsync with an empty group set: an empty groupIdAnyOf is
            // dropped by QueryBatch and would return every comment on the drive (cross-post leak).
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
                    // Next pass queries replies whose parent is a not-yet-queried top-level comment.
                    roundGroupIds = newTopLevelIds.filterNot { it in seenGroupIds }
                    seenGroupIds.addAll(newTopLevelIds)
                }
            }
            // Followed/received posts: the author's copy of the thread lives on THEIR drive, so the
            // local passes above find nothing. Read it over peer by the post's globalTransitId.
            loadPeerComments(post, state)
            emitSorted(state)
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Cold-load failed for post=$postId: ${e.message}" }
        }
    }

    /**
     * Cold-load a followed post's comment thread over peer. The author's comments live on their
     * channel drive ([FeedPostItem.channelId]) keyed by the post's [FeedPostItem.globalTransitId];
     * we broker the read through our own server via [DriveQueryProvider.queryBatch] with the author
     * as `ownerOdinId`. Comments are the **Comment** file system (fileType 801) per the dotyoucore
     * convention, selected by the `X-ODIN-FILE-SYSTEM-TYPE` header (see queryBatch's fileSystemType).
     *
     * Two passes mirror the local cold-load: top-level (groupId == the post's gtid), then replies
     * (groupId in the discovered top-level comment ids). No-op for own posts (null senderOdinId).
     */
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

        // Collect the states whose flow needs re-emitting; emit OUTSIDE the lock.
        val touched = synchronized(lock) {
            val dirty = mutableSetOf<PerPostState>()
            for (file in comments) {
                val groupId = file.fileMetadata.appData.groupId ?: continue
                val uniqueId = file.fileMetadata.appData.uniqueId ?: continue
                // A comment's groupId is either the post (top-level) or a parent comment (reply).
                // Route it to whichever observed post owns that group: postId directly, or the post
                // that has a comment whose id == groupId (the reply's parent).
                val ownerPostId = perPost.keys.firstOrNull { it == groupId }
                    ?: perPost.entries.firstOrNull { (_, s) -> s.byId.containsKey(groupId) }?.key

                if (ownerPostId == null) {
                    // Owning post not observed yet. If this is a reply whose parent comment simply
                    // hasn't arrived, buffer it so a later top-level insert can flush it instead of
                    // dropping it. (A comment whose groupId is an unobserved POST has no drain —
                    // opening that post cold-loads it from the DB — so it just ages out of the cap.)
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

                // A top-level comment just landed: flush any replies that were waiting on it.
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
        // The post header drives the target drive, recipients AND encryption: a comment must mirror
        // the parent post's encryption + ACL, or a comment on a PUBLIC post would be encrypted +
        // Owner-locked and invisible to public/anonymous viewers (broken public commenting).
        // Refuse rather than guess — an unresolved header used to silently produce an encrypted,
        // recipient-less, distribution-off comment that looked posted but never reached the author.
        val (drive, post) = findPostLocally(postId)
            ?: error("Parent post $postId not found locally; refusing to post a comment")
        val recipients = recipientsFromPost(post)
        val groupId = replyToCommentId ?: postId

        // Public post (unencrypted) → unencrypted comment with an Anonymous ACL; encrypted post →
        // encrypted comment readable by AutoConnected. Mirrors FeedPostSenderService.createPost.
        //
        // Deliberate divergence from the web on ONE case: dotyoucore-js overrides the ACL to
        // AutoConnected for every comment written over peer (`saveComment`'s peer branch),
        // including comments on an unencrypted public post. Anonymous is kept here because a
        // comment on a public post that only connections can read is invisible to exactly the
        // audience the post was written for. Encryption still follows the parent post either way,
        // and `allowDistribution` is already true whenever there are recipients — matching web.
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
        // Plaintext bundle. Encryption preserves payload keys and passes preview thumbs through
        // unchanged, so `mediaKey` / `previewThumbnail` are derived from this bundle in both paths.
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

        // Unencrypted metadata; the encrypted path lets UploadService apply `encryptContent`.
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
            // Encrypted comment → the shared pipeline (issue #844): it encrypts the bundle +
            // metadata content, enqueues the durable outbox row, seeds the cache, and writes the
            // optimistic local row.
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
            // Public comment → plaintext payloads, no encryptBundle (so #844-compliant to build the
            // request directly). Enqueue, then write the optimistic row best-effort.
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

    /** Edit a comment body. AES key reused, empty manifest, replaceEnqueue — mirrors Moments. */
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

        // Recipients + encryption resolve against the OWNING POST (hop a reply's groupId to the
        // post), never the parent commenter, and must mirror the post's encryption + ACL exactly
        // like postComment — so an edit to a public comment stays unencrypted/Anonymous.
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

    /** Locate a post locally across the source drives, as (drive, header). */
    private suspend fun findPostLocally(postId: Uuid): Pair<Uuid, HomebaseFile>? {
        for (drive in sourceDrives) {
            readPostHeader(drive, postId)?.let { return drive to it }
        }
        return null
    }

    /**
     * Read a header on [drive] addressed by [id], or null when it isn't present locally.
     *
     * [id] is a uniqueId for our own files, but a followed identity's post is aggregated onto the
     * feed drive with NO uniqueId — [toFeedPostItem] then falls back to the globalTransitId, so
     * [FeedPostItem.id] is a gtid for every such post and the uniqueId select can never hit it.
     */
    private suspend fun readPostHeader(drive: Uuid, id: Uuid): HomebaseFile? {
        val identityId = credentialsManager.requireActiveCredentials().getIdentityId()
        val index = databaseManager.driveMainIndex
        index.selectHomebaseFileByUnique(identityId, drive, id)?.let { return it }
        return index.selectByIdentityAndDriveAndGlobal(identityId, drive, id)
            ?.let { OdinSystemSerializer.deserialize<HomebaseFile>(it.jsonHeader) }
    }

    /**
     * Resolve the OWNING POST header for a comment's `groupId`. The `groupId` is either the post id
     * (top-level comment) or a parent COMMENT id (a reply). When it's a reply, read the parent
     * comment and follow its `groupId` to the post — so recipients/encryption are always resolved
     * against the post, never the parent commenter. Returns null when nothing resolves locally.
     */
    private suspend fun resolveOwningPost(drive: Uuid, groupId: Uuid): HomebaseFile? {
        val direct = readPostHeader(drive, groupId) ?: return null
        // A reply's groupId points at a parent COMMENT (fileType 801). Hop once to the post.
        if (direct.fileMetadata.appData.fileType == FeedProtocol.CommentFileType) {
            val parentGroupId = direct.fileMetadata.appData.groupId ?: return null
            return readPostHeader(drive, parentGroupId)
        }
        return direct
    }

    /**
     * Recipients for a comment given the owning POST header: the post's author minus self. A feed
     * [PostContent] carries no explicit recipient list (unlike a moment), so the audience is the
     * post author for an inbound (followed) post, or empty for the user's own post (the server
     * distributes own posts via the follower system).
     *
     * Use `originalAuthor` first: on a follower's copy of an inbound post the server STRIPS
     * `senderOdinId`, so resolving the author from `senderOdinId` alone returns null → empty
     * recipients → the comment is written local-only and never reaches the author (the bug where
     * comments on a followed post silently failed to post). `originalAuthor` survives transit and
     * names the real author; fall back to `senderOdinId` for the user's own posts.
     */
    private suspend fun recipientsFromPost(post: HomebaseFile?): List<OdinId> {
        if (post == null) return emptyList()
        val self = credentialsManager.getActiveCredentials()?.domain
        val author = post.fileMetadata.originalAuthor ?: post.fileMetadata.senderOdinId
        return listOfNotNull(author).filterNot { it == self }
    }

    /**
     * Resolve who a comment delete should be sent to: the post's author (`senderOdinId`) minus self.
     * Follows a reply's `groupId` to the post just like the post/edit paths. Reads the post header
     * locally — never a server round-trip.
     */
    private suspend fun resolveCommentRecipients(drive: Uuid, postOrCommentId: Uuid): List<OdinId> =
        recipientsFromPost(resolveOwningPost(drive, postOrCommentId))
}

data class PostCommentResult(val uniqueId: Uuid)
