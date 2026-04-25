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
import id.homebase.api.client.drives.files.DeleteFilesByGroupIdOutboxRequest
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
import id.homebase.api.toBase64
import id.homebase.api.client.drives.upload.UpdateLocalMetadataContentOutboxRequest
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatProtocol
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import id.homebase.chat.services.convo.ConversationAppDataJson
import id.homebase.chat.services.convo.ConversationLocalAppDataJson
import kotlin.uuid.Uuid

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
                BackendEvent.DriveEvent.BatchReceived(
                    driveId = driveId,
                    totalCount = batch.size,
                    batchCount = batch.size,
                    latestModified = created,
                    batchData = batch,
                    source = BackendEvent.SyncSource.WebSocket
                )
            )

        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Optimistic insert failed for uniqueId=${unecryptedMetadata.appData.uniqueId} groupId=${unecryptedMetadata.appData.groupId}" }
            throw e
        }
    }

    /**
     * Persist a local-only conversation-file placeholder for an orphaned
     * conversation (one whose messages arrived but whose conversation file
     * never synced down). Called by the post-sync reconciliation in
     * [id.homebase.chat.services.convo.ConversationStream] after the chat
     * drive emits [BackendEvent.DriveEvent.Stopped] with any placeholders
     * still unresolved.
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
    ) {
        val credentials = credentialsManager.requireActiveCredentials()
        val domain = credentials.domain
        val created = UnixTimeUtc.now()

        val content = ConversationAppDataJson(
            title = "",
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

    suspend fun writeUpdate(
        driveId: Uuid,
        keyHeader: KeyHeader,
        unecryptedMetadata: UploadFileMetadata
    ) {

        val credentials = credentialsManager.requireActiveCredentials()
        val uid = unecryptedMetadata.appData.uniqueId
            ?: throw IllegalStateException("missing unique id")

        val existingFile = dbm.driveMainIndex.selectHomebaseFileByUnique(
            credentials.getIdentityId(), driveId, uid
        ) ?: throw IllegalStateException("no file by uid")

        val lastModified = existingFile.fileMetadata.updated.addMilliseconds(1)

        val existing = existingFile.fileMetadata.localAppData

        val newLocalAppData = existing?.copy(
            tags = existing.tags.orEmpty() + ChatProtocol.isPendingSendTag
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
                BackendEvent.DriveEvent.BatchReceived(
                    driveId = driveId,
                    totalCount = batch.size,
                    batchCount = batch.size,
                    latestModified = lastModified,
                    batchData = batch,
                    source = BackendEvent.SyncSource.WebSocket
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
                BackendEvent.DriveEvent.BatchReceived(
                    driveId = driveId,
                    totalCount = batch.size,
                    batchCount = batch.size,
                    latestModified = lastModified,
                    batchData = batch,
                    source = BackendEvent.SyncSource.DriveSync
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
                BackendEvent.DriveEvent.BatchReceived(
                    driveId = driveId,
                    totalCount = batch.size,
                    batchCount = batch.size,
                    latestModified = original.fileMetadata.updated,
                    batchData = batch,
                    source = BackendEvent.SyncSource.WebSocket
                )
            )
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Optimistic delete rollback failed for fileId=${original.fileId}" }
        }
    }

    /** Optimistically updates the reactionPreview on a message and emits BatchReceived.
     *  Returns the original file for rollback, and the optimistic result type. */
    suspend fun writeReactionToggle(
        driveId: Uuid,
        uniqueId: Uuid,
        reactionJson: String,
    ): Pair<ToggleReactionResultType, HomebaseFile?> {
        val credentials = credentialsManager.requireActiveCredentials()

        val existingFile = dbm.driveMainIndex.selectHomebaseFileByUnique(
            credentials.getIdentityId(), driveId, uniqueId
        ) ?: return Pair(ToggleReactionResultType.None, null)

        val lastModified = existingFile.fileMetadata.updated.addMilliseconds(1)
        val currentReactions =
            existingFile.fileMetadata.reactionPreview?.reactions.orEmpty().toMutableMap()

        // The map key is server-assigned and may differ from reactionJson, so find by value.
        val existingKey = currentReactions.entries
            .firstOrNull { it.value.reactionContent == reactionJson }
            ?.key
        val existing = existingKey?.let { currentReactions[it] }
        val isAdding = existing == null || existing.count == 0

        val updatedReactions = if (isAdding) {
            currentReactions[reactionJson] = ReactionEntry(
                key = reactionJson,
                count = 1,
                reactionContent = reactionJson
            )
            currentReactions
        } else {
            val newCount = existing.count - 1
            if (newCount <= 0) currentReactions.remove(existingKey)
            else currentReactions[existingKey] = existing.copy(count = newCount)
            currentReactions
        }

        val updatedFile = existingFile.copy(
            fileMetadata = existingFile.fileMetadata.copy(
                updated = lastModified,
                reactionPreview = (existingFile.fileMetadata.reactionPreview
                    ?: ReactionSummary()).copy(
                    reactions = updatedReactions
                )
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
                BackendEvent.DriveEvent.BatchReceived(
                    driveId = driveId,
                    totalCount = batch.size,
                    batchCount = batch.size,
                    latestModified = lastModified,
                    batchData = batch,
                    source = BackendEvent.SyncSource.DriveSync
                )
            )
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Optimistic reaction toggle failed for uniqueId=$uniqueId" }
            return Pair(ToggleReactionResultType.None, null)
        }

        val resultType = if (isAdding)
            ToggleReactionResultType.Added
        else
            ToggleReactionResultType.Deleted
        return Pair(resultType, existingFile)
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun stampConversationExitedAt(driveId: Uuid, conversationId: Uuid): UpdateLocalMetadataContentOutboxRequest? {
        val credentials = credentialsManager.requireActiveCredentials()

        val existingFile = dbm.driveMainIndex.selectHomebaseFileByUnique(
            credentials.getIdentityId(), driveId, conversationId
        ) ?: return null

        val existing = existingFile.fileMetadata.localAppData?.content?.let {
            try { OdinSystemSerializer.deserialize<ConversationLocalAppDataJson>(it) } catch (_: Throwable) { null }
        }
        val updatedLocalAppData = (existing ?: ConversationLocalAppDataJson())
            .copy(lastExitedAt = UnixTimeUtc())
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
                BackendEvent.DriveEvent.BatchReceived(
                    driveId = driveId,
                    totalCount = batch.size,
                    batchCount = batch.size,
                    latestModified = lastModified,
                    batchData = batch,
                    source = BackendEvent.SyncSource.DriveSync
                )
            )
            UpdateLocalMetadataContentOutboxRequest(
                driveId = driveId,
                fileId = existingFile.fileId,
                versionTag = null,
                content = encryptedContent,
                iv = ivBase64
            )
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "stampConversationExitedAt failed for conversationId=$conversationId" }
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
                BackendEvent.DriveEvent.BatchReceived(
                    driveId = driveId,
                    totalCount = batch.size,
                    batchCount = batch.size,
                    latestModified = lastModified,
                    batchData = batch,
                    source = BackendEvent.SyncSource.DriveSync
                )
            )
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Optimistic tag update failed for uniqueId=$uniqueId" }
        }
    }
}