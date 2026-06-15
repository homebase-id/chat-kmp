package id.homebase.core.lists.services

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.OdinId
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.enqueued
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.config.listsLabeledDrive
import co.touchlab.kermit.Logger
import kotlin.uuid.Uuid

/**
 * Shared write primitives for header-only Lists files (no payloads). Encapsulates the
 * keyHeader/encrypt/optimistic/outbox boilerplate, mirroring ConversationService.writeConversationFile
 * (create), ChatMessageSenderService.updateMessage (update), and ChatMessageActionService.deleteMessage
 * (delete). Used by both ListService and ListItemSenderService.
 */
class ListFileWriter(
    private val outboxSync: OutboxSync,
    private val optimisticWriter: OptimisticWriter,
    private val credentialsManager: CredentialsManager,
) {
    private val listsDrive = listsLabeledDrive.drive.alias

    /** The active identity's own OdinId — never a transit recipient. */
    suspend fun selfDomain(): OdinId = credentialsManager.requireActiveCredentials().domain

    /** Transit recipients = members minus self, de-duplicated. */
    suspend fun recipientsExcludingSelf(members: List<OdinId>): List<OdinId> {
        val self = selfDomain()
        return members.filter { it != self }.distinct()
    }

    /** Create a header-only file (descriptor JSON in appData.content). */
    suspend fun createFile(
        uniqueId: Uuid,
        groupId: Uuid?,
        fileType: Int,
        contentJson: String,
        recipients: List<OdinId>,
    ): Boolean {
        val keyHeader = KeyHeader.newRandom16()
        val metadata = UploadFileMetadata(
            allowDistribution = true,
            isEncrypted = true,
            appData = UploadAppFileMetaData(
                uniqueId = uniqueId,
                groupId = groupId,
                fileType = fileType,
                content = contentJson,
            ),
        )
        val request = UploadFileRequest(
            driveId = listsDrive,
            keyHeader = keyHeader,
            metadata = metadata.encryptContent(keyHeader),
            transitOptions = TransitOptions(recipients = recipients, useAppNotification = false),
        )
        optimisticWriter.writeNewFile(
            driveId = listsDrive,
            keyHeader = keyHeader,
            unecryptedMetadata = metadata,
            originalRecipientCount = recipients.size,
            fileSystemType = FileSystemType.Standard,
        )
        val enqueued = outboxSync.tryEnqueue(request).enqueued
        if (!enqueued) {
            // The upload was never queued (e.g. DB error): drop the optimistic row so it
            // doesn't linger forever as a local-only ghost that never reaches the server.
            Logger.w(tag = "ListFileWriter") {
                "createFile enqueue failed for $uniqueId — rolling back optimistic write"
            }
            optimisticWriter.removeOptimisticFile(listsDrive, uniqueId)
        }
        return enqueued
    }

    /** Update a header-only file's content by uniqueId (last-writer-wins via versionTag). */
    suspend fun updateFile(
        uniqueId: Uuid,
        groupId: Uuid?,
        fileType: Int,
        contentJson: String,
        versionTag: Uuid?,
        recipients: List<OdinId>,
    ): Boolean {
        val keyHeader = KeyHeader.newRandom16()
        val metadata = UploadFileMetadata(
            allowDistribution = true,
            isEncrypted = true,
            versionTag = versionTag,
            appData = UploadAppFileMetaData(
                uniqueId = uniqueId,
                groupId = groupId,
                fileType = fileType,
                content = contentJson,
            ),
        )
        val request = UpdateFileByUniqueIdRequest(
            driveId = listsDrive,
            uniqueId = uniqueId,
            keyHeader = keyHeader,
            instructions = FileUpdateInstructionSet(
                transferIv = ByteArrayUtil.getRndByteArray(16),
                locale = UpdateLocale.Local,
                recipients = recipients,
                manifest = UpdateManifest.build(
                    payloads = emptyList(),
                    toDeletePayloads = null,
                    thumbnails = null,
                    generatePayloadIv = false,
                ),
                useAppNotification = false,
                appNotificationOptions = null,
            ),
            metadata = metadata.encryptContent(keyHeader),
            payloads = emptyList(),
            thumbnails = emptyList(),
        )
        val enqueued = outboxSync.replaceEnqueue(request, priority = 1, dependencyUniqueId = null).enqueued
        if (enqueued) {
            optimisticWriter.writeUpdate(
                driveId = listsDrive,
                keyHeader = keyHeader,
                unecryptedMetadata = metadata,
            )
        }
        return enqueued
    }

    /** Soft-delete a file by fileId (optimistic, with rollback on enqueue failure). */
    suspend fun deleteFile(fileId: Uuid, uniqueId: Uuid, recipients: List<OdinId>): Boolean {
        val original = optimisticWriter.writeDelete(listsDrive, uniqueId)
        return runCatching {
            val result = outboxSync.tryEnqueue(
                DeleteLocalFilesByFileIdRequest(
                    driveId = listsDrive,
                    fileIds = listOf(fileId),
                    recipients = recipients,
                    hardDelete = false,
                )
            )
            if (!result.enqueued && original != null) optimisticWriter.rollbackWrite(listsDrive, original)
            result.enqueued
        }.getOrElse {
            if (original != null) runCatching { optimisticWriter.rollbackWrite(listsDrive, original) }
            false
        }
    }
}
