package id.homebase.api.client.drives.files

import co.touchlab.kermit.Logger
import id.homebase.api.client.ByteApiResponse
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.crypto.AesCbc
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import io.ktor.utils.io.ByteReadChannel
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable

private fun ByteReadChannel.asFlow(chunkSize: Int = 64 * 1024): Flow<ByteArray> = flow {
    val buffer = ByteArray(chunkSize)

    while (!isClosedForRead) {
        val read = readAvailable(buffer)
        if (read > 0) emit(buffer.copyOf(read))
    }
}

@OptIn(ExperimentalEncodingApi::class)
public class DriveFileHttpProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager
) : OdinApiProviderBase(httpClient, credentialsManager) {

    companion object {
        private const val TAG = "DriveFileHttpProvider"
    }

    // ==================== GET METHODS ====================
    suspend fun streamPayloadDecryptedToPath(
        driveId: Uuid,
        fileId: Uuid,
        key: String,
        keyHeader: KeyHeader,
        outputPath: String,
        fileOps: FileOperationsProvider
    ): Boolean {

        ValidationUtil.requireValidUuid(driveId, "driveId")
        ValidationUtil.requireValidUuid(fileId, "fileId")
        require(key.isNotBlank()) { "Key must be defined" }

        val creds = requireCreds()

        val url =
            apiUrl(
                creds.domain,
                "/drives/$driveId/files/$fileId/payload/$key"
            )

        val response =
            httpClient.get(url) {
                bearerAuth(creds.accessToken)
            }

        if (response.status.value == 404) return false

        if (response.status.value !in listOf(200, 206)) {
            throwForFailure(
                ByteApiResponse(
                    status = response.status.value,
                    headers = response.headers,
                    bytes = ByteArray(0),
                    contentType = "application/octet-stream"
                )
            )
        }

        val encryptedFlow =
            response.bodyAsChannel().asFlow()

        val decryptedFlow =
            AesCbc.streamDecryptWithCbc(
                encryptedFlow,
                keyHeader.aesKey,
                keyHeader.iv
            )

        fileOps.writeStream(outputPath, decryptedFlow)

        return true
    }

    // This ought to be private / protected and only used by driveCache but probably rewire it all
    // TODO: Shouldn't it (always) be streaming?! If it's a 500MB file we dont want it in memory
    // TODO: Should we discern between HLS needing chunks and payloads not needing chunks? Two different calls?
    suspend fun getPayloadBytesRawNetwork(
        driveId: Uuid,
        fileId: Uuid,
        key: String,
        options: PayloadOperationOptions = PayloadOperationOptions(),
        onDownloadProgress: ((Float) -> Unit)? = null,
    ): ByteApiResponse {

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

                onDownloadProgress?.let { callback ->
                    onDownload { bytesReceived, contentLength ->
                        val total = contentLength ?: 0L
                        if (total > 0L) {
                            callback(bytesReceived.toFloat() / total.toFloat())
                        }
                        // if Content-Length is absent we still get called — progress stays
                        // at the synthetic milestones emitted by the caller
                    }
                }
            }
        }

        if (response.status != 200 && response.status != 206) {
            throwForFailure(response)
        }

        return response
    }

    // TODO: I suppose we can live with this NOT being streaming, but maybe we can insert a
    // safeguard so that if it's larger than 1MB we return an error (I believe thumb max is
    // roughly 1MB)
    suspend fun getThumbBytesRawNetwork(
        driveId: Uuid,
        fileId: Uuid,
        payloadKey: String,
        width: Int,
        height: Int,
        lastModified: Long? = null
    ): ByteApiResponse {

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

        if (response.status != 200 && response.status != 206) {
            throwForFailure(response)
        }

        return response
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
        recipients: List<OdinId>? = null
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

        throwForFailure(response)

        return deserialize<DeleteFileResult>(response.body)
    }

    suspend fun hardDeleteFile(
        driveId: Uuid,
        fileId: Uuid,
        recipients: List<OdinId>? = null,
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

        throwForFailure(response)

        return response.status == 200
    }

    /** Deletes multiple files from the drive by file IDs. */
    suspend fun deleteFiles(
        driveId: Uuid,
        fileIds: List<Uuid>,
        recipients: List<OdinId>? = null
    ): DeleteFileIdBatchResult {
        ValidationUtil.requireValidUuid(driveId, "driveId")
        ValidationUtil.requireValidUuidList(fileIds, "fileIds")
        val creds = requireCreds()

        val endpoint = "/drives/${driveId}/files/delete-batch/by-file-id"
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

        throwForFailure(response)

        return deserialize<DeleteFileIdBatchResult>(response.body)

    }

    /** Deletes files from the drive by group IDs. */
    suspend fun deleteFilesByGroupId(
        driveId: Uuid,
        groupIds: List<Uuid>,
        recipients: List<OdinId>? = null
    ): DeleteFilesByGroupIdBatchResult {
        ValidationUtil.requireValidUuid(driveId, "driveId")
        ValidationUtil.requireValidUuidList(groupIds, "groupIds")

        val creds = requireCreds()

        val endpoint = "/drives/${driveId}/files/delete-batch/by-group-id"
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

        throwForFailure(response)

        return deserialize<DeleteFilesByGroupIdBatchResult>(response.body)

    }

    // ==================== INBOX METHODS ====================

    // Disabled — server now auto-processes the inbox on QueryBatch, so the
    // explicit HTTP poke is no longer needed. Kept commented so it can be
    // re-enabled if the server-side behaviour regresses.
    //
    // suspend fun processInbox(
    //     driveId: Uuid,
    //     batchSize: Int = 10
    // ): InboxStatus {
    //     ValidationUtil.requireValidUuid(driveId, "driveId")
    //
    //     val creds = requireCreds()
    //     val endpoint = "/drives/$driveId/inbox/process"
    //
    //     val response = encryptedGet(
    //         url = apiUrl(creds.domain, endpoint),
    //         token = creds.accessToken,
    //         secret = creds.secret,
    //         queryString = "batchSize=$batchSize"
    //     )
    //
    //     throwForFailure(response)
    //
    //     return deserialize<InboxStatus>(response.body)
    // }

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
            val encryptedBytes = Base64.decode(content)
            val decryptedBytes = keyHeader.decrypt(encryptedBytes)
            decryptedBytes.decodeToString()
        } catch (e: Exception) {
            Logger.e(tag = TAG) { "[odin-kt:decryptJsonContent] ${e.message}" }
            null
        }
    }

    /**
     * Decrypts bytes using the shared secret (full payload/thumbnail decryption).
     */
    public suspend fun decryptBytes(
        keyHeader: KeyHeader,
        headers: Headers,
        bytes: ByteArray
    ): ByteArray {

        val payloadEncrypted =
            headers["payloadencrypted"]?.equals("true", ignoreCase = true) == true

//        val encryptedHeader64 =
//            headers["sharedsecretencryptedheader64"]

        return when {
            payloadEncrypted -> {
                decryptUsingKeyHeader(bytes, keyHeader)
            }

            else -> bytes
        }
    }


    /** Decrypts chunked bytes with offset handling. */
    suspend fun decryptChunkedBytes(
        headers: Headers,
        responseBytes: ByteArray,
        keyHeader: KeyHeader,
        startOffset: Int,
        chunkStart: Int
    ): ByteArray {

        val payloadEncrypted =
            headers["payloadencrypted"]?.equals("true", ignoreCase = true) == true

        if (payloadEncrypted) {

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
