package id.homebase.chat.services.outbox

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
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
import id.homebase.api.sync.database.QueryBatch
import id.homebase.api.toBase64
import id.homebase.api.client.drives.upload.UpdateLocalMetadataContentOutboxRequest
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.services.ChatProtocol
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import id.homebase.chat.services.convo.ConversationLocalAppDataJson
import kotlin.uuid.Uuid

class OptimisticWriter(
    private val credentialsManager: CredentialsManager,
    private val dbm: DatabaseManager,
    private val eventBus: EventBus,
) {
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
            Logger.e("Optimistic insert failed: ${e.message}")
        }
    }

    suspend fun writeUpdate(
        driveId: Uuid,
        keyHeader: KeyHeader,
        unecryptedMetadata: UploadFileMetadata
    ) {

        val credentials = credentialsManager.requireActiveCredentials()
        val queryBatch = QueryBatch(credentials.getIdentityId())
        val uid = unecryptedMetadata.appData.uniqueId
            ?: throw IllegalStateException("missing unique id")

        val result = queryBatch.queryBatchAsync(
            dbm = dbm,
            driveId = driveId,
            noOfItems = 1,
            cursor = null,
            sortOrder = QueryBatchSortOrder.NewestFirst,
            sortField = QueryBatchSortField.CreatedDate,
            fileSystemType = 0,
            uniqueIdAnyOf = listOf(uid)
        )

        val existingFile = result.records.singleOrNull()
            ?: throw IllegalStateException("no file by uid")

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
            Logger.e("Optimistic update failed: ${e.message}")
        }
    }

    /** Removes an optimistic file from the local DB. Call when the send fails so the
     *  pending message is not left stranded in the conversation list. */
    suspend fun removeOptimisticFile(driveId: Uuid, uniqueId: Uuid) {
        val credentials = credentialsManager.requireActiveCredentials()
        val queryBatch = QueryBatch(credentials.getIdentityId())

        val result = queryBatch.queryBatchAsync(
            dbm = dbm,
            driveId = driveId,
            noOfItems = 1,
            cursor = null,
            sortOrder = QueryBatchSortOrder.NewestFirst,
            sortField = QueryBatchSortField.CreatedDate,
            fileSystemType = 0,
            uniqueIdAnyOf = listOf(uniqueId)
        )

        val file = result.records.singleOrNull() ?: return

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
            Logger.e("Optimistic remove failed: ${e.message}")
        }
    }

    /** Marks a file as deleted in the local DB and emits BatchReceived so the UI
     *  immediately shows it as "Deleted File" without waiting for the outbox.
     *  Returns the original file so the caller can rollback if the outbox enqueue fails. */
    suspend fun writeDelete(driveId: Uuid, uniqueId: Uuid): HomebaseFile? {
        val credentials = credentialsManager.requireActiveCredentials()
        val queryBatch = QueryBatch(credentials.getIdentityId())

        val result = queryBatch.queryBatchAsync(
            dbm = dbm,
            driveId = driveId,
            noOfItems = 1,
            cursor = null,
            sortOrder = QueryBatchSortOrder.NewestFirst,
            sortField = QueryBatchSortField.CreatedDate,
            fileSystemType = 0,
            uniqueIdAnyOf = listOf(uniqueId)
        )

        val existingFile = result.records.singleOrNull() ?: return null

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
            Logger.e("Optimistic delete failed: ${e.message}")
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
            Logger.e("Optimistic delete rollback failed: ${e.message}")
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
        val queryBatch = QueryBatch(credentials.getIdentityId())

        val result = queryBatch.queryBatchAsync(
            dbm = dbm,
            driveId = driveId,
            noOfItems = 1,
            cursor = null,
            sortOrder = QueryBatchSortOrder.NewestFirst,
            sortField = QueryBatchSortField.CreatedDate,
            fileSystemType = 0,
            uniqueIdAnyOf = listOf(uniqueId)
        )

        val existingFile = result.records.singleOrNull()
            ?: return Pair(ToggleReactionResultType.None, null)

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
            Logger.e("Optimistic reaction toggle failed: ${e.message}")
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
        val queryBatch = QueryBatch(credentials.getIdentityId())

        val result = queryBatch.queryBatchAsync(
            dbm = dbm,
            driveId = driveId,
            noOfItems = 1,
            cursor = null,
            sortOrder = QueryBatchSortOrder.NewestFirst,
            sortField = QueryBatchSortField.CreatedDate,
            fileSystemType = 0,
            uniqueIdAnyOf = listOf(conversationId)
        )

        val existingFile = result.records.singleOrNull() ?: return null

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
                versionTag = existingFile.fileMetadata.localAppData?.versionTag?.toString(),
                content = encryptedContent,
                iv = ivBase64
            )
        } catch (e: Exception) {
            Logger.e("stampConversationExitedAt failed: ${e.message}")
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
        val queryBatch = QueryBatch(credentials.getIdentityId())

        val result = queryBatch.queryBatchAsync(
            dbm = dbm,
            driveId = driveId,
            noOfItems = 1,
            cursor = null,
            sortOrder = QueryBatchSortOrder.NewestFirst,
            sortField = QueryBatchSortField.CreatedDate,
            fileSystemType = 0,
            uniqueIdAnyOf = listOf(uniqueId)
        )

        val existingFile = result.records.singleOrNull() ?: return

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
            Logger.e("Optimistic tag update failed: ${e.message}")
        }
    }
}