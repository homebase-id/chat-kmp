package id.homebase.api.client.drives.files

import co.touchlab.kermit.Logger
import id.homebase.api.client.ByteApiResponse
import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.crypto.AesCbc
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.ServerFile
import id.homebase.api.client.drives.upload.TransferUploadStatus
import id.homebase.homebasekmppoc.prototype.lib.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

/** Options for payload operations with range support. */
data class PayloadOperationOptions(
    val fileSystemType: FileSystemType = FileSystemType.Standard,
    val chunkStart: Long? = null,
    val chunkLength: Long? = null,
    val lastModified: Long? = null
)

/** Response containing bytes and their content type. */
data class BytesResponse(val bytes: ByteArray, val contentType: String) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as BytesResponse
        if (!bytes.contentEquals(other.bytes)) return false
        if (contentType != other.contentType) return false
        return true
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + contentType.hashCode()
        return result
    }
}

@OptIn(ExperimentalEncodingApi::class)
public class DriveFileProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager
) : OdinApiProviderBase(httpClient, credentialsManager) {

    companion object {
        private const val TAG = "DriveFileProvider"
    }

    // ==================== GET METHODS ====================

    /**
     * Gets a file header with optional decryption.
     *
     * @param driveId The target drive id containing the file
     * @param fileId The ID of the file
     * @param options Optional operation options
     * @return The HomebaseFile or null if not found
     */

    suspend fun getFileHeader(
        driveId: Uuid,
        fileId: Uuid
    ): HomebaseFile? {

        ValidationUtil.requireValidUuid(driveId, "driveId")
        ValidationUtil.requireValidUuid(fileId, "fileId")

        val creds = requireCreds()
        val url = apiUrl(
            creds.domain,
            "/drives/$driveId/files/$fileId/header"
        )

        val response = encryptedGet(
            url = url,
            token = creds.accessToken,
            secret = creds.secret
        )

        if (response.status == 404) {
            return null
        }

        throwForFailure(response)

        var file = deserialize<ServerFile>(response.body)
        return file.asHomebaseFile(creds.secret)
    }


    suspend fun getPayloadBytesRaw(
        driveId: Uuid,
        fileId: Uuid,
        key: String,
        options: PayloadOperationOptions = PayloadOperationOptions()
    ): ByteApiResponse? {

        ValidationUtil.requireValidUuid(driveId, "driveId")
        ValidationUtil.requireValidUuid(fileId, "fileId")
        require(key.isNotBlank()) { "Key must be defined" }

        val creds = requireCreds()

        val queryParams =
            buildMap<String, String> {
                options.lastModified?.let {
                    put("lastModified", it.toString())
                }
            }

        val rangeResult =
            DriveFileHelpers.getRangeHeader(
                options.chunkStart,
                options.chunkLength
            )

        val path =
            if (options.chunkStart != null)
                "/drives/$driveId/files/$fileId/payload/$key/${options.chunkStart}/${options.chunkLength ?: ""}"
            else
                "/drives/$driveId/files/$fileId/payload/$key"

        val url = apiUrl(creds.domain, path)

        val response = requestBytes {
            httpClient.get(url) {
                bearerAuth(creds.accessToken)

                queryParams.forEach { (k, v) ->
                    url { parameters.append(k, v) }
                }

                rangeResult.rangeHeader?.let {
                    header(HttpHeaders.Range, it)
                }
            }
        }

        if (response.status == 404) return null

        if (response.status != 200 && response.status != 206) {
            throwForFailure(response)
        }

        if (response.bytes.isEmpty()) return null

        return response;
    }

    suspend fun getPayloadBytesDecrypted(
        driveId: Uuid,
        fileId: Uuid,
        key: String,
        chunkStart: Long? = null,
        chunkLength: Long? = null
    ): BytesResponse? {

        val raw =
            getPayloadBytesRaw(
                driveId = driveId,
                fileId = fileId,
                key = key,
                options = PayloadOperationOptions(
                    chunkStart = chunkStart,
                    chunkLength = chunkLength
                )
            ) ?: return null

        val rangeResult =
            DriveFileHelpers.getRangeHeader(chunkStart, chunkLength)

        val decryptedBytes =
            if (rangeResult.updatedChunkStart != null) {
                val decrypted =
                    decryptChunkedBytes(
                        raw.headers,
                        raw.bytes,
                        startOffset = rangeResult.startOffset,
                        chunkStart = (chunkStart ?: 0).toInt()
                    )

                val sliceEnd =
                    if (chunkLength != null && chunkStart != null) {
                        (chunkLength - chunkStart).toInt()
                    } else {
                        decrypted.size
                    }

                decrypted.sliceArray(0 until minOf(sliceEnd, decrypted.size))
            } else {
                decryptBytes(raw.headers, raw.bytes)
            }

        return BytesResponse(
            bytes = decryptedBytes,
            contentType = raw.contentType
        )
    }


    suspend fun getThumbBytesRaw(
        driveId: Uuid,
        fileId: Uuid,
        payloadKey: String,
        width: Int,
        height: Int,
        lastModified: Long? = null
    ): ByteApiResponse? {

        ValidationUtil.requireValidUuid(driveId, "driveId")
        ValidationUtil.requireValidUuid(fileId, "fileId")
        require(payloadKey.isNotBlank()) { "PayloadKey must be defined" }
        require(width > 0) { "Width must be positive" }
        require(height > 0) { "Height must be positive" }

        val creds = requireCreds()

        val queryParams =
            buildMap<String, String> {
                put("width", width.toString())
                put("height", height.toString())
                lastModified?.let {
                    put("lastModified", it.toString())
                }
            }


        val url =
            apiUrl(
                creds.domain,
                "/drives/$driveId/files/$fileId/payload/$payloadKey/thumb"
            )

        val response = requestBytes {
            httpClient.get(url) {
                bearerAuth(creds.accessToken)
                queryParams.forEach { (k, v) ->
                    url { parameters.append(k, v) }
                }
            }
        }

        if (response.status == 404) return null

        throwForFailure(response);

        if (response.bytes.isEmpty()) return null

        return response;
    }

    suspend fun getThumbBytesDecrypted(
        driveId: Uuid,
        fileId: Uuid,
        payloadKey: String,
        width: Int,
        height: Int,
        lastModified: Long? = null
    ): BytesResponse? {

        val raw =
            getThumbBytesRaw(
                driveId = driveId,
                fileId = fileId,
                payloadKey = payloadKey,
                width = width,
                height = height,
                lastModified = lastModified
            ) ?: return null

        val decryptedBytes =
            decryptBytes(raw.headers, raw.bytes)

        return BytesResponse(
            bytes = decryptedBytes,
            contentType = raw.contentType
        )
    }

    /**
     * Gets transfer history for a file.
     *
     * @param driveId The target drive containing the file
     * @param fileId The ID of the file
     * @param fileSystemType Optional file system type
     * @return The TransferHistory or null if not found
     */
    suspend fun getTransferHistory(
        driveId: Uuid,
        fileId: Uuid
    ): TransferHistory? {

        ValidationUtil.requireValidUuid(driveId, "driveId")
        ValidationUtil.requireValidUuid(fileId, "fileId")


        val creds = requireCreds()
        val endpoint = "/drives/${driveId}/files/${fileId}/transfer-history"

        val response = encryptedGet(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            secret = creds.secret
        )

        if (response.status == 404) {
            return null
        }

        throwForFailure(response);

        return deserialize<TransferHistory>(response.body)
    }

    // ==================== DELETE METHODS ====================

    /**
     * Deletes a single file from the drive.
     *
     * @param driveId The target drive containing the file
     * @param fileId The ID of the file to delete
     * @param recipients Optional list of recipients to notify
     * @param hardDelete If true, performs a hard delete instead of soft delete
     * @return True if the file was deleted successfully
     */
    suspend fun softDeleteFile(
        driveId: Uuid,
        fileId: Uuid,
        recipients: List<String>? = null
    ): DeleteFileResult {

        ValidationUtil.requireValidUuid(driveId, "driveId")
        ValidationUtil.requireValidUuid(fileId, "fileId")

        val endpoint = "/drives/$driveId/files/$fileId/delete"

        val creds = requireCreds()

        // fileId not used  because we pass it in via query string
        val request =
            DeleteFileRequest(
                fileId = Uuid.NIL,
                recipients = recipients
            )

        val response = encryptedPostJson(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(request),
            secret = creds.secret
        )

        throwForFailure(response);

        return deserialize<DeleteFileResult>(response.body)
    }

    suspend fun hardDeleteFile(
        driveId: Uuid,
        fileId: Uuid,
        recipients: List<String>? = null,
    ): Boolean {

        ValidationUtil.requireValidUuid(driveId, "driveId")
        ValidationUtil.requireValidUuid(fileId, "fileId")

        val endpoint = "/drives/$driveId/files/$fileId/hard-delete"

        val creds = requireCreds()

        // fileId not used  because we pass it in via query string
        val request =
            DeleteFileRequest(
                fileId = Uuid.NIL,
                recipients = recipients
            )

        val response = encryptedPostJson(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(request),
            secret = creds.secret
        )

        throwForFailure(response);

        return response.status == 200;
    }

    /** Deletes multiple files from the drive by file IDs. */
    suspend fun deleteFiles(
        driveId: Uuid,
        fileIds: List<Uuid>,
        recipients: List<String>? = null
    ): DeleteFileIdBatchResult {
        ValidationUtil.requireValidUuid(driveId, "driveId")
        ValidationUtil.requireValidUuidList(fileIds, "fileIds")
        val creds = requireCreds()

        val endpoint = "/drives/${driveId}/files/delete-batch/by-file-id";
        val request =
            DeleteFilesBatchRequest(
                requests =
                    fileIds.map { fileId ->
                        DeleteFileRequest(
                            fileId = fileId,
                            recipients = recipients
                        )
                    }
            )

        val response = encryptedPostJson(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(request),
            secret = creds.secret
        )

        throwForFailure(response);

        return deserialize<DeleteFileIdBatchResult>(response.body)

    }

    /** Deletes files from the drive by group IDs. */
    suspend fun deleteFilesByGroupId(
        driveId: Uuid,
        groupIds: List<Uuid>,
        recipients: List<String>? = null
    ): DeleteFilesByGroupIdBatchResult {
        ValidationUtil.requireValidUuid(driveId, "driveId")
        ValidationUtil.requireValidUuidList(groupIds, "groupIds")

        val creds = requireCreds()

        val endpoint = "/drives/${driveId}/files/delete-batch/by-group-id";
        val request =
            DeleteByGroupIdBatchRequest(
                requests =
                    groupIds.map { groupId ->
                        DeleteByGroupIdRequest(
                            groupId = groupId,
                            recipients = recipients
                        )
                    }
            )

        val response = encryptedPostJson(
            url = apiUrl(creds.domain, endpoint),
            token = creds.accessToken,
            jsonBody = OdinSystemSerializer.serialize(request),
            secret = creds.secret
        )

        throwForFailure(response);

        return deserialize<DeleteFilesByGroupIdBatchResult>(response.body)

    }

    // ==================== PRIVATE HELPER METHODS ====================

    /** Decrypts the key header using the shared secret. */
    private suspend fun decryptKeyHeader(encryptedKeyHeader: EncryptedKeyHeader): KeyHeader? {
        val sharedSecret = credentialsManager.getActiveCredentials()?.sharedSecret ?: return null
        return encryptedKeyHeader.decryptAesToKeyHeader(sharedSecret)
    }

    /** Decrypts JSON content from file metadata. */
    private suspend fun decryptJsonContent(metadata: FileMetadata, keyHeader: KeyHeader): String? {
        val content = metadata.appData.content ?: return null
        if (!metadata.isEncrypted) return content

        return try {
            val encryptedBytes = Base64.Default.decode(content)
            val decryptedBytes = keyHeader.decrypt(encryptedBytes)
            decryptedBytes.decodeToString()
        } catch (e: Exception) {
            Logger.Companion.e(TAG) { "[odin-kt:decryptJsonContent] ${e.message}" }
            null
        }
    }

    /**
     * Decrypts bytes using the shared secret (full payload/thumbnail decryption).
     */
    private suspend fun decryptBytes(
        headers: Headers,
        bytes: ByteArray
    ): ByteArray {

        val payloadEncrypted =
            headers["payloadencrypted"]?.equals("true", ignoreCase = true) == true

        val encryptedHeader64 =
            headers["sharedsecretencryptedheader64"]

        return when {
            payloadEncrypted && encryptedHeader64 != null -> {
                val encryptedKeyHeader =
                    EncryptedKeyHeader.fromBase64(encryptedHeader64)

                val keyHeader =
                    decryptKeyHeader(encryptedKeyHeader)
                        ?: error("Missing shared secret")

                decryptUsingKeyHeader(bytes, keyHeader)
            }

            payloadEncrypted ->
                error("Can't decrypt; missing keyheader")

            else ->
                bytes
        }
    }


    /** Decrypts chunked bytes with offset handling. */
    suspend fun decryptChunkedBytes(
        headers: Headers,
        responseBytes: ByteArray,
        startOffset: Int,
        chunkStart: Int
    ): ByteArray {

        val payloadEncrypted =
            headers["payloadencrypted"]?.equals("True", ignoreCase = false) == true

        val encryptedHeader64 =
            headers["sharedsecretencryptedheader64"]

        if (payloadEncrypted && encryptedHeader64 != null) {

            val encryptedKeyHeader = EncryptedKeyHeader.fromBase64(encryptedHeader64)
            val keyHeader = decryptKeyHeader(encryptedKeyHeader)
                ?: throw IllegalStateException("Can't decrypt; missing key header")

            val key = keyHeader.aesKey

            val (iv, cipher) = run {
                val padding = ByteArray(16) { 16 }

                val encryptedPadding =
                    AesCbc.encrypt(
                        padding,
                        key,
                        iv = responseBytes.copyOfRange(
                            responseBytes.size - 16,
                            responseBytes.size
                        )
                    ).copyOfRange(0, 16)

                if (chunkStart == 0) {
                    // First block
                    Pair(
                        keyHeader.iv,
                        mergeByteArrays(
                            listOf(responseBytes, encryptedPadding)
                        )
                    )
                } else {
                    // Middle blocks
                    Pair(
                        responseBytes.copyOfRange(0, 16),
                        mergeByteArrays(
                            listOf(
                                responseBytes.copyOfRange(16, responseBytes.size),
                                encryptedPadding
                            )
                        )
                    )
                }
            }

            val decryptedBytes = AesCbc.decrypt(cipher, key, iv)

            // Match TS behavior:
            // decryptedBytes.slice(startOffset ? startOffset - 16 : 0)
            val sliceStart =
                if (startOffset > 0) maxOf(startOffset - 16, 0) else 0

            return decryptedBytes.copyOfRange(sliceStart, decryptedBytes.size)

        } else {
            // Not encrypted → return raw bytes with offset
            return responseBytes.copyOfRange(startOffset, responseBytes.size)
        }
    }


    fun mergeByteArrays(chunks: List<ByteArray>): ByteArray {
        var size = 0
        for (chunk in chunks) {
            size += chunk.size
        }

        val merged = ByteArray(size)
        var offset = 0

        for (chunk in chunks) {
            chunk.copyInto(
                destination = merged,
                destinationOffset = offset
            )
            offset += chunk.size
        }

        return merged
    }

    private suspend fun decryptUsingKeyHeader(
        encryptedBytes: ByteArray,
        keyHeader: KeyHeader
    ): ByteArray {
        return keyHeader.decrypt(encryptedBytes)
    }

}

// Request data classes for delete operations

@Serializable
private data class DeleteFileRequest(val fileId: Uuid, val recipients: List<String>? = null)

@Serializable
enum class DeleteLinkedFileStatus(val value: String) {
    @SerialName("enqueued")
    Enqueued("enqueued"),

    @SerialName("enqueuedfailed")
    EnqueuedFailed("enqueuedfailed"),
}

@Serializable
data class DeleteFileResult(
    val fileId: Uuid,
    var localFileDeleted: Boolean,
    var localFileNotFound: Boolean,
    val recipientStatus: Map<String, TransferUploadStatus>? = null
)

@Serializable
data class DeleteFileIdBatchResult
    (
    val results: List<DeleteFileResult>
)

@Serializable
data class DeleteFilesByGroupIdBatchResult(
    val results: List<DeleteFileByGroupIdResult>
)

@Serializable
data class DeleteFileByGroupIdResult(
    val groupId: Uuid,
    val deleteFileResults: List<DeleteFileResult>
)

@Serializable
private data class DeleteFilesBatchRequest(val requests: List<DeleteFileRequest>)

@Serializable
private data class DeleteByGroupIdRequest(
    val groupId: Uuid,
    val recipients: List<String>? = null
)

@Serializable
private data class DeleteByGroupIdBatchRequest(val requests: List<DeleteByGroupIdRequest>)
