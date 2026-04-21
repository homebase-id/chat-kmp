@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.vault

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.UpdateFileByFileIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.QueryBatch
import id.homebase.chat.services.PayloadBundle
import id.homebase.chat.services.PayloadBundleEncryptionService
import id.homebase.chat.services.builder.MessageThumbnailGenerator
import id.homebase.core.config.vaultLabeledDrive
import kotlinx.coroutines.CoroutineScope
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "VaultRepository"
private const val VAULT_PAYLOAD_KEY = "vault_pld"

class VaultRepository(
    private val databaseManager: DatabaseManager,
    private val uploadProvider: DriveUploadProvider,
    private val credentialsManager: CredentialsManager,
    private val fileOperationsProvider: FileOperationsProvider,
    private val outboxSync: OutboxSync,
    private val payloadEncryptionService: PayloadBundleEncryptionService,
) {
    private val driveId = vaultLabeledDrive.drive.alias

    suspend fun loadFiles(): List<VaultFileItem> {
        val creds = credentialsManager.getActiveCredentials() ?: return emptyList()
        val identityId = creds.getIdentityId()
        val queryBatch = QueryBatch(identityId)

        return try {
            val result = queryBatch.queryBatchAsync(
                dbm = databaseManager,
                driveId = driveId,
                noOfItems = 1000,
                sortOrder = QueryBatchSortOrder.NewestFirst,
                sortField = QueryBatchSortField.CreatedDate,
                fileSystemType = FileSystemType.Standard.value,
            )
            result.records.mapNotNull { it.toVaultFileItem() }
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to load vault files" }
            emptyList()
        }
    }

    /**
     * Enqueues a file upload to the outbox for reliable delivery.
     * Pre-encrypts payload and thumbnails via [PayloadBundleEncryptionService],
     * matching the chat upload pipeline exactly.
     *
     * @return The uniqueId for tracking outbox progress events, or null on failure.
     */
    suspend fun uploadFile(
        fileName: String,
        contentType: String,
        filePath: String,
        scope: CoroutineScope,
    ): Uuid? {
        return try {
            val uniqueId = Uuid.random()
            val keyHeader = KeyHeader.newRandom16()

            val resolvedPath = fileOperationsProvider.resolveToFilePath(filePath)

            // Build unencrypted payload bundle (thumbnails generated for images)
            val bundle = buildPayloadBundle(resolvedPath, contentType)

            // Encrypt payloads + thumbnails using existing service
            val encryptedBundle = payloadEncryptionService.encryptBundle(
                uniqueId, bundle, keyHeader.aesKey, scope
            )

            // Encrypt metadata content
            val content = OdinSystemSerializer.serialize(VaultFileContent(name = fileName))
            val metadata = UploadFileMetadata(
                allowDistribution = false,
                isEncrypted = true,
                appData = UploadAppFileMetaData(
                    uniqueId = uniqueId,
                    content = content,
                    fileType = VAULT_FILE_TYPE,
                    previewThumbnail = encryptedBundle.previewThumbs.firstOrNull(),
                ),
            ).encryptContent(keyHeader)

            val request = UploadFileRequest(
                driveId = driveId,
                keyHeader = keyHeader,
                metadata = metadata,
                payloads = encryptedBundle.payloads,
                thumbnails = encryptedBundle.thumbnails,
            )

            val enqueued = outboxSync.tryEnqueue(request)
            if (enqueued) uniqueId else null
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to enqueue vault file upload: $fileName" }
            null
        }
    }

    suspend fun deleteFile(fileId: Uuid): Boolean {
        return try {
            outboxSync.tryEnqueue(
                request = DeleteLocalFilesByFileIdRequest(
                    driveId = driveId,
                    fileIds = listOf(fileId),
                ),
            )
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to enqueue vault file delete: $fileId" }
            false
        }
    }

    suspend fun renameFile(
        fileId: Uuid,
        newName: String,
        versionTag: Uuid?,
        keyHeader: KeyHeader,
    ): Boolean {
        return try {
            val content = OdinSystemSerializer.serialize(VaultFileContent(name = newName))
            val metadata = UploadFileMetadata(
                allowDistribution = false,
                isEncrypted = true,
                appData = UploadAppFileMetaData(
                    content = content,
                ),
                versionTag = versionTag,
            ).encryptContent(keyHeader)

            val request = UpdateFileByFileIdRequest(
                driveId = driveId,
                fileId = fileId,
                keyHeader = keyHeader,
                instructions = FileUpdateInstructionSet(
                    transferIv = ByteArrayUtil.getRndByteArray(16),
                    locale = UpdateLocale.Local,
                    recipients = emptyList(),
                    manifest = UpdateManifest.build(
                        payloads = null,
                        toDeletePayloads = null,
                        thumbnails = null,
                        generatePayloadIv = false,
                    ),
                ),
                metadata = metadata,
            )

            val result = uploadProvider.updateFileByFileId(request)
            result != null
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to rename vault file: $fileId -> $newName" }
            false
        }
    }

    private suspend fun buildPayloadBundle(
        filePath: String,
        contentType: String,
    ): PayloadBundle {
        var previewThumbnail: EmbeddedThumb? = null
        var thumbnails = emptyList<id.homebase.api.client.drives.files.ThumbnailFile>()

        if (contentType.startsWith("image/")) {
            try {
                val result = MessageThumbnailGenerator.generate(
                    filePath, VAULT_PAYLOAD_KEY, fileOperationsProvider,
                )
                previewThumbnail = result.preview
                thumbnails = result.thumbnails
            } catch (e: Exception) {
                Logger.w(e, TAG) { "Thumbnail generation failed, uploading without thumbnails" }
            }
        }

        val payload = PayloadFile(
            key = VAULT_PAYLOAD_KEY,
            filePath = filePath,
            contentType = contentType,
            previewThumbnail = previewThumbnail,
        )

        return PayloadBundle(
            payloads = listOf(payload),
            thumbnails = thumbnails,
            previewThumbs = listOfNotNull(previewThumbnail),
        )
    }
}
