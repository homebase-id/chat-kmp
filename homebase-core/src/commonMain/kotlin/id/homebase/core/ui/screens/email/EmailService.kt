@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.email

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.enqueued
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.config.emailLabeledDrive
import id.homebase.core.ui.screens.email.model.EmailCredentialContent
import id.homebase.core.ui.screens.email.model.EmailFileTypes
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "EmailService"

/**
 * The app's only writes to the email drive: its record of the mail-client credentials it asked
 * for. Key material is written by the SERVER — one writer per file type, so there is no
 * read-modify-write race between them.
 *
 * These files are why an app password can be shown again later at all: the mail server generates
 * the secret and never repeats it.
 */
class EmailService(
    private val outboxSync: OutboxSync,
    private val optimisticWriter: OptimisticWriter,
) {
    private val driveId = emailLabeledDrive.drive.alias

    /**
     * Records an issued credential. Call this BEFORE showing the secret to anyone: if the write
     * fails, the caller should revoke the credential rather than leave one live on the mail
     * server that nobody has a record of.
     */
    suspend fun saveCredential(
        credential: EmailCredentialContent,
        keyHeader: KeyHeader = KeyHeader.newRandom16(),
    ): Boolean {
        return try {
            val unencryptedMetadata = UploadFileMetadata(
                allowDistribution = false,
                isEncrypted = true,
                appData = UploadAppFileMetaData(
                    uniqueId = Uuid.random(),
                    content = OdinSystemSerializer.serialize(credential),
                    fileType = EmailFileTypes.APP_PASSWORD_CREDENTIAL,
                ),
            )

            val enqueued = outboxSync.tryEnqueue(
                UploadFileRequest(
                    driveId = driveId,
                    keyHeader = keyHeader,
                    metadata = unencryptedMetadata.encryptContent(keyHeader),
                )
            ).enqueued

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
                    // Non-fatal: the outbox has it, so it lands on the next sync either way.
                    Logger.e(e, TAG) { "Optimistic write failed for credential '${credential.label}'" }
                }
            }

            enqueued
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to save credential '${credential.label}'" }
            false
        }
    }

    /**
     * Forgets our record of a credential. This is bookkeeping ONLY — the credential keeps working
     * until the server revokes it, so callers must revoke first and delete second.
     */
    suspend fun forgetCredential(fileId: Uuid): Boolean {
        return try {
            outboxSync.tryEnqueue(
                DeleteLocalFilesByFileIdRequest(driveId = driveId, fileIds = listOf(fileId))
            ).enqueued
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to delete credential record $fileId" }
            false
        }
    }
}
