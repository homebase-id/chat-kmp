package id.homebase.api.client.drives.upload

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import id.homebase.api.client.drives.lockKeyHeader
import id.homebase.api.serialization.Base64Bytes
import id.homebase.api.serialization.LenientBase64BytesSerializer
import co.touchlab.kermit.Logger
import id.homebase.api.client.ApiResponse
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.OdinErrorResponse
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.shares.ShareRequest
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.AesCbc
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.api.client.UploadProgress
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

@Serializable
data class LocalMetadataUploadResult(val newLocalVersionTag: String)

/** Local app data for metadata updates. */
data class LocalAppData(
    val versionTag: String? = null,
    val tags: List<String>? = null,
    val content: String? = null,
    @Serializable(with = LenientBase64BytesSerializer::class) val iv: Base64Bytes? = null
)

@Serializable
private data class UpdateLocalMetadataTagsRequest(
    val localVersionTag: String?,
    val tags: List<String>?
)

@Serializable
private data class UpdateLocalMetadataContentRequest(
    @Serializable(with = LenientBase64BytesSerializer::class) val iv: Base64Bytes? = null,
    val localVersionTag: String?,
    val content: String?
)

class PreparedUploadBody(val url: String, val bearerToken: String, val contentType: String)

private const val UPLOAD_BODY_CHUNK_BYTES = 64 * 1024

@Serializable
data class UploadFileRequest(
    val driveId: Uuid,
    /// The KeyHeader used to encrypt content and payloads
    val keyHeader: KeyHeader,
    val metadata: UploadFileMetadata,
    val payloads: List<PayloadFile> = emptyList(),
    val thumbnails: List<ThumbnailFile> = emptyList(),
    val transitOptions: TransitOptions? = null,
    val fileSystemType: FileSystemType? = null,
    val withCommentedFileKey: Boolean = false,
    /** A recipient drive's write-only public point, `0x04 ‖ x ‖ y`, to seal the key header to. */
    val sealedTo: ByteArray? = null,
    /** The file's uniqueId is its content, so a 4105 means it's already there: sent, nothing to convert to an update. */
    val existingFileIsSuccess: Boolean = false,
    val shares: List<ShareRequest>? = null,
    val globalTransitId: Uuid? = null,
)

@Serializable
data class UpdateFileByFileIdRequest(
    val driveId: Uuid,
    val fileId: Uuid,
    val keyHeader: KeyHeader?,
    val instructions: FileUpdateInstructionSet,
    val metadata: UploadFileMetadata,
    val payloads: List<PayloadFile>? = null,
    val thumbnails: List<ThumbnailFile>? = null,
)

@Serializable
data class UpdateFileByUniqueIdRequest(
    val driveId: Uuid,
    val uniqueId: Uuid,
    val keyHeader: KeyHeader?,
    val instructions: FileUpdateInstructionSet,
    val metadata: UploadFileMetadata,
    val payloads: List<PayloadFile>? = null,
    val thumbnails: List<ThumbnailFile>? = null,
)


@OptIn(ExperimentalEncodingApi::class)
class DriveUploadProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager,
    private val fileOperationsProvider: FileOperationsProvider,
) : OdinApiProviderBase(httpClient, credentialsManager) {

    companion object {
        const val TAG = "DriveUploadProvider"
    }

    // ==================== HIGH-LEVEL UPLOAD METHODS ====================

    /**
     * DRAIN-TIME payload integrity pre-flight (HealAudit + #842). Payload bytes are
     * staged to a file at ENQUEUE time but read lazily at outbox-drain time, which
     * can be days later — if the staged file is gone by then, openFileInput() would
     * either stream 0 bytes (silently shipping an EMPTY payload that clobbers a good
     * one on AppendOrOverwrite) or throw mid-request as a platform-specific IO error
     * that gets misclassified as a transient "Network failure" and retried 20×.
     *
     * So: probe every payload path BEFORE any network call. A plain filesystem path
     * that is missing/empty throws [StagedPayloadMissingException] — deterministic,
     * classified PERMANENT, row drops on attempt 1 with an honest OutboxItemDropped.
     *
     * URI-shaped sources (Android `content://`, iOS `ph://` / raw PHAsset ids) are
     * NOT escalated: they aren't staged artifacts we own, and getFileSize can't
     * reliably size them (the SAF SIZE column is optional; Photos assets report 0) —
     * a false PERMANENT drop is worse than a retry tail. They keep the WARN-only
     * audit log.
     */
    private fun preflightStagedPayloads(payloads: List<PayloadFile>?, context: String) {
        payloads?.forEach { p ->
            val path = p.filePath
            val size = runCatching { fileOperationsProvider.getFileSize(path) }.getOrDefault(-1L)
            if (size > 0L) {
                Logger.d(tag = "HealAudit") {
                    "$TAG.$context: payload key=${p.key} size=$size at drain (preEncrypted=${p.isPreEncrypted})"
                }
                return@forEach
            }
            val isUriSource =
                path.startsWith("content:") || path.startsWith("ph://") || path.contains("/L0/")
            Logger.w(tag = "HealAudit") {
                "$TAG.$context: payload key=${p.key} size=$size at drain " +
                    "(path=$path preEncrypted=${p.isPreEncrypted}) — " +
                    if (isUriSource) "URI source, keeping WARN-only (not escalating)"
                    else "staged file missing, failing PERMANENT"
            }
            if (!isUriSource) throw StagedPayloadMissingException(path, p.key)
        }
    }

    /** For an upload something else will send, like a background URLSession: the same request as [uploadFile], in a file. */
    suspend fun writeUploadBody(request: UploadFileRequest, outputPath: String): PreparedUploadBody {
        preflightStagedPayloads(request.payloads, "writeUploadBody uniqueId=${request.metadata.appData.uniqueId}")
        val credentials = requireCreds()
        val form = uploadForm(request, credentials.secret.unsafeBytes).first
        val channel = ByteChannel(autoFlush = true)
        coroutineScope {
            launch {
                try {
                    form.writeTo(channel)
                } finally {
                    channel.flushAndClose()
                }
            }
            fileOperationsProvider.writeStream(
                outputPath,
                flow {
                    val buffer = ByteArray(UPLOAD_BODY_CHUNK_BYTES)
                    while (true) {
                        val read = channel.readAvailable(buffer)
                        if (read < 0) break
                        if (read > 0) emit(buffer.copyOf(read))
                    }
                },
            )
        }
        return PreparedUploadBody(
            url = uploadUrl(credentials.domain, request.driveId, request.fileSystemType),
            bearerToken = credentials.accessToken,
            contentType = form.contentType.toString(),
        )
    }

    private suspend fun uploadForm(request: UploadFileRequest, sharedSecret: ByteArray): Pair<MultiPartFormDataContent, ByteArray> {
        val transferIv = ByteArrayUtil.getRndByteArray(16)
        val sharedSecretEncryptedDescriptor = buildSharedSecretEncryptedUploadDescriptor(
            lockKeyHeader(request.keyHeader, request.driveId, sharedSecret, transferIv, request.withCommentedFileKey, request.sealedTo),
            request.metadata,
            sharedSecret,
            transferIv = transferIv,
            shares = request.shares,
            globalTransitId = request.globalTransitId,
        )

        val instructions =
            UploadInstructionSet(
                transferIv = transferIv,
                manifest = UploadManifest.build(
                    request.payloads,
                    request.thumbnails,
                    generatePayloadIv = request.metadata.isEncrypted
                ),
                transitOptions = request.transitOptions
            )

        val data =
            buildUploadFormData(
                instructionSet = instructions,
                sharedSecretEncryptedDescriptor = sharedSecretEncryptedDescriptor,
                payloads = request.payloads,
                thumbnails = request.thumbnails,
                fileOperationsProvider = fileOperationsProvider
            )
        return data to sharedSecretEncryptedDescriptor
    }

    suspend fun uploadFile(
        request: UploadFileRequest,
        onProgress: UploadProgress? = null,
        onVersionConflict: (suspend () -> CreateFileResult?)? = null
    ): CreateFileResult? {

        preflightStagedPayloads(request.payloads, "uploadFile uniqueId=${request.metadata.appData.uniqueId}")

        val creds = requireCreds()

        val (data, sharedSecretEncryptedDescriptor) = uploadForm(request, creds.secret.unsafeBytes)

        val totalUploadSize = calculateUploadSize(
            request.payloads,
            request.thumbnails,
            sharedSecretEncryptedDescriptor,
            fileOperationsProvider
        )

        val wrappedProgress = onProgress?.let { progress ->
            suspend { sent: Long, _: Long? ->
                progress(sent, totalUploadSize)
            }
        }

        val result =
            pureUpload(
                request.driveId, data, request.fileSystemType,
                wrappedProgress,
                onVersionConflict
            )

        if (result != null) {
            cleanupPayloadTempFiles(request.payloads)
        }

        return result
    }

    suspend fun updateFileByFileId(
        request: UpdateFileByFileIdRequest,
        onProgress: UploadProgress? = null,
        onVersionConflict: (suspend () -> UpdateFileResult?)? = null
    ): UpdateFileResult? {

        preflightStagedPayloads(request.payloads, "updateFileByFileId fileId=${request.fileId}")

        val creds = requireCreds()
        val sharedSecret = creds.secret.unsafeBytes

        // Build encrypted descriptor
        val sharedSecretEncryptedDescriptor =
            buildSharedSecretEncryptedUpdateDescriptor(
                request.keyHeader,
                request.metadata,
                sharedSecret,
                request.instructions.transferIv,
                request.driveId
            )

        val data =
            buildUpdateFormData(
                instructionSet = request.instructions,
                sharedSecretEncryptedDescriptor = sharedSecretEncryptedDescriptor,
                payloads = request.payloads,
                thumbnails = request.thumbnails,
                fileOperationsProvider = fileOperationsProvider
            )

        val totalUploadSize = calculateUploadSize(
            request.payloads,
            request.thumbnails,
            sharedSecretEncryptedDescriptor,
            fileOperationsProvider
        )

        val wrappedProgress = onProgress?.let { progress ->
            suspend { sent: Long, _: Long? ->
                progress(sent, totalUploadSize)
            }
        }

        val path = "/drives/${request.driveId}/files/${request.fileId}"
        val result = pureUpdate(data, path, wrappedProgress, onVersionConflict)

        if (result != null) {
            cleanupPayloadTempFiles(request.payloads)
        }

        return result
    }

    suspend fun updateFileByUniqueId(
        request: UpdateFileByUniqueIdRequest,
        onProgress: UploadProgress? = null,
        onVersionConflict: (suspend () -> UpdateFileResult?)? = null
    ): UpdateFileResult? {

        val creds = requireCreds()
        val sharedSecret = creds.secret.unsafeBytes

        preflightStagedPayloads(request.payloads, "updateFileByUniqueId uniqueId=${request.uniqueId}")

        // Build encrypted descriptor
        val sharedSecretEncryptedDescriptor =
            buildSharedSecretEncryptedUpdateDescriptor(
                request.keyHeader,
                request.metadata,
                sharedSecret,
                request.instructions.transferIv,
                request.driveId
            )

        val data =
            buildUpdateFormData(
                instructionSet = request.instructions,
                sharedSecretEncryptedDescriptor = sharedSecretEncryptedDescriptor,
                payloads = request.payloads,
                thumbnails = request.thumbnails,
                fileOperationsProvider = fileOperationsProvider,
            )

        val path = "/drives/${request.driveId}/files/by-uid/${request.uniqueId}"

        val result = pureUpdate(data, path, onProgress, onVersionConflict)

        if (result != null) {
            cleanupPayloadTempFiles(request.payloads)
        }

        return result;
    }

    /**
     * Changes the file with [globalTransitId] on [owner]'s [driveId] through this identity's server, which sends it from
     * its outbox: the answer says only that it was queued; how it went comes as a
     * [id.homebase.api.client.eventbus.BackendEvent.PeerSendResult]. A member keeps the file's key, so
     * [encryptedKeyHeader] goes as it is; the payloads are already encrypted, each under its own new IV.
     */
    suspend fun updateFileOverPeerByGlobalTransitId(
        owner: OdinId,
        driveId: Uuid,
        globalTransitId: Uuid,
        encryptedKeyHeader: EncryptedKeyHeader,
        metadata: UploadFileMetadata,
        payloads: List<PayloadFile>,
        onProgress: UploadProgress? = null,
    ): UpdateFileResult {
        preflightStagedPayloads(payloads, "updateFileOverPeerByGlobalTransitId gtid=$globalTransitId")
        val sharedSecret = requireCreds().secret.unsafeBytes
        val transferIv = ByteArrayUtil.getRndByteArray(16)
        val descriptor = buildSharedSecretEncryptedUpdateDescriptor(encryptedKeyHeader, metadata, sharedSecret, transferIv)
        val instructions = FileUpdateInstructionSet(
            transferIv = transferIv,
            locale = UpdateLocale.Peer,
            recipients = listOf(owner),
            manifest = UpdateManifest.build(payloads),
        )
        val data = buildUpdateFormData(instructions, descriptor, payloads, fileOperationsProvider = fileOperationsProvider)
        val total = calculateUploadSize(payloads, emptyList(), descriptor, fileOperationsProvider)
        val progress = onProgress?.let { report -> suspend { sent: Long, _: Long? -> report(sent, total) } }
        val result = requireNotNull(pureUpdate(data, "/peer/$owner/drives/$driveId/files/by-gtid/$globalTransitId", progress))
        cleanupPayloadTempFiles(payloads)
        return result
    }

    // ==================== LOCAL METADATA METHODS ====================

    /** Updates local metadata tags for a file. */
    suspend fun uploadLocalMetadataTags(
        file: FileIdFileIdentifier,
        localAppData: LocalAppData,
        onVersionConflict: (suspend () -> LocalMetadataUploadResult?)? = null
    ): LocalMetadataUploadResult? {

        val driveId = file.targetDrive.alias
        val fileId = file.fileId

        val requestBody =
            UpdateLocalMetadataTagsRequest(
                localVersionTag = localAppData.versionTag,
                tags = localAppData.tags
            )
                .let { OdinSystemSerializer.json.encodeToString(it) }

        val creds = requireCreds()

        val endpoint = "/drives/${driveId}/files/${fileId}/update-local-metadata-tags"
        val response =
            encryptedPatchJson(
                url = apiUrl(creds.domain, endpoint),
                token = creds.accessToken,
                jsonBody = requestBody,
                secret = creds.secret
            )

        if (response.status in 200..299) {
            return deserialize(response.body)
        }

        return handleErrorResponse(response, onVersionConflict) { it() }
    }

    /** Updates local metadata content for a file. */
    suspend fun uploadLocalMetadataContent(
        driveId: Uuid,
        file: HomebaseFile,
        localAppData: LocalAppData,
        onVersionConflict: (suspend () -> LocalMetadataUploadResult?)? = null
    ): LocalMetadataUploadResult? {

        val fileId = file.fileId
        val creds = requireCreds()

        // Decrypt key header if needed
        val decryptedKeyHeader: KeyHeader = file.keyHeader

        // If the caller pre-encrypted content and supplied an IV (e.g. stampConversationExitedAt
        // which encrypts at enqueue time to avoid a race with participant removal), pass them
        // through directly.  Otherwise encrypt here using the file's key header.
        val (ivToSend, encryptedContent) =
            if (localAppData.iv != null && localAppData.content != null) {
                // Already encrypted by the caller
                localAppData.iv to localAppData.content
            } else if (file.serverFileIsEncrypted && localAppData.content != null) {
                val keyHeader = KeyHeader(
                    iv = ByteArrayUtil.getRndByteArray(16),
                    aesKey = decryptedKeyHeader.aesKey
                )
                val encrypted = keyHeader.encryptDataAes(localAppData.content.encodeToByteArray())
                Base64Bytes(keyHeader.iv) to Base64.encode(encrypted)
            } else {
                null to localAppData.content
            }

        val requestBody =
            UpdateLocalMetadataContentRequest(
                iv = ivToSend,
                localVersionTag = localAppData.versionTag,
                content = encryptedContent
            ).let { OdinSystemSerializer.serialize(it) }

        val endpoint = "/drives/${driveId}/files/${fileId}/update-local-metadata-content"
        val response =
            encryptedPatchJson(
                url = apiUrl(creds.domain, endpoint),
                token = creds.accessToken,
                jsonBody = requestBody,
                secret = creds.secret
            )

        if (response.status in 200..299) {
            return deserialize(response.body)
        }

        return handleErrorResponse(response, onVersionConflict) { it() }

    }


    // ==================== LOW-LEVEL UPLOAD METHODS ====================

    /** Performs a raw upload to the drive. */
    suspend fun pureUpload(
        driveId: Uuid,
        data: MultiPartFormDataContent,
        fileSystemType: FileSystemType? = null,
        onProgress: UploadProgress? = null,
        onVersionConflict: (suspend () -> CreateFileResult?)? = null
    ): CreateFileResult? {

        val credentials = requireCreds()
        val url = uploadUrl(credentials.domain, driveId, fileSystemType)

        Logger.i(tag = TAG) { "drive upload url: [${url}]" }

        val response =
            plainPostMultipart(
                url = url,
                token = credentials.accessToken,
                formData = data,
                onProgress = onProgress
            )

        if (response.status in 200..299) {
            return OdinSystemSerializer.deserialize(response.body)
        }

        return handleErrorResponse(response, onVersionConflict) { it() }
    }


    private fun uploadUrl(domain: id.homebase.api.common.OdinId, driveId: Uuid, fileSystemType: FileSystemType?): String {
        val query = if (fileSystemType != null) "?xfst=$fileSystemType" else ""
        return apiUrl(domain, "/drives/$driveId/files$query")
    }

    /** Performs a raw update to an existing file on the drive. */
    suspend fun pureUpdate(
        data: MultiPartFormDataContent,
        path: String,
        onProgress: UploadProgress? = null,
        onVersionConflict: (suspend () -> UpdateFileResult?)? = null
    ): UpdateFileResult? {
        val credentials = requireCreds()

        val url = apiUrl(credentials.domain, path)
        val response =
            plainPatchMultipart(
                url = url,
                token = credentials.accessToken,
                formData = data,
                onProgress = onProgress
            )

        if (response.status in 200..299) {
            return OdinSystemSerializer.deserialize(response.body)
        }

        return handleErrorResponse(response, onVersionConflict) { it() }
    }


    private suspend fun buildSharedSecretEncryptedUpdateDescriptor(
        keyHeader: KeyHeader?,
        metadata: UploadFileMetadata,
        sharedSecret: ByteArray,
        transferIv: ByteArray,
        driveId: Uuid
    ): ByteArray =
        buildSharedSecretEncryptedUpdateDescriptor(lockKeyHeader(keyHeader, driveId, sharedSecret, transferIv), metadata, sharedSecret, transferIv)

    private suspend fun <T> handleErrorResponse(
        response: ApiResponse,
        onVersionConflict: (suspend () -> T?)? = null,
        invokeCallback: suspend ((suspend () -> T?)) -> T?
    ): T? {

        val errorResponse =
            runCatching {
                OdinSystemSerializer.deserialize<OdinErrorResponse>(response.body)
            }.getOrNull()

        if (
            errorResponse?.errorCode == OdinClientErrorCode.VersionTagMismatch &&
            onVersionConflict != null
        ) {
            return invokeCallback(onVersionConflict)
        }

        // Optional warning if handler not provided
        if (errorResponse?.errorCode == OdinClientErrorCode.VersionTagMismatch) {
            Logger.w(tag = TAG) {
                "VersionTagMismatch encountered with no onVersionConflict handler"
            }
        }

        throwForFailure(response)
        return null
    }

    private fun cleanupPayloadTempFiles(payloads: List<PayloadFile>?) {


        payloads?.forEach { payload ->
            val path = payload.filePath
            Logger.d(tag = TAG) { "Attempting to delete temp payload file $path" }

            val deleted =
                runCatching {
                    fileOperationsProvider.deleteTempFile(path)
                }.getOrElse { e ->
                    Logger.w(
                        throwable = e,
                        tag = TAG
                    ) { "Exception while deleting temp file: $path" }
                    false
                }

            if (deleted) {
                Logger.d(tag = TAG) { "Deleted temp payload file $path" }

            } else {
                Logger.w(tag = TAG) { "Temp file could not be deleted (best-effort): $path" }
            }
        }

        // The per-file loop above only kills the single payload file (e.g. the
        // HLS `index.ts`). For HLS sends we also want the parent `hls_<uuid>/`
        // dir gone — otherwise `index.m3u8` and any FFmpeg `.tmp` leftovers
        // accumulate (the cache leak chased in #6). cleanupHlsScratch is a
        // no-op for non-HLS payloads.
        cleanupHlsScratch(payloads)
    }
}
