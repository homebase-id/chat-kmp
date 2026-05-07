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
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.PayloadDeleteKey
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.convertHeicToJpeg
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.QueryBatch
import id.homebase.chat.services.PayloadBundle
import id.homebase.chat.services.PayloadBundleEncryptionService
import id.homebase.chat.services.builder.MessageThumbnailGenerator
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.config.vaultLabeledDrive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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
    private val credentialsManager: CredentialsManager,
    private val fileOperationsProvider: FileOperationsProvider,
    private val outboxSync: OutboxSync,
    private val payloadEncryptionService: PayloadBundleEncryptionService,
    private val driveFileProvider: DriveFileProvider,
    private val optimisticWriter: OptimisticWriter,
    private val eventBus: EventBus,
) {
    private val driveId = vaultLabeledDrive.drive.alias

    fun observeVaultData(): Flow<VaultData> {
        val vaultEvents = eventBus.events.filter { event ->
            (event is BackendEvent.DriveEvent.BatchReceived && event.driveId == driveId) || (event is BackendEvent.OutboxEvent.ItemCompleted && event.driveId == driveId) || (event is BackendEvent.OutboxEvent.ItemFailed && event.driveId == driveId) || (event is BackendEvent.OutboxEvent.OutboxItemDropped && event.driveId == driveId)
        }
        return merge(flowOf(Unit), vaultEvents.map { }).conflate().map { loadAllVaultData() }
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

            val request = UploadFileRequest(
                driveId = driveId,
                keyHeader = keyHeader,
                metadata = unencryptedMetadata.encryptContent(keyHeader),
            )
            val enqueued = outboxSync.tryEnqueue(request)
            if (enqueued) {
                try {
                    optimisticWriter.writeNewFile(
                        driveId = driveId,
                        keyHeader = keyHeader,
                        unecryptedMetadata = unencryptedMetadata,
                        originalRecipientCount = 0,
                        fileSystemType = FileSystemType.Standard,
                    )
                } catch (e: Exception) {
                    Logger.e(
                        e, TAG
                    ) { "Optimistic write failed (non-fatal) for section: ${sectionContent.title}" }
                }
            }
            enqueued
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to create vault section: ${sectionContent.title}" }
            false
        }
    }

    suspend fun loadAllVaultData(): VaultData {
        val creds =
            credentialsManager.getActiveCredentials() ?: return VaultData(emptyList(), emptyMap())
        val identityId = creds.getIdentityId()
        val queryBatch = QueryBatch(identityId)

        return try {
            val result = queryBatch.queryBatchAsync(
                dbm = databaseManager,
                driveId = driveId,
                noOfItems = 1100,
                sortOrder = QueryBatchSortOrder.NewestFirst,
                sortField = QueryBatchSortField.CreatedDate,
                fileSystemType = FileSystemType.Standard.value,
                filetypesAnyOf = listOf(VAULT_SECTION_TYPE, VAULT_FILE_TYPE),
            )

            val (sectionRecords, fileRecords) = result.records.partition {
                it.fileMetadata.appData.fileType == VAULT_SECTION_TYPE
            }

            val sections = sectionRecords.mapNotNull { file ->
                val sectionContent = file.toVaultSection() ?: return@mapNotNull null
                file to sectionContent
            }

            val allFiles = fileRecords.mapNotNull { it.toVaultFileItem() }

            val filesBySection = allFiles.filter { it.groupId != null }.groupBy { it.groupId!! }

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
                val resolved = fileOperationsProvider.resolveToFilePath(path)
                convertHeicIfNeeded(resolved, contentType)
            }

            val bundle = buildMultiPayloadBundle(resolvedFiles)

            val encryptedBundle = payloadEncryptionService.encryptBundle(
                uniqueId, bundle, keyHeader.aesKey, scope
            )

            val content = OdinSystemSerializer.serialize(
                VaultFileContent(name = entryName, notes = notes)
            )
            val unencryptedMetadata = UploadFileMetadata(
                allowDistribution = false,
                isEncrypted = true,
                appData = UploadAppFileMetaData(
                    uniqueId = uniqueId,
                    content = content,
                    fileType = VAULT_FILE_TYPE,
                    groupId = groupId,
                    previewThumbnail = encryptedBundle.previewThumbs.firstOrNull(),
                ),
            )

            val payloadDescriptors = encryptedBundle.payloads.map { payload ->
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

            val request = UploadFileRequest(
                driveId = driveId,
                keyHeader = keyHeader,
                metadata = unencryptedMetadata.encryptContent(keyHeader),
                payloads = encryptedBundle.payloads,
                thumbnails = encryptedBundle.thumbnails,
            )

            val enqueued = outboxSync.tryEnqueue(request)
            if (enqueued) {
                try {
                    optimisticWriter.writeNewFile(
                        driveId = driveId,
                        keyHeader = keyHeader,
                        unecryptedMetadata = unencryptedMetadata,
                        originalRecipientCount = 0,
                        fileSystemType = FileSystemType.Standard,
                        payloadDescriptors = payloadDescriptors,
                    )
                    Logger.d(tag = TAG) { "Optimistic write complete: $entryName uniqueId=$uniqueId" }
                } catch (e: Exception) {
                    Logger.e(e, TAG) { "Optimistic write failed (non-fatal): $entryName" }
                }
                uniqueId
            } else {
                null
            }
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to enqueue multi-payload upload: $entryName" }
            null
        }
    }

    // TODO: Add optimistic deletes with rollback (snapshot → writeDelete → enqueue → rollback on failure)
    suspend fun deleteFile(uniqueId: Uuid, fileId: Uuid): Boolean {
        return try {
            outboxSync.tryEnqueue(
                request = DeleteLocalFilesByFileIdRequest(
                    driveId = driveId,
                    fileIds = listOf(fileId),
                ),
            )
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to enqueue vault file delete: $uniqueId" }
            false
        }
    }

    private suspend fun enqueueFileContentUpdate(
        uniqueId: Uuid,
        fileContent: VaultFileContent,
        groupId: Uuid?,
        versionTag: Uuid?,
        keyHeader: KeyHeader,
        fileType: Int = VAULT_FILE_TYPE,
        manifest: UpdateManifest = UpdateManifest.build(),
        payloads: List<PayloadFile>? = null,
        thumbnails: List<ThumbnailFile>? = null,
    ): Boolean {
        val content = OdinSystemSerializer.serialize(fileContent)
        val newKeyHeader = KeyHeader(
            iv = ByteArrayUtil.getRndByteArray(16), aesKey = keyHeader.aesKey
        )
        val unencryptedMetadata = UploadFileMetadata(
            allowDistribution = false,
            isEncrypted = true,
            appData = UploadAppFileMetaData(
                uniqueId = uniqueId,
                content = content,
                fileType = fileType,
                groupId = groupId,
            ),
            versionTag = versionTag,
        )

        val enqueued = outboxSync.tryEnqueue(
            request = UpdateFileByUniqueIdRequest(
                driveId = driveId,
                uniqueId = uniqueId,
                keyHeader = newKeyHeader,
                instructions = FileUpdateInstructionSet(
                    transferIv = ByteArrayUtil.getRndByteArray(16),
                    locale = UpdateLocale.Local,
                    recipients = emptyList(),
                    manifest = manifest,
                ),
                metadata = unencryptedMetadata.encryptContent(newKeyHeader),
                payloads = payloads,
                thumbnails = thumbnails,
            ),
        )

        if (enqueued) {
            try {
                optimisticWriter.writeUpdate(driveId, newKeyHeader, unencryptedMetadata)
            } catch (e: Exception) {
                Logger.e(e, TAG) { "Optimistic write failed (non-fatal) for $uniqueId" }
            }
        }

        return enqueued
    }

    suspend fun renameFile(
        uniqueId: Uuid,
        newName: String,
        existingLabel: String?,
        existingNotes: String?,
        groupId: Uuid?,
        versionTag: Uuid?,
        keyHeader: KeyHeader,
    ): Boolean {
        return try {
            enqueueFileContentUpdate(
                uniqueId = uniqueId,
                fileContent = VaultFileContent(name = newName, label = existingLabel, notes = existingNotes),
                groupId = groupId,
                versionTag = versionTag,
                keyHeader = keyHeader,
            )
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to enqueue rename: $uniqueId -> $newName" }
            false
        }
    }

    suspend fun updateLabel(
        uniqueId: Uuid,
        existingName: String,
        newLabel: String?,
        existingNotes: String?,
        groupId: Uuid?,
        versionTag: Uuid?,
        keyHeader: KeyHeader,
    ): Boolean {
        return try {
            enqueueFileContentUpdate(
                uniqueId = uniqueId,
                fileContent = VaultFileContent(name = existingName, label = newLabel, notes = existingNotes),
                groupId = groupId,
                versionTag = versionTag,
                keyHeader = keyHeader,
            )
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to enqueue label update: $uniqueId" }
            false
        }
    }

    // TODO: Add optimistic deletes with rollback for section + child files
    suspend fun deleteSection(sectionUniqueId: Uuid, sectionFileId: Uuid): Boolean {
        return try {
            val childrenEnqueued = outboxSync.tryEnqueue(
                request = DeleteFilesByGroupIdOutboxRequest(
                    driveId = driveId,
                    groupIds = listOf(sectionUniqueId),
                ),
            )
            val sectionEnqueued = outboxSync.tryEnqueue(
                request = DeleteLocalFilesByFileIdRequest(
                    driveId = driveId,
                    fileIds = listOf(sectionFileId),
                ),
            )
            childrenEnqueued && sectionEnqueued
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to delete vault section: $sectionUniqueId" }
            false
        }
    }

    // TODO: Add optimistic update with rollback (snapshot → writeUpdate → enqueue → rollback on failure)
    suspend fun updateSection(
        sectionUniqueId: Uuid,
        sectionContent: VaultSectionContent,
        versionTag: Uuid?,
        keyHeader: KeyHeader,
    ): Boolean {
        return try {
            val content = OdinSystemSerializer.serialize(sectionContent)
            val newKeyHeader = KeyHeader(
                iv = ByteArrayUtil.getRndByteArray(16),
                aesKey = keyHeader.aesKey
            )
            val metadata = UploadFileMetadata(
                allowDistribution = false,
                isEncrypted = true,
                appData = UploadAppFileMetaData(
                    uniqueId = sectionUniqueId,
                    content = content,
                    fileType = VAULT_SECTION_TYPE,
                ),
                versionTag = versionTag,
            ).encryptContent(newKeyHeader)

            outboxSync.tryEnqueue(
                request = UpdateFileByUniqueIdRequest(
                    driveId = driveId,
                    uniqueId = sectionUniqueId,
                    keyHeader = newKeyHeader,
                    instructions = FileUpdateInstructionSet(
                        transferIv = ByteArrayUtil.getRndByteArray(16),
                        locale = UpdateLocale.Local,
                        recipients = emptyList(),
                        manifest = UpdateManifest.build(),
                    ),
                    metadata = metadata,
                ),
            )
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to enqueue section update: $sectionUniqueId" }
            false
        }
    }

    suspend fun appendPages(
        file: VaultFileItem,
        newFiles: List<Pair<String, String>>,
        scope: CoroutineScope,
    ): Boolean {
        return try {
            val existingMaxIndex =
                file.payloadDescriptors.mapNotNull { it.key.removePrefix("vlt_pg_").toIntOrNull() }
                    .maxOrNull() ?: -1
            val startIndex = existingMaxIndex + 1

            val resolvedFiles = newFiles.map { (path, contentType) ->
                val resolved = fileOperationsProvider.resolveToFilePath(path)
                convertHeicIfNeeded(resolved, contentType)
            }

            val allPayloads = mutableListOf<PayloadFile>()
            val allThumbnails = mutableListOf<ThumbnailFile>()

            resolvedFiles.forEachIndexed { i, (filePath, contentType) ->
                val key = vaultPayloadKey(startIndex + i)
                var previewThumbnail: EmbeddedThumb? = null
                var thumbnails = emptyList<ThumbnailFile>()

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

            val keyHeader = KeyHeader(
                iv = ByteArrayUtil.getRndByteArray(16), aesKey = file.keyHeader.aesKey
            )
            val encryptedBundle = payloadEncryptionService.encryptBundle(
                Uuid.random(), PayloadBundle(allPayloads, allThumbnails, emptyList()),
                keyHeader.aesKey, scope,
            )

            enqueueFileContentUpdate(
                uniqueId = file.uniqueId,
                fileContent = VaultFileContent(name = file.fileName, label = file.label, notes = file.notes),
                groupId = file.groupId,
                versionTag = file.versionTag,
                keyHeader = file.keyHeader,
                manifest = UpdateManifest.build(
                    payloads = encryptedBundle.payloads,
                    thumbnails = encryptedBundle.thumbnails,
                    generatePayloadIv = false,
                ),
                payloads = encryptedBundle.payloads,
                thumbnails = encryptedBundle.thumbnails,
            )
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to enqueue append pages to ${file.uniqueId}" }
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
                return deleteFile(file.uniqueId, file.fileId)
            }

            enqueueFileContentUpdate(
                uniqueId = file.uniqueId,
                fileContent = VaultFileContent(name = file.fileName, label = file.label, notes = file.notes),
                groupId = file.groupId,
                versionTag = file.versionTag,
                keyHeader = file.keyHeader,
                manifest = UpdateManifest.build(
                    toDeletePayloads = listOf(PayloadDeleteKey(payloadKey)),
                ),
            )
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to enqueue delete page $payloadKey from ${file.uniqueId}" }
            false
        }
    }

    // TODO: Add optimistic update with rollback (snapshot → writeUpdate → enqueue → rollback on failure)
    suspend fun updateNotes(
        file: VaultFileItem,
        notes: String?,
    ): Boolean {
        return try {
            enqueueFileContentUpdate(
                uniqueId = file.uniqueId,
                fileContent = VaultFileContent(name = file.fileName, label = file.label, notes = notes),
                groupId = file.groupId,
                versionTag = file.versionTag,
                keyHeader = file.keyHeader,
            )
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to enqueue notes update for ${file.uniqueId}" }
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
        val allThumbnails = mutableListOf<ThumbnailFile>()
        val allPreviews = mutableListOf<EmbeddedThumb>()

        files.forEachIndexed { index, (filePath, contentType) ->
            val key = vaultPayloadKey(index)
            var previewThumbnail: EmbeddedThumb? = null
            var thumbnails = emptyList<ThumbnailFile>()

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

    private suspend fun convertHeicIfNeeded(
        filePath: String,
        contentType: String,
    ): Pair<String, String> {
        if (contentType != "image/heic" && contentType != "image/heif") {
            return filePath to contentType
        }
        val heicBytes = fileOperationsProvider.readFileBytes(filePath)
        val jpegBytes = convertHeicToJpeg(heicBytes) ?: return filePath to contentType
        val convertedPath = fileOperationsProvider.writeBytesToTempFile(
            jpegBytes, "heic_converted_", ".jpg"
        )
        return convertedPath to "image/jpeg"
    }
}
