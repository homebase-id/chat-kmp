@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.webdrop

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.crypto.AesCbc
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.enqueued
import id.homebase.core.config.webDropLabeledDrive
import id.homebase.core.ui.screens.webdrop.model.PickedDropFile
import id.homebase.core.ui.screens.webdrop.model.WebDropTtlChoice
import id.homebase.core.webdrop.WebDropDropContent
import id.homebase.core.webdrop.WebDropManifestEntry
import id.homebase.core.webdrop.WebDropProtocol
import id.homebase.core.webdrop.WebDropReceiptContent
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "WebDropService"

data class CreatedDrop(val dropId: Uuid, val url: String)

/**
 * The WebDrop writer. Builds the two files of a drop and enqueues them through the outbox:
 * the anonymous drop file (server-unencrypted, payloads pre-encrypted here under the link key)
 * and the owner-encrypted receipt. The receipt is enqueued with a dependency on the drop so a
 * link is never syncable before the bytes it points at.
 */
@OptIn(ExperimentalEncodingApi::class)
class WebDropService(
    private val outboxSync: OutboxSync,
    private val fileOps: FileOperationsProvider,
    private val credentialsManager: CredentialsManager,
) {
    private val driveId = webDropLabeledDrive.drive.alias

    suspend fun createDrop(
        files: List<PickedDropFile>,
        ttlChoice: WebDropTtlChoice,
    ): Result<CreatedDrop> = runCatching {
        require(files.isNotEmpty()) { "a drop needs at least one file" }
        require(files.size <= WebDropProtocol.MaxFilesPerDrop) {
            "a drop holds at most ${WebDropProtocol.MaxFilesPerDrop} files"
        }
        val domain = credentialsManager.getActiveCredentials()?.domain
            ?: error("not authenticated")

        val dropId = Uuid.random()
        val key = ByteArrayUtil.getRndByteArray(WebDropProtocol.KeyBytes)
        val nowMs = UnixTimeUtc.now().milliseconds
        val ttl = ttlChoice.toTtl(nowMs)

        val ivs = mutableMapOf<String, ByteArray>()
        val payloads = mutableListOf<PayloadFile>()

        val resolvedPaths = files.map { fileOps.resolveToFilePath(it.path) }
        val manifest = files.mapIndexed { index, file ->
            WebDropManifestEntry(
                key = WebDropProtocol.dataPayloadKey(index),
                name = file.name,
                contentType = file.contentType,
                size = fileOps.getFileSize(resolvedPaths[index]),
            )
        }

        val manifestIv = ByteArrayUtil.getRndByteArray(16)
        ivs[WebDropProtocol.ManifestPayloadKey] = manifestIv
        val manifestBytes = AesCbc.encrypt(
            OdinSystemSerializer.serialize(manifest).encodeToByteArray(),
            key,
            manifestIv,
        )
        val manifestPath = fileOps.writeBytesToOutboxTempFile(manifestBytes, "wdrmeta", ".bin")
        payloads += PayloadFile(
            key = WebDropProtocol.ManifestPayloadKey,
            filePath = manifestPath,
            contentType = "application/octet-stream",
            isPreEncrypted = true,
        )

        files.forEachIndexed { index, file ->
            val payloadKey = WebDropProtocol.dataPayloadKey(index)
            val iv = ByteArrayUtil.getRndByteArray(16)
            ivs[payloadKey] = iv
            // Staged (not cache-temp) because the outbox reads payload bytes lazily at drain
            // time; a reaped temp file would kill the row as PERMANENT.
            val stagedPath = fileOps.createOutboxStagingPath("wdrdata", ".bin")
            fileOps.writeStream(
                stagedPath,
                AesCbc.streamEncryptWithCbc(fileOps.readFileAsFlow(resolvedPaths[index]), key, iv),
            )
            payloads += PayloadFile(
                key = payloadKey,
                filePath = stagedPath,
                contentType = "application/octet-stream",
                isPreEncrypted = true,
            )
        }

        val dropContent = WebDropDropContent(
            ivs = ivs.mapValues { (_, iv) -> Base64.encode(iv) },
        )

        val dropMetadata = UploadFileMetadata(
            allowDistribution = false,
            isEncrypted = false,
            accessControlList = AccessControlList(
                requiredSecurityGroup = SecurityGroupType.Anonymous.value,
            ),
            appData = UploadAppFileMetaData(
                uniqueId = dropId,
                groupId = dropId,
                fileType = WebDropProtocol.DropFileType,
                userDate = nowMs,
                content = OdinSystemSerializer.serialize(dropContent),
            ),
            ttl = ttl,
        )

        val dropRequest = UploadFileRequest(
            driveId = driveId,
            keyHeader = KeyHeader.empty(),
            metadata = dropMetadata,
            payloads = payloads,
        )

        val url = WebDropProtocol.buildLink(domain.toString(), driveId, dropId, key)

        val receipt = WebDropReceiptContent(
            name = files.first().name,
            files = manifest,
            url = url,
            ttl = ttl,
            createdAt = nowMs,
        )
        val receiptKeyHeader = KeyHeader.newRandom16()
        val receiptMetadata = UploadFileMetadata(
            allowDistribution = false,
            isEncrypted = true,
            appData = UploadAppFileMetaData(
                uniqueId = Uuid.random(),
                groupId = dropId,
                fileType = WebDropProtocol.ReceiptFileType,
                userDate = nowMs,
                content = OdinSystemSerializer.serialize(receipt),
            ),
        ).encryptContent(receiptKeyHeader)

        val receiptRequest = UploadFileRequest(
            driveId = driveId,
            keyHeader = receiptKeyHeader,
            metadata = receiptMetadata,
        )

        val dropEnqueued = outboxSync.tryEnqueue(dropRequest)
        if (!dropEnqueued.enqueued) error("failed to enqueue drop upload")
        val receiptEnqueued = outboxSync.tryEnqueue(receiptRequest, dependencyUniqueId = dropId)
        if (!receiptEnqueued.enqueued) error("failed to enqueue receipt upload")

        CreatedDrop(dropId = dropId, url = url)
    }.onFailure { e -> Logger.e(e, TAG) { "createDrop failed" } }

    /**
     * Kills the link by deleting the drop file only. The receipt stays so the list can show the
     * drop as Removed; [clear] deletes the receipt when the user tidies the row away.
     */
    suspend fun revoke(dropFileId: Uuid): Boolean = enqueueDelete(dropFileId)

    suspend fun clear(receiptFileId: Uuid): Boolean = enqueueDelete(receiptFileId)

    private suspend fun enqueueDelete(fileId: Uuid): Boolean {
        return try {
            outboxSync.tryEnqueue(
                request = DeleteLocalFilesByFileIdRequest(
                    driveId = driveId,
                    fileIds = listOf(fileId),
                ),
            ).enqueued
        } catch (e: Exception) {
            Logger.e(e, TAG) { "failed to enqueue webdrop delete: $fileId" }
            false
        }
    }
}
