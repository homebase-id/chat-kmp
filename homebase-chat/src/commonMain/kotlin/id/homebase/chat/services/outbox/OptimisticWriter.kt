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
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.QueryBatch
import id.homebase.chat.services.PayloadBundle
import id.homebase.chat.services.PayloadBundleEncryptionService
import kotlinx.coroutines.CoroutineScope
import kotlin.uuid.Uuid

class OptimisticWriter(
    private val credentialsManager: CredentialsManager,
    private val outboxSync: OutboxSync,
    private val dbm: DatabaseManager,
    private val payloadBundleEncryptionService: PayloadBundleEncryptionService,
    private val eventBus: EventBus
) {

    private val fileProcessor: MainIndexMetaHelpers.HomebaseFileProcessor =
        MainIndexMetaHelpers.HomebaseFileProcessor(dbm)

    /** Enqueues a new file to the outbox and writes it to the local sync upon success **/
    suspend fun tryEnqueueCreateNewFile(
        driveId: Uuid,
        uniqueId: Uuid,
        dependencyUniqueId: Uuid?,
        keyHeader: KeyHeader,
        unecryptedMetadata: UploadFileMetadata,
        unencryptedPayloadBundle: PayloadBundle? = null,
        transitOptions: TransitOptions? = null,
        fileSystemType: FileSystemType = FileSystemType.Standard,
        scope: CoroutineScope
    ): Boolean {

        val encryptedBundle =
            payloadBundleEncryptionService.encryptBundle(
                uniqueId,
                unencryptedPayloadBundle,
                keyHeader.aesKey,
                scope
            )

        val encryptedRequest = UploadFileRequest(
            driveId = driveId,
            keyHeader = keyHeader,
            metadata = unecryptedMetadata.encryptContent(keyHeader),
            payloads = encryptedBundle.payloads,
            thumbnails = encryptedBundle.thumbnails,
            transitOptions = transitOptions,
            fileSystemType = fileSystemType
        )

        // enqueue the encrypted request
        val enqueued = outboxSync.tryEnqueue(
            driveId,
            uniqueId,
            dependencyUniqueId = dependencyUniqueId,
            priority = 1,
            uploadType = DriveOutboxUploader.UploadNewFile,
            json = OdinSystemSerializer.serialize(encryptedRequest),
        )

        if (enqueued) {
            writeOptimisticFile(
                driveId = driveId,
                keyHeader = keyHeader,
                unecryptedMetadata = unecryptedMetadata,
                originalRecipientCount = transitOptions?.recipients?.size ?: 0,
                fileSystemType = fileSystemType
            )

            //TODO: need to handle payloads

            outboxSync.send()
            return true
        }

        return false
    }

    suspend fun tryEnqueueUpdateFileByUniqueId(
        driveId: Uuid,
        uniqueId: Uuid,
        dependencyUniqueId: Uuid?,
        keyHeader: KeyHeader,
        unecryptedMetadata: UploadFileMetadata,
        unencryptedPayloadBundle: PayloadBundle? = null,
        recipients: List<OdinId>,
        scope: CoroutineScope
    ): Boolean {

        val domain = credentialsManager.getActiveDomain()

        val encryptedBundle =
            payloadBundleEncryptionService.encryptBundle(
                uniqueId,
                unencryptedPayloadBundle,
                keyHeader.aesKey,
                scope
            )

        val others = recipients.filter { it != domain }

        val instructions =
            FileUpdateInstructionSet(
                transferIv = ByteArrayUtil.getRndByteArray(16),
                locale = UpdateLocale.Local,
                recipients = others,
                manifest = UpdateManifest.build(
                    payloads = encryptedBundle.payloads,
                    toDeletePayloads = null,
                    thumbnails = encryptedBundle.thumbnails,
                    generatePayloadIv = false
                )
            )

        val encryptedRequest =
            UpdateFileByUniqueIdRequest(
                driveId = driveId,
                uniqueId = uniqueId,
                keyHeader = keyHeader,
                instructions = instructions,
                metadata = unecryptedMetadata.encryptContent(keyHeader),
                payloads = encryptedBundle.payloads,
                thumbnails = encryptedBundle.thumbnails
            )


        // enqueue the encrypted request
        val enqueued = outboxSync.tryEnqueue(
            driveId,
            uniqueId,
            dependencyUniqueId = dependencyUniqueId,
            priority = 1,
            uploadType = DriveOutboxUploader.UploadNewFile,
            json = OdinSystemSerializer.serialize(encryptedRequest),
        )

        if (enqueued) {
            writeOptimisticUpdate(
                driveId = driveId,
                keyHeader = keyHeader,
                unecryptedMetadata = unecryptedMetadata
            )

            //TODO: need to handle payloads

            outboxSync.send()
            return true
        }

        return false
    }

    private suspend fun writeOptimisticFile(
        driveId: Uuid,
        keyHeader: KeyHeader,
        unecryptedMetadata: UploadFileMetadata,
        originalRecipientCount: Int,
        fileSystemType: FileSystemType
    ) {

        val credentials = credentialsManager.requireActiveCredentials()
        val domain = credentials.domain

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
                created = UnixTimeUtc.now(),
                updated = UnixTimeUtc.ZeroTime,
                isEncrypted = unecryptedMetadata.isEncrypted,
                senderOdinId = domain,
                originalAuthor = domain,
                versionTag = null
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
        } catch (e: Exception) {
            Logger.e("Optimistic insert failed: ${e.message}")
        }
    }

    private suspend fun writeOptimisticUpdate(
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

        val lastModified = UnixTimeUtc.now().addSeconds(-1)
        val file = existingFile.copy(
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
                created = UnixTimeUtc.now(),
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
}