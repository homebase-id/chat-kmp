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
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.api.sync.database.QueryBatch
import kotlin.uuid.Uuid

class OptimisticWriter(
    private val credentialsManager: CredentialsManager,
    private val dbm: DatabaseManager,
    private val eventBus: EventBus
) {
    private val fileProcessor: MainIndexMetaHelpers.HomebaseFileProcessor =
        MainIndexMetaHelpers.HomebaseFileProcessor(dbm)

    suspend fun writeNewFile(
        driveId: Uuid,
        keyHeader: KeyHeader,
        unecryptedMetadata: UploadFileMetadata,
        originalRecipientCount: Int,
        fileSystemType: FileSystemType
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
                created = created,
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
                created = existingFile.fileMetadata.created,
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