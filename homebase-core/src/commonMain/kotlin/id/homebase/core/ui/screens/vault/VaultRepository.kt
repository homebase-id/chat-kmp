@file:OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)

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
import id.homebase.api.client.drives.upload.PayloadDeleteKey
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
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "VaultRepository"
private fun vaultPayloadKey(index: Int): String = "vlt_pg_${index.toString().padStart(2, '0')}"

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

    suspend fun uploadFile(
        entryName: String,
        files: List<Pair<String, String>>,
        scope: CoroutineScope,
        groupId: Uuid? = null,
        notes: String? = null,
    ): Uuid? {
        return try {
            val uniqueId = Uuid.random()
            val keyHeader = KeyHeader.newRandom16()

            val resolvedFiles = files.map { (path, contentType) ->
                fileOperationsProvider.resolveToFilePath(path) to contentType
            }

            val bundle = buildMultiPayloadBundle(resolvedFiles)

            val encryptedBundle = payloadEncryptionService.encryptBundle(
                uniqueId, bundle, keyHeader.aesKey, scope
            )

            val content = OdinSystemSerializer.serialize(
                VaultFileContent(name = entryName, notes = notes)
            )
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
            Logger.e(e, TAG) { "Failed to enqueue multi-payload upload: $entryName" }
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
        existingNotes: String?,
        versionTag: Uuid?,
        keyHeader: KeyHeader,
    ): Boolean {
        return try {
            val content = OdinSystemSerializer.serialize(VaultFileContent(name = newName, notes = existingNotes))
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

    suspend fun appendPages(
        file: VaultFileItem,
        newFiles: List<Pair<String, String>>,
        scope: CoroutineScope,
    ): Boolean {
        return try {
            val existingMaxIndex = file.payloadDescriptors
                .mapNotNull { it.key.removePrefix("vlt_pg_").toIntOrNull() }
                .maxOrNull() ?: -1
            val startIndex = existingMaxIndex + 1

            val resolvedFiles = newFiles.map { (path, contentType) ->
                fileOperationsProvider.resolveToFilePath(path) to contentType
            }

            val allPayloads = mutableListOf<PayloadFile>()
            val allThumbnails = mutableListOf<id.homebase.api.client.drives.files.ThumbnailFile>()

            resolvedFiles.forEachIndexed { i, (filePath, contentType) ->
                val key = vaultPayloadKey(startIndex + i)
                var previewThumbnail: EmbeddedThumb? = null
                var thumbnails = emptyList<id.homebase.api.client.drives.files.ThumbnailFile>()

                if (contentType.startsWith("image/")) {
                    try {
                        val result = MessageThumbnailGenerator.generate(
                            filePath, key, fileOperationsProvider,
                        )
                        previewThumbnail = result.preview
                        thumbnails = result.thumbnails
                    } catch (e: Exception) {
                        Logger.w(e, TAG) { "Thumbnail generation failed for append payload $key" }
                    }
                }

                allPayloads += PayloadFile(
                    key = key,
                    filePath = filePath,
                    contentType = contentType,
                    previewThumbnail = previewThumbnail,
                )
                allThumbnails += thumbnails
            }

            val keyHeader = file.keyHeader
            val encryptedBundle = payloadEncryptionService.encryptBundle(
                Uuid.random(), PayloadBundle(allPayloads, allThumbnails, emptyList()),
                keyHeader.aesKey, scope,
            )

            val metadata = UploadFileMetadata(
                allowDistribution = false,
                isEncrypted = true,
                appData = UploadAppFileMetaData(),
                versionTag = file.versionTag,
            )

            val request = UpdateFileByFileIdRequest(
                driveId = file.driveId,
                fileId = file.fileId,
                keyHeader = keyHeader,
                instructions = FileUpdateInstructionSet(
                    transferIv = ByteArrayUtil.getRndByteArray(16),
                    locale = UpdateLocale.Local,
                    recipients = emptyList(),
                    manifest = UpdateManifest.build(
                        payloads = encryptedBundle.payloads,
                        thumbnails = encryptedBundle.thumbnails,
                        generatePayloadIv = false,
                    ),
                ),
                metadata = metadata,
                payloads = encryptedBundle.payloads,
                thumbnails = encryptedBundle.thumbnails,
            )

            val result = uploadProvider.updateFileByFileId(request)
            result != null
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to append pages to ${file.fileId}" }
            false
        }
    }

    suspend fun deletePage(
        file: VaultFileItem,
        payloadKey: String,
    ): Boolean {
        return try {
            val isLastPage = file.payloadDescriptors.size <= 1
            if (isLastPage) {
                return deleteFile(file.fileId)
            }

            val metadata = UploadFileMetadata(
                allowDistribution = false,
                isEncrypted = true,
                appData = UploadAppFileMetaData(),
                versionTag = file.versionTag,
            )

            val request = UpdateFileByFileIdRequest(
                driveId = file.driveId,
                fileId = file.fileId,
                keyHeader = file.keyHeader,
                instructions = FileUpdateInstructionSet(
                    transferIv = ByteArrayUtil.getRndByteArray(16),
                    locale = UpdateLocale.Local,
                    recipients = emptyList(),
                    manifest = UpdateManifest.build(
                        toDeletePayloads = listOf(PayloadDeleteKey(payloadKey)),
                    ),
                ),
                metadata = metadata,
            )

            val result = uploadProvider.updateFileByFileId(request)
            result != null
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to delete page $payloadKey from ${file.fileId}" }
            false
        }
    }

    suspend fun updateNotes(
        file: VaultFileItem,
        notes: String?,
    ): Boolean {
        return try {
            val content = OdinSystemSerializer.serialize(
                VaultFileContent(name = file.fileName, notes = notes)
            )
            val metadata = UploadFileMetadata(
                allowDistribution = false,
                isEncrypted = true,
                appData = UploadAppFileMetaData(content = content),
                versionTag = file.versionTag,
            ).encryptContent(file.keyHeader)

            val request = UpdateFileByFileIdRequest(
                driveId = file.driveId,
                fileId = file.fileId,
                keyHeader = file.keyHeader,
                instructions = FileUpdateInstructionSet(
                    transferIv = ByteArrayUtil.getRndByteArray(16),
                    locale = UpdateLocale.Local,
                    recipients = emptyList(),
                    manifest = UpdateManifest.build(),
                ),
                metadata = metadata,
            )

            val result = uploadProvider.updateFileByFileId(request)
            result != null
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to update notes for ${file.fileId}" }
            false
        }
    }

    suspend fun downloadPayload(file: VaultFileItem, payloadKey: String): String? {
        return try {
            val payloadDescriptor = file.payloadDescriptors.find { it.key == payloadKey }
            val iv = payloadDescriptor?.iv
            val keyHeader = if (iv != null) {
                try {
                    KeyHeader(Base64.decode(iv), file.keyHeader.aesKey)
                } catch (_: Exception) {
                    file.keyHeader
                }
            } else {
                file.keyHeader
            }

            val bytes = driveFileProvider.getPayloadBytesDecrypted(
                driveId = file.driveId,
                fileId = file.fileId,
                key = payloadKey,
                keyHeader = keyHeader,
            )?.bytes ?: return null

            val ct = payloadDescriptor?.contentType ?: file.contentType
            val extension = ct.substringAfter("/", "bin").let {
                if (it == "jpeg") "jpg" else it
            }
            fileOperationsProvider.writeBytesToTempFile(bytes, "share_", ".$extension")
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to download payload $payloadKey from ${file.fileId}" }
            null
        }
    }

    private suspend fun buildMultiPayloadBundle(
        files: List<Pair<String, String>>,
    ): PayloadBundle {
        val allPayloads = mutableListOf<PayloadFile>()
        val allThumbnails = mutableListOf<id.homebase.api.client.drives.files.ThumbnailFile>()
        val allPreviews = mutableListOf<EmbeddedThumb>()

        files.forEachIndexed { index, (filePath, contentType) ->
            val key = vaultPayloadKey(index)
            var previewThumbnail: EmbeddedThumb? = null
            var thumbnails = emptyList<id.homebase.api.client.drives.files.ThumbnailFile>()

            if (contentType.startsWith("image/")) {
                try {
                    val result = MessageThumbnailGenerator.generate(
                        filePath, key, fileOperationsProvider,
                    )
                    previewThumbnail = result.preview
                    thumbnails = result.thumbnails
                } catch (e: Exception) {
                    Logger.w(e, TAG) { "Thumbnail generation failed for payload $key" }
                }
            }

            allPayloads += PayloadFile(
                key = key,
                filePath = filePath,
                contentType = contentType,
                previewThumbnail = previewThumbnail,
            )
            allThumbnails += thumbnails
            if (previewThumbnail != null) allPreviews += previewThumbnail
        }

        return PayloadBundle(
            payloads = allPayloads,
            thumbnails = allThumbnails,
            previewThumbs = allPreviews,
        )
    }
}
