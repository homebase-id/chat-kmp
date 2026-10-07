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
import id.homebase.api.image.convertHeicToJpeg
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.enqueued
import id.homebase.core.config.webDropLabeledDrive
import id.homebase.core.ui.screens.webdrop.model.PickedDropFile
import id.homebase.core.ui.screens.webdrop.model.WebDropTtlChoice
import id.homebase.core.webdrop.WebDropDropContent
import id.homebase.core.webdrop.WebDropIntro
import id.homebase.core.webdrop.WebDropIntroContent
import id.homebase.api.file.withResolvedFile
import id.homebase.core.webdrop.WebDropManifestEntry
import id.homebase.core.webdrop.WebDropProtocol
import id.homebase.core.webdrop.WebDropReceiptContent
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.flowOf

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
        intro: WebDropIntroContent? = null,
        theme: String? = null,
        viewOnly: Boolean,
    ): Result<CreatedDrop> = runCatching {
        val domain = credentialsManager.getActiveCredentials()?.domain
            ?: error("not authenticated")

        val built = buildWebDrop(
            fileOps, driveId, domain.toString(), files, ttlChoice, intro, theme, viewOnly,
            UnixTimeUtc.now().milliseconds,
        )

        val dropEnqueued = outboxSync.tryEnqueue(built.dropRequest)
        if (!dropEnqueued.enqueued) error("failed to enqueue drop upload")
        val receiptEnqueued = outboxSync.tryEnqueue(built.receiptRequest, dependencyUniqueId = built.dropId)
        if (!receiptEnqueued.enqueued) error("failed to enqueue receipt upload")

        CreatedDrop(dropId = built.dropId, url = built.url)
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

internal class BuiltWebDrop(
    val dropId: Uuid,
    val url: String,
    val dropRequest: UploadFileRequest,
    val receiptRequest: UploadFileRequest,
)

/** Everything createDrop does short of the outbox enqueue, so the wire shape is testable. */
@OptIn(ExperimentalEncodingApi::class)
internal suspend fun buildWebDrop(
    fileOps: FileOperationsProvider,
    driveId: Uuid,
    domain: String,
    files: List<PickedDropFile>,
    ttlChoice: WebDropTtlChoice,
    intro: WebDropIntroContent?,
    theme: String?,
    viewOnly: Boolean,
    nowMs: Long,
): BuiltWebDrop {
    require(files.isNotEmpty()) { "a drop needs at least one file" }
    require(files.size <= WebDropProtocol.MaxFilesPerDrop) {
        "a drop holds at most ${WebDropProtocol.MaxFilesPerDrop} files"
    }

    val dropId = Uuid.random()
    val key = ByteArrayUtil.getRndByteArray(WebDropProtocol.KeyBytes)
    val ttl = ttlChoice.toTtl(nowMs)
    val manifestKey = WebDropProtocol.manifestKey(viewOnly)
    val contentVersion = WebDropProtocol.contentVersion(viewOnly)
    val viewOnlyFlag = viewOnly.takeIf { it }

    val ivs = mutableMapOf<String, ByteArray>()

    val manifestIv = ByteArrayUtil.getRndByteArray(16)
    ivs[manifestKey] = manifestIv

    val (manifest, dataPayloads) = stageWebDropPayloads(fileOps, files, key, ivs, viewOnly)

    val manifestBytes = AesCbc.encrypt(
        OdinSystemSerializer.serialize(manifest).encodeToByteArray(),
        key,
        manifestIv,
    )
    val manifestPath = fileOps.writeBytesToOutboxTempFile(manifestBytes, "wdrmeta", ".bin")
    val payloads = mutableListOf(
        PayloadFile(
            key = manifestKey,
            filePath = manifestPath,
            contentType = "application/octet-stream",
            isPreEncrypted = true,
        )
    )
    payloads += dataPayloads

    val encryptedIntro = intro?.takeUnless { it.isEmpty() }?.let {
        // Its own IV, never a payload's: reusing an IV under the same key breaks CBC.
        val introIv = ByteArrayUtil.getRndByteArray(16)
        WebDropIntro(
            iv = Base64.encode(introIv),
            data = Base64.encode(
                AesCbc.encrypt(OdinSystemSerializer.serialize(it).encodeToByteArray(), key, introIv)
            ),
        )
    }

    val dropContent = WebDropDropContent(
        v = contentVersion,
        ivs = ivs.mapValues { (_, iv) -> Base64.encode(iv) },
        theme = theme,
        intro = encryptedIntro,
        viewOnly = viewOnlyFlag,
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

    val url = WebDropProtocol.buildLink(domain, driveId, dropId, key)

    val receipt = WebDropReceiptContent(
        v = contentVersion,
        name = files.first().name,
        files = manifest,
        url = url,
        ttl = ttl,
        createdAt = nowMs,
        recipientName = intro?.recipientName?.takeUnless { it.isBlank() },
        conditions = intro?.conditions ?: emptyList(),
        theme = theme,
        viewOnly = viewOnlyFlag,
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

    return BuiltWebDrop(dropId, url, dropRequest, receiptRequest)
}

/**
 * A drop source file could not be read. Snapshot-on-pick (materializeForUpload) makes this rare -
 * the picker copies into the sandbox at selection time - but a cache sweep between pick and
 * create, or a share-in whose temp was reaped, can still land here. Typed so the UI can name the
 * file and say "pick it again" instead of a generic failure (#1420).
 */
class WebDropSourceReadException(
    val fileName: String,
    val filePath: String,
    cause: Throwable,
) : Exception("couldn't read drop source '$fileName'", cause)

/**
 * Sizes and encrypts every picked file into outbox staging, one at a time. Extracted from
 * [WebDropService.createDrop] so the read-failure contract is unit-testable without the outbox:
 * any per-file failure aborts the WHOLE drop as [WebDropSourceReadException] - a partial drop
 * must never reach the recipient - and each resolved content-URI copy is reaped either way
 * ([withResolvedFile]). [ivs] gains one fresh IV per data payload, keyed by payload key.
 * A view-only drop transcodes HEIC/HEIF to JPEG because most browsers cannot decode HEIC and the
 * recipient has no download to fall back on; if the transcode fails the original is sent as-is.
 */
internal suspend fun stageWebDropPayloads(
    fileOps: FileOperationsProvider,
    files: List<PickedDropFile>,
    key: ByteArray,
    ivs: MutableMap<String, ByteArray>,
    viewOnly: Boolean = false,
    heicToJpeg: (ByteArray) -> ByteArray? = ::convertHeicToJpeg,
): Pair<List<WebDropManifestEntry>, List<PayloadFile>> {
    val manifest = mutableListOf<WebDropManifestEntry>()
    val payloads = mutableListOf<PayloadFile>()
    files.forEachIndexed { index, file ->
        val payloadKey = WebDropProtocol.dataPayloadKey(index)
        val iv = ByteArrayUtil.getRndByteArray(16)
        try {
            fileOps.withResolvedFile(file.path) { resolvedPath ->
                val jpeg = if (viewOnly && file.isHeif()) {
                    heicToJpeg(fileOps.readFileBytes(resolvedPath))
                } else null
                manifest += WebDropManifestEntry(
                    key = payloadKey,
                    name = if (jpeg != null) file.name.substringBeforeLast('.', file.name) + ".jpg" else file.name,
                    contentType = if (jpeg != null) "image/jpeg" else file.contentType,
                    size = jpeg?.size?.toLong() ?: fileOps.getFileSize(resolvedPath),
                )
                // Staged (not cache-temp) because the outbox reads payload bytes lazily at drain
                // time; a reaped temp file would kill the row as PERMANENT.
                val stagedPath = fileOps.createOutboxStagingPath("wdrdata", ".bin")
                fileOps.writeStream(
                    stagedPath,
                    AesCbc.streamEncryptWithCbc(jpeg?.let { flowOf(it) } ?: fileOps.readFileAsFlow(resolvedPath), key, iv),
                )
                payloads += PayloadFile(
                    key = payloadKey,
                    filePath = stagedPath,
                    contentType = "application/octet-stream",
                    isPreEncrypted = true,
                )
            }
        } catch (e: Exception) {
            // The source read is what fails in practice (stale URI, swept temp); a staging-write
            // failure lands here too, and "couldn't read <name>" with a re-pick is still the
            // honest, actionable message for both.
            throw WebDropSourceReadException(file.name, file.path, e)
        }
        ivs[payloadKey] = iv
    }
    return manifest to payloads
}

private fun PickedDropFile.isHeif(): Boolean {
    val type = contentType.lowercase()
    return type == "image/heic" || type == "image/heif" ||
        name.endsWith(".heic", ignoreCase = true) || name.endsWith(".heif", ignoreCase = true)
}
