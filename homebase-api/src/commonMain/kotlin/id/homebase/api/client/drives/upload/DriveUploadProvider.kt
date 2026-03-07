package id.homebase.api.client.drives.upload

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
    val iv: String? = null
)

@Serializable
private data class UpdateLocalMetadataTagsRequest(
    val localVersionTag: String?,
    val tags: List<String>?
)

@Serializable
private data class UpdateLocalMetadataContentRequest(
    val iv: String? = null,
    val localVersionTag: String?,
    val content: String?
)

@Serializable
data class UploadFileRequest(
    val driveId: Uuid,
    /// The KeyHeader used to encrypt content and payloads
    val keyHeader: KeyHeader,
    val metadata: UploadFileMetadata,
    val payloads: List<PayloadFile> = emptyList(),
    val thumbnails: List<ThumbnailFile> = emptyList(),
    val transitOptions: TransitOptions? = null,
    val fileSystemType: FileSystemType? = null
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
        private const val TAG = "DriveUploadProvider"
    }

    // ==================== HIGH-LEVEL UPLOAD METHODS ====================

    suspend fun uploadFile(
        request: UploadFileRequest,
        onProgress: UploadProgress? = null,
        onVersionConflict: (suspend () -> CreateFileResult?)? = null
    ): CreateFileResult? {

        val creds = requireCreds()

        val sharedSecret = creds.secret.unsafeBytes

        val transferIv = ByteArrayUtil.getRndByteArray(16)
        val sharedSecretEncryptedDescriptor = buildEncryptedUploadDescriptor(
            request.keyHeader,
            request.metadata,
            sharedSecret,
            transferIv = transferIv
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

        val result =
            pureUpload(
                request.driveId, data, request.fileSystemType,
                onProgress,
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

        val creds = requireCreds()
        val sharedSecret = creds.secret.unsafeBytes

        // Build encrypted descriptor
        val sharedSecretEncryptedDescriptor =
            buildSharedSecretEncryptedUpdateDescriptor(
                request.keyHeader,
                request.metadata,
                sharedSecret,
                request.instructions.transferIv
            )

        val data =
            buildUpdateFormData(
                instructionSet = request.instructions,
                sharedSecretEncryptedDescriptor = sharedSecretEncryptedDescriptor,
                payloads = request.payloads,
                thumbnails = request.thumbnails,
                fileOperationsProvider = fileOperationsProvider
            )

        val path = "/drives/${request.driveId}/files/${request.fileId}"
        val result = pureUpdate(data, path, onProgress, onVersionConflict)

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
        // Build encrypted descriptor
        val sharedSecretEncryptedDescriptor =
            buildSharedSecretEncryptedUpdateDescriptor(
                request.keyHeader,
                request.metadata,
                sharedSecret,
                request.instructions.transferIv
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

        // Build key header with new IV
        val keyHeader: KeyHeader? =
            if (file.fileMetadata.isEncrypted) {
                KeyHeader(
                    iv = localAppData.iv?.let { Base64.decode(it) }
                        ?: ByteArrayUtil.getRndByteArray(16),
                    aesKey = decryptedKeyHeader.aesKey
                )
            } else null

        val (ivToSend, encryptedContent) =
            if (keyHeader != null && localAppData.content != null) {
                val encrypted = keyHeader.encryptDataAes(localAppData.content.encodeToByteArray())
                Base64.encode(keyHeader.iv) to Base64.encode(encrypted)
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
        val queryParams =
            buildMap {
                if (fileSystemType != null) {
                    put("xsft", fileSystemType.toString())
                }
            }

        val url =
            apiUrl(
                credentials.domain,
                buildString {
                    append("/drives/${driveId}/files")

                    if (queryParams.isNotEmpty()) {
                        append("?")
                        append(
                            queryParams.entries.joinToString("&") {
                                "${it.key}=${it.value}"
                            }
                        )
                    }
                }
            )

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


    /**
     * Builds an encrypted descriptor (UploadFileDescriptor encrypted with sharedSecret). Matches
     * TypeScript buildDescriptor function.
     *
     * @param keyHeader The key header to encrypt
     * @param metadata The already-encrypted file metadata
     * @param sharedSecret The shared secret for encryption
     * @param transferIv The transfer IV from instructions
     * @return AES-CBC encrypted descriptor bytes
     */
    private suspend fun buildEncryptedUploadDescriptor(
        keyHeader: KeyHeader?,
        metadata: UploadFileMetadata,
        sharedSecret: ByteArray,
        transferIv: ByteArray
    ): ByteArray {
        // Encrypt the key header using shared secret and transferIv (matches TypeScript
        // encryptKeyHeader)
        val sharedSecretEncryptedKeyHeader =
            EncryptedKeyHeader.encryptKeyHeaderAes(
                keyHeader ?: KeyHeader.empty(),
                transferIv,
                SecureByteArray(sharedSecret)
            )

        // Create the file descriptor
        val descriptor =
            UploadFileDescriptor(
                encryptedKeyHeader = sharedSecretEncryptedKeyHeader,
                fileMetadata = metadata
            )

        // Serialize to JSON (matches TypeScript jsonStringify64)
        val descriptorJson = OdinSystemSerializer.json.encodeToString(descriptor)
        val descriptorBytes = descriptorJson.encodeToByteArray()

        // Encrypt the entire descriptor with sharedSecret using transferIv (matches TypeScript
        // encryptWithSharedSecret)
        return AesCbc.encrypt(descriptorBytes, sharedSecret, transferIv)
    }

    /**
     * Builds an encrypted descriptor (UploadFileDescriptor encrypted with sharedSecret). Matches
     * TypeScript buildDescriptor function.
     *
     * @param keyHeader The key header to encrypt
     * @param metadata The already-encrypted file metadata
     * @param sharedSecret The shared secret for encryption
     * @param transferIv The transfer IV from instructions
     * @return AES-CBC encrypted descriptor bytes
     */
    private suspend fun buildSharedSecretEncryptedUpdateDescriptor(
        keyHeader: KeyHeader?,
        metadata: UploadFileMetadata,
        sharedSecret: ByteArray,
        transferIv: ByteArray
    ): ByteArray {
        // Encrypt the key header using shared secret and transferIv (matches TypeScript
        // encryptKeyHeader)
        val sharedSecretEncryptedKeyHeader =
            EncryptedKeyHeader.encryptKeyHeaderAes(
                keyHeader ?: KeyHeader.empty(),
                transferIv,
                SecureByteArray(sharedSecret)
            )

        // Create the file descriptor
        val descriptor =
            UpdateFileDescriptor(
                encryptedKeyHeader = sharedSecretEncryptedKeyHeader,
                fileMetadata = metadata
            )

        // Serialize to JSON (matches TypeScript jsonStringify64)
        val descriptorJson = OdinSystemSerializer.json.encodeToString(descriptor)
        val descriptorBytes = descriptorJson.encodeToByteArray()

        // Encrypt the entire descriptor with sharedSecret using transferIv (matches TypeScript
        // encryptWithSharedSecret)
        return AesCbc.encrypt(descriptorBytes, sharedSecret, transferIv)
    }

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
    }
}
