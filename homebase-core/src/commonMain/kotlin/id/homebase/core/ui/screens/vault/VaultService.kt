@file:OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)

package id.homebase.core.ui.screens.vault
import kotlinx.coroutines.CoroutineScope
import id.homebase.upload.PayloadBundle
import id.homebase.upload.UploadOutcome
import id.homebase.upload.UploadService
import id.homebase.upload.MediaUpdateSpec

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.files.DeleteFilesByGroupIdOutboxRequest
import id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.PayloadDeleteKey
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.enqueued
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.config.vaultLabeledDrive
import id.homebase.core.ui.screens.vault.model.VaultEntry
import id.homebase.core.ui.screens.vault.model.VaultFileContent
import id.homebase.core.ui.screens.vault.model.VaultSectionContent
import id.homebase.core.ui.screens.vault.model.VAULT_FILE_TYPE
import id.homebase.core.ui.screens.vault.model.VAULT_SECTION_TYPE
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "VaultService"

class VaultService(
    private val outboxSync: OutboxSync,
    private val optimisticWriter: OptimisticWriter,
    private val uploadService: UploadService,
    private val scope: CoroutineScope,
) {
    private val driveId = vaultLabeledDrive.drive.alias

    suspend fun createSection(
        sectionId: Uuid,
        sectionContent: VaultSectionContent,
        keyHeader: KeyHeader = KeyHeader.newRandom16(),
    ): Boolean {
        return try {
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
            val enqueued = outboxSync.tryEnqueue(request).enqueued
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

    suspend fun deleteEntry(uniqueId: Uuid, fileId: Uuid): Boolean {
        return try {
            outboxSync.tryEnqueue(
                request = DeleteLocalFilesByFileIdRequest(
                    driveId = driveId,
                    fileIds = listOf(fileId),
                ),
            ).enqueued
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to enqueue vault file delete: $uniqueId" }
            false
        }
    }

    /**
     * Enqueue a content/metadata update for an existing vault file through the shared
     * [UploadService.updateFile]. Pass a plaintext [bundle] for new/replaced payloads (the pipeline
     * encrypts + fail-softs), [toDeletePayloads] for a delete, or neither for a header-only edit
     * (rename / label / notes). The pipeline reuses the file's AES key with a fresh IV.
     */
    internal suspend fun enqueueFileContentUpdate(
        uniqueId: Uuid,
        fileContent: VaultFileContent,
        groupId: Uuid?,
        versionTag: Uuid?,
        keyHeader: KeyHeader,
        fileType: Int = VAULT_FILE_TYPE,
        bundle: PayloadBundle? = null,
        toDeletePayloads: List<PayloadDeleteKey>? = null,
        generatePayloadIv: Boolean = false,
    ): Boolean {
        val content = OdinSystemSerializer.serialize(fileContent)
        // Reuse the file's AES key with a fresh IV — the server rejects an update whose AES key differs.
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

        val outcome = uploadService.updateFile(
            MediaUpdateSpec(
                driveId = driveId,
                uniqueId = uniqueId,
                keyHeader = newKeyHeader,
                metadata = unencryptedMetadata,
                bundle = bundle,
                toDeletePayloads = toDeletePayloads,
                generatePayloadIv = generatePayloadIv,
                replace = false, // tryEnqueue — matches prior behavior
            ),
            scope = scope,
        )
        return outcome is UploadOutcome.Enqueued
    }

    suspend fun renameEntry(
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

    suspend fun deleteSection(sectionUniqueId: Uuid, sectionFileId: Uuid): Boolean {
        return try {
            val childrenEnqueued = outboxSync.tryEnqueue(
                request = DeleteFilesByGroupIdOutboxRequest(
                    driveId = driveId,
                    groupIds = listOf(sectionUniqueId),
                ),
            ).enqueued
            val sectionEnqueued = outboxSync.tryEnqueue(
                request = DeleteLocalFilesByFileIdRequest(
                    driveId = driveId,
                    fileIds = listOf(sectionFileId),
                ),
            ).enqueued
            childrenEnqueued && sectionEnqueued
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to delete vault section: $sectionUniqueId" }
            false
        }
    }

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
            val unencryptedMetadata = UploadFileMetadata(
                allowDistribution = false,
                isEncrypted = true,
                appData = UploadAppFileMetaData(
                    uniqueId = sectionUniqueId,
                    content = content,
                    fileType = VAULT_SECTION_TYPE,
                ),
                versionTag = versionTag,
            )

            val enqueued = outboxSync.tryEnqueue(
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
                    metadata = unencryptedMetadata.encryptContent(newKeyHeader),
                ),
            ).enqueued

            if (enqueued) {
                try {
                    optimisticWriter.writeUpdate(driveId, newKeyHeader, unencryptedMetadata)
                } catch (e: Exception) {
                    Logger.e(e, TAG) { "Optimistic write failed (non-fatal) for section: $sectionUniqueId" }
                }
            }

            enqueued
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to enqueue section update: $sectionUniqueId" }
            false
        }
    }

    suspend fun moveEntryToSection(
        entry: VaultEntry,
        targetSectionId: Uuid,
    ): Boolean {
        return try {
            enqueueFileContentUpdate(
                uniqueId = entry.uniqueId,
                fileContent = VaultFileContent(
                    name = entry.fileName,
                    label = entry.label,
                    notes = entry.notes,
                    pdfPageCount = entry.pdfPageCount,
                ),
                groupId = targetSectionId,
                versionTag = entry.versionTag,
                keyHeader = entry.keyHeader,
            )
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to enqueue move for ${entry.uniqueId} -> $targetSectionId" }
            false
        }
    }

    suspend fun updateNotes(
        entry: VaultEntry,
        notes: String?,
    ): Boolean {
        return try {
            enqueueFileContentUpdate(
                uniqueId = entry.uniqueId,
                fileContent = VaultFileContent(name = entry.fileName, label = entry.label, notes = notes),
                groupId = entry.groupId,
                versionTag = entry.versionTag,
                keyHeader = entry.keyHeader,
            )
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to enqueue notes update for ${entry.uniqueId}" }
            false
        }
    }
}
