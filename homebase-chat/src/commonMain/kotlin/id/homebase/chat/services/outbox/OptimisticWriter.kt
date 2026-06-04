package id.homebase.chat.services.outbox

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.files.LocalAppMetadata
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ReactionEntry
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.client.drives.files.reactions.ToggleReactionResultType
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.api.client.drives.upload.UpdateLocalAppdataContentOutboxRequest
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatProtocol
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import id.homebase.chat.services.convo.ConversationAppDataJson
import id.homebase.chat.services.convo.ConversationLocalAppDataJson
import kotlin.uuid.Uuid
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OptimisticWriter(
    private val credentialsManager: CredentialsManager,
    private val dbm: DatabaseManager,
    private val eventBus: EventBus,
) {
    companion object {
        private const val TAG = "OptimisticWriter"
    }

    private val fileProcessor: MainIndexMetaHelpers.HomebaseFileProcessor =
        MainIndexMetaHelpers.HomebaseFileProcessor(dbm)

    // Per-message lock guarding the read-modify-write inside writeReactionToggle.
    // Without this, rapid taps on the bottom sheet have all five coroutines
    // read the same starting state, each compute "remove my one", and last-
    // writer-wins clobbers most of the removes. Per-(driveId, uniqueId) so
    // toggles on different messages don't queue against each other.
    private val reactionToggleLocks = mutableMapOf<Pair<Uuid, Uuid>, Mutex>()
    private val reactionToggleLocksGuard = Mutex()

    private suspend fun reactionLockFor(driveId: Uuid, uniqueId: Uuid): Mutex =
        reactionToggleLocksGuard.withLock {
            reactionToggleLocks.getOrPut(driveId to uniqueId) { Mutex() }
        }

    suspend fun writeNewFile(
        driveId: Uuid,
        keyHeader: KeyHeader,
        unecryptedMetadata: UploadFileMetadata,
        originalRecipientCount: Int,
        fileSystemType: FileSystemType,
        payloadDescriptors: List<PayloadDescriptor>? = null,
    ) {

        val credentials = credentialsManager.requireActiveCredentials()
        val domain = credentials.domain
        val created = UnixTimeUtc.now()

        val file = HomebaseFile(
            fileId = Uuid.random(),
            driveId = driveId,
            serverFileIsEncrypted = unecryptedMetadata.isEncrypted,
            fileState = FileState.Active,
            fileSystemType = fileSystemType,
            keyHeader = keyHeader,
            fileMetadata = FileMetadata(
                appData = AppFileMetaData(
                    uniqueId = unecryptedMetadata.appData.uniqueId,
                    tags = unecryptedMetadata.appData.tags,
                    fileType = unecryptedMetadata.appData.fileType,
                    dataType = unecryptedMetadata.appData.dataType,
                    groupId = unecryptedMetadata.appData.groupId,
                    userDate = unecryptedMetadata.appData.userDate,
                    content = unecryptedMetadata.appData.content,
                    previewThumbnail = unecryptedMetadata.appData.previewThumbnail,
                    archivalStatus = unecryptedMetadata.appData.archivalStatus
                ),
                localAppData = LocalAppMetadata(
                    tags = listOf(ChatProtocol.isPendingSendTag)
                ),
                created = created,
                updated = UnixTimeUtc.ZeroTime,
                isEncrypted = unecryptedMetadata.isEncrypted,
                senderOdinId = domain,
                originalAuthor = domain,
                versionTag = null,
                payloads = payloadDescriptors,
            ),
            serverMetadata = ServerMetadata(
                accessControlList = unecryptedMetadata.accessControlList ?: AccessControlList(
                    requiredSecurityGroup = "Owner"
                ),
                allowDistribution = unecryptedMetadata.allowDistribution,
                fileSystemType = fileSystemType,
                fileByteCount = 100,
                originalRecipientCount = originalRecipientCount,
                transferHistory = null
            ),
            priority = 100,
            fileByteCount = 100,
        )

        val batch = listOf(file)
        try {
            fileProcessor.baseUpsertEntryZapZap(
                identityId = credentials.getIdentityId(),
                driveId = driveId,
                fileHeaders = batch,
                cursor = null
            )

            eventBus.emit(
                BackendEvent.DataEvent.BatchReceived(
                    driveId = driveId,
                    batchData = batch,
                )
            )

        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Optimistic insert failed for uniqueId=${unecryptedMetadata.appData.uniqueId} groupId=${unecryptedMetadata.appData.groupId}" }
            throw e
        }
    }

    /**
     * Persist a local-only conversation-file placeholder for a conversation
     * that needs to surface in the UI before (or instead of) a real server
     * file. Live callers:
     *   - [id.homebase.chat.services.convo.ConversationService.recoverConversation]:
     *     explicit local recovery (e.g. orphan-branch fallback when a message
     *     arrived for a conversation whose definition file never synced).
     *
     * Strictly local: no `outboxSync.tryEnqueue`, no server distribution,
     * no `isPendingSendTag`. The row exists only so the conversation
     * survives an app restart — without it, the orphan's messages would
     * be stranded in DriveMainIndex with no UI surface.
     *
     * The `updated` field is deliberately set to [UnixTimeUtc.ZeroTime].
     * DriveMainIndex's upsert guard at DriveMainIndex.sq:68
     * (`WHERE excluded.modified > DriveMainIndex.modified`) only allows
     * an incoming row to replace an existing one when its modified is
     * strictly greater. Any non-zero server-side `modified` passes that
     * guard cleanly, so when a peer eventually creates the real
     * conversation file and we sync it down, the placeholder is replaced
     * in place (same uniqueId, new fileId/keyHeader/versionTag). A
     * non-zero `updated` here would silently block the real file and
     * make the "Conversation missing..." placeholder permanent.
     */
    suspend fun writeLocalOnlyConversationPlaceholder(
        driveId: Uuid,
        conversationId: Uuid,
        participants: List<OdinId>,
        isGroup: Boolean,
        title: String = "",
    ) {
        val credentials = credentialsManager.requireActiveCredentials()
        val domain = credentials.domain
        val created = UnixTimeUtc.now()

        val content = ConversationAppDataJson(
            title = title,
            recipients = participants,
            version = 1,
        )

        val file = HomebaseFile(
            fileId = Uuid.random(),
            driveId = driveId,
            serverFileIsEncrypted = true,
            fileState = FileState.Active,
            fileSystemType = FileSystemType.Standard,
            keyHeader = KeyHeader.newRandom16(),
            fileMetadata = FileMetadata(
                appData = AppFileMetaData(
                    uniqueId = conversationId,
                    tags = if (isGroup) listOf(ChatProtocol.ConversationGroupTag) else null,
                    fileType = ChatProtocol.ConversationFileType,
                    dataType = 0,
                    groupId = conversationId,
                    userDate = null,
                    content = OdinSystemSerializer.serialize(content),
                    previewThumbnail = null,
                    archivalStatus = null,
                ),
                localAppData = LocalAppMetadata(
                    tags = emptyList(),
                ),
                created = created,
                updated = UnixTimeUtc.ZeroTime,
                isEncrypted = true,
                senderOdinId = domain,
                originalAuthor = domain,
                versionTag = null,
                payloads = null,
            ),
            serverMetadata = ServerMetadata(
                accessControlList = AccessControlList(
                    requiredSecurityGroup = "Owner"
                ),
                allowDistribution = true,
                fileSystemType = FileSystemType.Standard,
                fileByteCount = 100,
                originalRecipientCount = participants.size,
                transferHistory = null,
            ),
            priority = 100,
            fileByteCount = 100,
        )

        try {
            fileProcessor.baseUpsertEntryZapZap(
                identityId = credentials.getIdentityId(),
                driveId = driveId,
                fileHeaders = listOf(file),
                cursor = null,
            )
            Logger.d(tag = TAG) {
                "writeLocalOnlyConversationPlaceholder: persisted uniqueId=$conversationId " +
                        "driveId=$driveId participants=${participants.size} isGroup=$isGroup"
            }
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) {
                "writeLocalOnlyConversationPlaceholder FAILED for uniqueId=$conversationId"
            }
            throw e
        }
    }

    /**
     * Persist a local-only admin-file placeholder for a group whose admin file
     * is missing or broken. Counterpart to [writeLocalOnlyConversationPlaceholder]
     * for the group's admin file.
     *
     * Used by the receive-side heal handler when the incoming heal message
     * carries `canonicalAdmins`. Strictly local: no outbox enqueue, no
     * `isPendingSendTag`. The row exists so [getAdmins] can read the canonical
     * admin set immediately, instead of falling back to `originalAuthor`.
     *
     * The placeholder uses the canonical [adminUniqueId] derived from the
     * conversation id (`ChatProtocol.getAdminFileUniqueId`) so a future peer
     * push of the real admin file replaces the placeholder by uniqueId.
     *
     * `versionTag = null` and `updated = ZeroTime` for the same reason as
     * [writeLocalOnlyConversationPlaceholder]: any non-zero `modified` here
     * would block the real file's upsert via DriveMainIndex.sq:68.
     */
    suspend fun writeLocalOnlyAdminPlaceholder(
        driveId: Uuid,
        conversationId: Uuid,
        admins: List<OdinId>,
    ) {
        val credentials = credentialsManager.requireActiveCredentials()
        val domain = credentials.domain
        val created = UnixTimeUtc.now()
        val adminUniqueId = ChatProtocol.getAdminFileUniqueId(conversationId)
        val content = OdinSystemSerializer.serialize(
            id.homebase.chat.services.convo.ConversationAdminInfo(admins = admins)
        )

        val file = HomebaseFile(
            fileId = Uuid.random(),
            driveId = driveId,
            serverFileIsEncrypted = true,
            fileState = FileState.Active,
            fileSystemType = FileSystemType.Standard,
            keyHeader = KeyHeader.newRandom16(),
            fileMetadata = FileMetadata(
                appData = AppFileMetaData(
                    uniqueId = adminUniqueId,
                    tags = null,
                    fileType = ChatProtocol.ConversationAdminFileType,
                    dataType = 0,
                    groupId = conversationId,
                    userDate = null,
                    content = content,
                    previewThumbnail = null,
                    archivalStatus = null,
                ),
                localAppData = LocalAppMetadata(
                    tags = emptyList(),
                ),
                created = created,
                updated = UnixTimeUtc.ZeroTime,
                isEncrypted = true,
                senderOdinId = domain,
                originalAuthor = domain,
                versionTag = null,
                payloads = null,
            ),
            serverMetadata = ServerMetadata(
                accessControlList = AccessControlList(
                    requiredSecurityGroup = "Owner"
                ),
                allowDistribution = true,
                fileSystemType = FileSystemType.Standard,
                fileByteCount = 100,
                originalRecipientCount = admins.size,
                transferHistory = null,
            ),
            priority = 100,
            fileByteCount = 100,
        )

        try {
            fileProcessor.baseUpsertEntryZapZap(
                identityId = credentials.getIdentityId(),
                driveId = driveId,
                fileHeaders = listOf(file),
                cursor = null,
            )
            Logger.i(tag = TAG) {
                "writeLocalOnlyAdminPlaceholder: persisted adminUniqueId=$adminUniqueId " +
                        "conversationId=$conversationId driveId=$driveId admins(${admins.size})=${admins.map { it.domainName }} fileId=${file.fileId}"
            }
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) {
                "writeLocalOnlyAdminPlaceholder FAILED for adminUniqueId=$adminUniqueId conversationId=$conversationId"
            }
            throw e
        }
    }

    suspend fun writeUpdate(
        driveId: Uuid,
        keyHeader: KeyHeader,
        unecryptedMetadata: UploadFileMetadata,
        payloadDescriptors: List<PayloadDescriptor>? = null,
    ) {

        val credentials = credentialsManager.requireActiveCredentials()
        val uid = unecryptedMetadata.appData.uniqueId
            ?: throw IllegalStateException("missing unique id")

        val existingFile = dbm.driveMainIndex.selectHomebaseFileByUnique(
            credentials.getIdentityId(), driveId, uid
        ) ?: throw IllegalStateException("no file by uid")

        val lastModified = existingFile.fileMetadata.updated.addMilliseconds(1)

        val existing = existingFile.fileMetadata.localAppData
        val existingTags = existing?.tags.orEmpty()

        // Skip re-adding when present: a stuck pending row would otherwise produce
        // a duplicate that violates DriveLocalTagIndex's UNIQUE constraint.
        val newLocalAppData = existing?.copy(
            tags = if (ChatProtocol.isPendingSendTag in existingTags) existingTags
                   else existingTags + ChatProtocol.isPendingSendTag
        ) ?: LocalAppMetadata(
            tags = listOf(ChatProtocol.isPendingSendTag)
        )

        val file = existingFile.copy(
            keyHeader = keyHeader,
            fileMetadata = existingFile.fileMetadata.copy(
                appData = AppFileMetaData(
                    uniqueId = unecryptedMetadata.appData.uniqueId,
                    tags = unecryptedMetadata.appData.tags,
                    fileType = unecryptedMetadata.appData.fileType,
                    dataType = unecryptedMetadata.appData.dataType,
                    groupId = unecryptedMetadata.appData.groupId,
                    userDate = unecryptedMetadata.appData.userDate,
                    content = unecryptedMetadata.appData.content,
                    previewThumbnail = unecryptedMetadata.appData.previewThumbnail,
                    archivalStatus = unecryptedMetadata.appData.archivalStatus
                ),
                localAppData = newLocalAppData,
                updated = lastModified,
                // Null preserves the existing payloads (description-only edits).
                // Non-null replaces them — used by the moments "placeholder → real"
                // promotion where the first write had no payloads and the second
                // installs the real encrypted set after thumbnail/encrypt finishes.
                payloads = payloadDescriptors ?: existingFile.fileMetadata.payloads,
            ),
            serverMetadata = ServerMetadata(
                accessControlList = unecryptedMetadata.accessControlList ?: AccessControlList(
                    requiredSecurityGroup = "Owner"
                ),
                allowDistribution = unecryptedMetadata.allowDistribution,
                fileByteCount = 100, // not sure how to handle this here
            ),
            priority = 100,
            fileByteCount = 100 // not sure how to handle this here
        )

        try {

            val batch = listOf(file)
            fileProcessor.baseUpsertEntryZapZap(
                identityId = credentials.getIdentityId(),
                driveId = driveId,
                fileHeaders = batch,
                cursor = null
            )

            eventBus.emit(
                BackendEvent.DataEvent.BatchReceived(
                    driveId = driveId,
                    batchData = batch,
                )
            )

        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Optimistic update failed for uniqueId=${unecryptedMetadata.appData.uniqueId}" }
            throw e
        }
    }

    /** Removes an optimistic file from the local DB. Call when the send fails so the
     *  pending message is not left stranded in the conversation list. */
    suspend fun removeOptimisticFile(driveId: Uuid, uniqueId: Uuid) {
        val credentials = credentialsManager.requireActiveCredentials()

        val file = dbm.driveMainIndex.selectHomebaseFileByUnique(
            credentials.getIdentityId(), driveId, uniqueId
        ) ?: return

        // Only remove files that are still pending — if the outbox already sent the message
        // the isPendingSendTag will have been removed, and we must not delete it.
        val isPending = file.fileMetadata.localAppData?.tags
            ?.contains(ChatProtocol.isPendingSendTag) == true
        if (!isPending) return

        try {
            fileProcessor.deleteEntryDriveMainIndex(
                identityId = credentials.getIdentityId(),
                driveId = driveId,
                fileId = file.fileId
            )
            eventBus.emit(
                BackendEvent.OutboxEvent.OptimisticRollback(
                    driveId = driveId,
                    uniqueId = uniqueId,
                )
            )
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Optimistic remove failed for uniqueId=$uniqueId" }
        }
    }

    /** Marks a file as deleted in the local DB and emits BatchReceived so the UI
     *  immediately shows it as "Deleted File" without waiting for the outbox.
     *  Returns the original file so the caller can rollback if the outbox enqueue fails. */
    suspend fun writeDelete(driveId: Uuid, uniqueId: Uuid): HomebaseFile? {
        val credentials = credentialsManager.requireActiveCredentials()

        val existingFile = dbm.driveMainIndex.selectHomebaseFileByUnique(
            credentials.getIdentityId(), driveId, uniqueId
        ) ?: return null

        val lastModified = existingFile.fileMetadata.updated.addMilliseconds(1)

        val deletedFile = existingFile.copy(
            fileState = FileState.Deleted,
            fileMetadata = existingFile.fileMetadata.copy(
                updated = lastModified,
                payloads = emptyList(),
                appData = existingFile.fileMetadata.appData.copy(
                    content = "",
                    previewThumbnail = null,
                    // Belt-and-suspenders: HomebaseFile.isSoftDeleted() checks BOTH
                    // markers and the defragmenter's SoftDeleteArchivalMismatch
                    // classifier asserts the two stay in sync. Unread-count SQL now
                    // filters on fileState directly, so this mirror is no longer
                    // required for that purpose — but removing it would make every
                    // fresh soft-delete look like drift the defragmenter would try
                    // to repair.
                    archivalStatus = ArchivalStatus.Removed,
                )
            )
        )

        try {
            val batch = listOf(deletedFile)
            fileProcessor.baseUpsertEntryZapZap(
                identityId = credentials.getIdentityId(),
                driveId = driveId,
                fileHeaders = batch,
                cursor = null
            )

            eventBus.emit(
                BackendEvent.DataEvent.BatchReceived(
                    driveId = driveId,
                    batchData = batch,
                )
            )
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Optimistic delete failed for uniqueId=$uniqueId" }
            return null
        }

        return existingFile
    }

    /** Restores a file that was optimistically deleted. Call when the outbox enqueue fails. */
    suspend fun rollbackWrite(driveId: Uuid, original: HomebaseFile) {
        val credentials = credentialsManager.requireActiveCredentials()
        try {
            val batch = listOf(original)
            fileProcessor.baseUpsertEntryZapZap(
                identityId = credentials.getIdentityId(),
                driveId = driveId,
                fileHeaders = batch,
                cursor = null
            )

            eventBus.emit(
                BackendEvent.DataEvent.BatchReceived(
                    driveId = driveId,
                    batchData = batch,
                )
            )
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Optimistic delete rollback failed for fileId=${original.fileId}" }
        }
    }

    /** Optimistically updates the reactionPreview AND localAppData.localReactions
     *  on a message and emits BatchReceived. Returns the original file for
     *  rollback, and the optimistic result type. */
    suspend fun writeReactionToggle(
        driveId: Uuid,
        uniqueId: Uuid,
        reactionJson: String,
    ): Pair<ToggleReactionResultType, HomebaseFile?> = reactionLockFor(driveId, uniqueId).withLock {
        val credentials = credentialsManager.requireActiveCredentials()

        val existingFile = dbm.driveMainIndex.selectHomebaseFileByUnique(
            credentials.getIdentityId(), driveId, uniqueId
        ) ?: return@withLock Pair(ToggleReactionResultType.None, null)

        val lastModified = existingFile.fileMetadata.updated.addMilliseconds(1)

        // isAdding is per-user, not per-aggregate: in groups, another user
        // having reacted with the same emoji must not flip our toggle into a
        // remove. localReactions is the per-user mirror; ChatMessageStream
        // decodes it into MessageUiModel.ownReactions on every BatchReceived,
        // so updating it here also closes the rapid-tap race against the cap.
        val currentLocalReactions =
            existingFile.fileMetadata.localAppData?.localReactions.orEmpty()
        val isAdding = reactionJson !in currentLocalReactions
        val updatedLocalReactions = if (isAdding) {
            currentLocalReactions + reactionJson
        } else {
            currentLocalReactions.filterNot { it == reactionJson }
        }

        val currentReactions =
            existingFile.fileMetadata.reactionPreview?.reactions.orEmpty().toMutableMap()
        // The map key is server-assigned and may differ from reactionJson, so find by value.
        val existingKey = currentReactions.entries
            .firstOrNull { it.value.reactionContent == reactionJson }
            ?.key
        val existing = existingKey?.let { currentReactions[it] }

        val updatedReactions = if (isAdding) {
            if (existing == null) {
                currentReactions[reactionJson] = ReactionEntry(
                    key = reactionJson,
                    count = 1,
                    reactionContent = reactionJson
                )
            } else {
                currentReactions[existingKey] = existing.copy(count = existing.count + 1)
            }
            currentReactions
        } else {
            if (existing != null) {
                val newCount = existing.count - 1
                if (newCount <= 0) currentReactions.remove(existingKey)
                else currentReactions[existingKey] = existing.copy(count = newCount)
            }
            currentReactions
        }

        val updatedFile = existingFile.copy(
            fileMetadata = existingFile.fileMetadata.copy(
                updated = lastModified,
                reactionPreview = (existingFile.fileMetadata.reactionPreview
                    ?: ReactionSummary()).copy(
                    reactions = updatedReactions
                ),
                localAppData = (existingFile.fileMetadata.localAppData
                    ?: LocalAppMetadata()).copy(
                    localReactions = updatedLocalReactions
                ),
            )
        )

        try {
            val batch = listOf(updatedFile)
            fileProcessor.baseUpsertEntryZapZap(
                identityId = credentials.getIdentityId(),
                driveId = driveId,
                fileHeaders = batch,
                cursor = null
            )

            eventBus.emit(
                BackendEvent.DataEvent.BatchReceived(
                    driveId = driveId,
                    batchData = batch,
                )
            )
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Optimistic reaction toggle failed for uniqueId=$uniqueId" }
            return@withLock Pair(ToggleReactionResultType.None, null)
        }

        val resultType = if (isAdding)
            ToggleReactionResultType.Added
        else
            ToggleReactionResultType.Deleted
        Pair(resultType, existingFile)
    }

    suspend fun stampConversationExitedAt(driveId: Uuid, conversationId: Uuid): UpdateLocalAppdataContentOutboxRequest? =
        stampConversationLocalAppData(driveId, conversationId, "stampConversationExitedAt") {
            it.copy(lastExitedAt = UnixTimeUtc())
        }

    suspend fun stampConversationLastReadTime(
        driveId: Uuid,
        conversationId: Uuid,
        newLastReadTime: UnixTimeUtc,
        latestMessageTimestamp: UnixTimeUtc? = null,
    ): UpdateLocalAppdataContentOutboxRequest? =
        stampConversationLocalAppData(driveId, conversationId, "stampConversationLastReadTime") {
            it.copy(
                lastReadTime = newLastReadTime,
                // Ride the list sort key along with the lastRead push — no separate
                // write. Monotonic so a device behind on sync can't stamp an older
                // last-message time over a newer one another device already pushed.
                latestMessageTimestamp = UnixTimeUtc.later(it.latestMessageTimestamp, latestMessageTimestamp),
            )
        }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun stampConversationLocalAppData(
        driveId: Uuid,
        conversationId: Uuid,
        opName: String,
        mutate: (ConversationLocalAppDataJson) -> ConversationLocalAppDataJson,
    ): UpdateLocalAppdataContentOutboxRequest? {
        Logger.d(tag = "MarkAsRead") {
            "OptimisticWriter.$opName: enter convo=$conversationId drive=$driveId"
        }
        val credentials = credentialsManager.requireActiveCredentials()

        val existingFile = dbm.driveMainIndex.selectHomebaseFileByUnique(
            credentials.getIdentityId(), driveId, conversationId
        ) ?: run {
            Logger.w(tag = "MarkAsRead") {
                "OptimisticWriter.$opName: convo=$conversationId NOT FOUND in DriveMainIndex — skipping"
            }
            return null
        }

        val existing = existingFile.fileMetadata.localAppData?.content?.let {
            try { OdinSystemSerializer.deserialize<ConversationLocalAppDataJson>(it) } catch (_: Throwable) { null }
        }
        val updatedLocalAppData = mutate(existing ?: ConversationLocalAppDataJson())
        val content = OdinSystemSerializer.serialize(updatedLocalAppData)

        val lastModified = existingFile.fileMetadata.updated.addMilliseconds(1)
        val updatedFile = existingFile.copy(
            fileMetadata = existingFile.fileMetadata.copy(
                localAppData = (existingFile.fileMetadata.localAppData ?: LocalAppMetadata()).copy(
                    content = content
                ),
                updated = lastModified
            )
        )

        // Pre-encrypt while we still have access to the key header. The outbox
        // processes this later, possibly after the participant-update has removed
        // us from the group — at which point getFileHeader returns isEncrypted=false
        // and the server rejects the update with "A string IV is required".
        val ivBase64: String?
        val encryptedContent: String?
        if (existingFile.serverFileIsEncrypted) {
            val iv = ByteArrayUtil.getRndByteArray(16)
            val keyHeader = KeyHeader(iv = iv, aesKey = existingFile.keyHeader.aesKey)
            val encrypted = keyHeader.encryptDataAes(content.encodeToByteArray())
            ivBase64 = Base64.encode(iv)
            encryptedContent = Base64.encode(encrypted)
        } else {
            ivBase64 = null
            encryptedContent = content
        }

        return try {
            val batch = listOf(updatedFile)
            fileProcessor.baseUpsertEntryZapZap(
                identityId = credentials.getIdentityId(),
                driveId = driveId,
                fileHeaders = batch,
                cursor = null
            )
            eventBus.emit(
                BackendEvent.DataEvent.BatchReceived(
                    driveId = driveId,
                    batchData = batch,
                )
            )
            Logger.d(tag = "MarkAsRead") {
                "OptimisticWriter.$opName: optimistic local upsert ok convo=$conversationId fileId=${existingFile.fileId} encrypted=${existingFile.serverFileIsEncrypted} → returning UpdateLocalAppdataContentOutboxRequest"
            }
            UpdateLocalAppdataContentOutboxRequest(
                driveId = driveId,
                fileId = existingFile.fileId,
                versionTag = null,
                content = encryptedContent,
                iv = ivBase64
            )
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "$opName failed for conversationId=$conversationId" }
            Logger.e(throwable = e, tag = "MarkAsRead") { "OptimisticWriter.$opName FAILED convo=$conversationId" }
            null
        }
    }

    /**
     * Blindly writes all given tags to the file's localAppData, replacing any existing tags.
     * Callers are responsible for merging with or removing from the existing tag list as needed.
     */
    suspend fun updateLocalTags(
        driveId: Uuid,
        uniqueId: Uuid,
        newTags: List<Uuid>
    ) {
        val credentials = credentialsManager.requireActiveCredentials()

        val existingFile = dbm.driveMainIndex.selectHomebaseFileByUnique(
            credentials.getIdentityId(), driveId, uniqueId
        ) ?: return

        val lastModified = existingFile.fileMetadata.updated.addMilliseconds(1)

        val updatedFile = existingFile.copy(
            fileMetadata = existingFile.fileMetadata.copy(
                localAppData = (existingFile.fileMetadata.localAppData ?: LocalAppMetadata()).copy(
                    tags = newTags.distinct()
                ),
                updated = lastModified
            )
        )

        try {
            val batch = listOf(updatedFile)
            fileProcessor.baseUpsertEntryZapZap(
                identityId = credentials.getIdentityId(),
                driveId = driveId,
                fileHeaders = batch,
                cursor = null
            )

            eventBus.emit(
                BackendEvent.DataEvent.BatchReceived(
                    driveId = driveId,
                    batchData = batch,
                )
            )
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Optimistic tag update failed for uniqueId=$uniqueId" }
        }
    }
}