@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.vault

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.files.DeleteFilesByGroupIdOutboxRequest
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
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.QueryBatch
import id.homebase.chat.services.PayloadBundle
import id.homebase.chat.services.PayloadBundleEncryptionService
import id.homebase.chat.services.builder.MessageThumbnailGenerator
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.config.vaultLabeledDrive
import kotlinx.coroutines.CoroutineScope
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "VaultRepository"
private const val VAULT_PAYLOAD_KEY = "vault_pld"

data class VaultData(
    val sections: List<Pair<HomebaseFile, VaultSectionContent>>,
    val filesBySection: Map<Uuid, List<VaultFileItem>>,
)

class VaultRepository(
    private val databaseManager: DatabaseManager,
    private val uploadProvider: DriveUploadProvider,
    private val credentialsManager: CredentialsManager,
    private val fileOperationsProvider: FileOperationsProvider,
    private val outboxSync: OutboxSync,
    private val payloadEncryptionService: PayloadBundleEncryptionService,
    private val driveFileProvider: DriveFileProvider,
    private val optimisticWriter: OptimisticWriter,
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

    suspend fun createSection(
        sectionId: Uuid,
        sectionContent: VaultSectionContent,
    ): Boolean {
        return try {
            val keyHeader = KeyHeader.newRandom16()
            val content = OdinSystemSerializer.serialize(sectionContent)
            val unencryptedMetadata = UploadFileMetadata(
                allowDistribution = false,
                isEncrypted = true,
                appData = UploadAppFileMetaData(
                    uniqueId = sectionId,
                    content = content,
                    fileType = VAULT_SECTION_TYPE,
                ),
            )

            optimisticWriter.writeNewFile(
                driveId = driveId,
                keyHeader = keyHeader,
                unecryptedMetadata = unencryptedMetadata,
                originalRecipientCount = 0,
                fileSystemType = FileSystemType.Standard,
            )

            val request = UploadFileRequest(
                driveId = driveId,
                keyHeader = keyHeader,
                metadata = unencryptedMetadata.encryptContent(keyHeader),
            )
            outboxSync.tryEnqueue(request)
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to create vault section: ${sectionContent.title}" }
            false
        }
    }

    suspend fun loadAllVaultData(): VaultData {
        val creds = credentialsManager.getActiveCredentials()
            ?: return VaultData(emptyList(), emptyMap())
        val identityId = creds.getIdentityId()
        val queryBatch = QueryBatch(identityId)

        return try {
            val sectionResult = queryBatch.queryBatchAsync(
                dbm = databaseManager,
                driveId = driveId,
                noOfItems = 100,
                sortOrder = QueryBatchSortOrder.NewestFirst,
                sortField = QueryBatchSortField.CreatedDate,
                fileSystemType = FileSystemType.Standard.value,
                filetypesAnyOf = listOf(VAULT_SECTION_TYPE),
            )
            val sections = sectionResult.records.mapNotNull { file ->
                val sectionContent = file.toVaultSection() ?: return@mapNotNull null
                file to sectionContent
            }

            val fileResult = queryBatch.queryBatchAsync(
                dbm = databaseManager,
                driveId = driveId,
                noOfItems = 1000,
                sortOrder = QueryBatchSortOrder.NewestFirst,
                sortField = QueryBatchSortField.CreatedDate,
                fileSystemType = FileSystemType.Standard.value,
                filetypesAnyOf = listOf(VAULT_FILE_TYPE),
            )
            val allFiles = fileResult.records.mapNotNull { it.toVaultFileItem() }

            val filesBySection = allFiles
                .filter { it.groupId != null }
                .groupBy { it.groupId!! }

            VaultData(sections, filesBySection)
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to load vault data" }
            VaultData(emptyList(), emptyMap())
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
        groupId: Uuid? = null,
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
                    groupId = groupId,
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

    suspend fun deleteSection(sectionUniqueId: Uuid, sectionFileId: Uuid): Boolean {
        return try {
            outboxSync.tryEnqueue(
                request = DeleteFilesByGroupIdOutboxRequest(
                    driveId = driveId,
                    groupIds = listOf(sectionUniqueId),
                ),
            )
            outboxSync.tryEnqueue(
                request = DeleteLocalFilesByFileIdRequest(
                    driveId = driveId,
                    fileIds = listOf(sectionFileId),
                ),
            )
            true
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to delete vault section: $sectionUniqueId" }
            false
        }
    }

    suspend fun updateSection(
        sectionFileId: Uuid,
        sectionContent: VaultSectionContent,
        versionTag: Uuid?,
        keyHeader: KeyHeader,
    ): Boolean {
        return try {
            val content = OdinSystemSerializer.serialize(sectionContent)
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
                fileId = sectionFileId,
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
            Logger.e(e, TAG) { "Failed to update vault section: $sectionFileId" }
            false
        }
    }

    suspend fun downloadFileForShare(file: VaultFileItem): String? {
        return try {
            val bytes = driveFileProvider.getPayloadBytesDecrypted(
                driveId = file.driveId,
                fileId = file.fileId,
                key = file.payloadKey,
                keyHeader = file.payloadKeyHeader,
            )?.bytes ?: return null

            val extension = file.contentType.substringAfter("/", "bin").let {
                if (it == "jpeg") "jpg" else it
            }
            fileOperationsProvider.writeBytesToTempFile(bytes, "share_", ".$extension")
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to download vault file for share: ${file.fileId}" }
            null
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
