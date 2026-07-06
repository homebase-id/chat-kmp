package id.homebase.api.client.drives.query

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResponse
import id.homebase.api.client.drives.ServerFile
import id.homebase.api.client.drives.files.ValidationUtil
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.serialization.UuidSerializer
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.Uuid
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.TargetDrive

/** Drive query provider for querying files from a drive */
class DriveQueryProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager
) : OdinApiProviderBase(httpClient, credentialsManager) {

    /** GET /drives/metadata/channel-drives — returns the feed channel drives the caller can read. */
    suspend fun getChannelDrives(): PagedResult<ClientDriveData> {
        val creds = requireCreds()
        val response = encryptedGet(
            url = apiUrl(creds.domain, "/drives/metadata/channel-drives"),
            token = creds.accessToken,
            secret = creds.secret,
        )
        throwForFailure(response)
        return deserialize(response.body)
    }

    /**
     * Query a batch of files from a drive.
     *
     * @param ownerOdinId when null (the default) the query targets the logged-in user's own host
     *   (`creds.domain`) at `/drives/$driveId/files/query-batch`. When set, the query is brokered
     *   over peer to the drive's owning identity at
     *   `/peer/$ownerOdinId/drives/$driveId/files/query-batch` — the user's own host proxies the
     *   call server-side and returns headers re-encrypted under the caller's own shared secret, so
     *   the response decode path is identical. Mirrors the `/peer/$peer/…` broker convention already
     *   used by [id.homebase.api.client.peer.PeerDriveQueryProvider].
     */
    suspend fun queryBatch(
        driveId: Uuid,
        request: QueryBatchRequest,
        ownerOdinId: OdinId? = null,
    ): QueryBatchResponse {

        ValidationUtil.requireValidUuid(driveId, "driveId")

        val creds = requireCreds()
        val path = if (ownerOdinId == null) {
            "/drives/$driveId/files/query-batch"
        } else {
            // Over-peer query-batch is a DRIVE-level route (no /files segment, unlike the per-file
            // peer routes /files/{fileId}/header etc.). Verified against the V2 backend: this returns
            // 403 unauthenticated (route exists) whereas /files/query-batch returns 404.
            "/peer/$ownerOdinId/drives/$driveId/query-batch"
        }
        val url = apiUrl(creds.domain, path)

        val jsonRequest = OdinSystemSerializer.serialize(request)

        val apiResponse = encryptedPostJson(
            url = url,
            token = creds.accessToken,
            jsonBody = jsonRequest,
            secret = creds.secret
        )

        throwForFailure(apiResponse)

        return mapQueryBatchResponse(apiResponse.body, creds.secret)
    }

    /**
     * Decode a query-batch response body into a [QueryBatchResponse], salvaging individually
     * corrupt file metadata rather than failing the whole batch. Shared by the own-host and
     * over-peer query paths — both receive headers encrypted under the caller's [secret].
     *
     * `internal` so the temporal-read path
     * ([id.homebase.api.client.peer.temporal.TemporalDriveReadProvider.temporalQueryBatch]) reuses the
     * exact same salvage-decode instead of duplicating it — its `/temporal/query-batch` response has
     * the identical [QueryBatchResponse] shape.
     */
    internal suspend fun mapQueryBatchResponse(
        body: String,
        secret: SecureByteArray,
    ): QueryBatchResponse {
        val internal = deserialize<QueryBatchResponseInternalRaw>(body)

        if (internal.invalidDrive) {
            return QueryBatchResponse.fromInvalidDrive(internal.name ?: "")
        }

        val files = internal.searchResults.map { serverFileJson ->
            createServerFileWithSafeMetadata(serverFileJson, secret)
        }

        return QueryBatchResponse(
            name = internal.name,
            invalidDrive = internal.invalidDrive,
            queryTime = internal.queryTime,
            includeMetadataHeader = internal.includeMetadataHeader,
            cursorState = internal.cursorState,
            searchResults = files,
            hasMoreRows = internal.hasMoreRows
        )
    }

    private suspend fun createServerFileWithSafeMetadata(serverFileJson: JsonObject, secret: SecureByteArray): HomebaseFile {
        // First deserialize ServerFile with FileMetadata as JsonObject
        val serverFileWithRawMetadata = try {
            OdinSystemSerializer.json.decodeFromString<ServerFileWithRawMetadata>(serverFileJson.toString())
        } catch (e: Throwable) {
            throw IllegalStateException("Failed to deserialize ServerFileWithRawMetadata: ${serverFileJson.toString().take(200)}", e)
        }
        
        // Try to deserialize FileMetadata separately, fallback to bad metadata if it fails
        val fileMetadata = try {
            OdinSystemSerializer.json.decodeFromString<FileMetadata>(serverFileWithRawMetadata.fileMetadata.toString())
        } catch (e: Throwable) {
            createBadFileMetadata(serverFileWithRawMetadata.fileMetadata)
        }
        
        // Create proper ServerFile with deserialized FileMetadata
        val serverFile = ServerFile(
            fileId = serverFileWithRawMetadata.fileId,
            driveId = serverFileWithRawMetadata.driveId,
            fileState = serverFileWithRawMetadata.fileState,
            fileSystemType = serverFileWithRawMetadata.fileSystemType,
            sharedSecretEncryptedKeyHeader = serverFileWithRawMetadata.sharedSecretEncryptedKeyHeader,
            fileMetadata = fileMetadata,
            serverMetadata = serverFileWithRawMetadata.serverMetadata ?: ServerMetadata(),
            priority = serverFileWithRawMetadata.priority,
            fileByteCount = serverFileWithRawMetadata.fileByteCount
        )
        
        return serverFile.asHomebaseFile(secret)
    }
    
    private fun createBadFileMetadata(fileMetadataJson: JsonObject): FileMetadata {
        // Try to salvage what we can from the corrupted FileMetadata
        val globalTransitId = fileMetadataJson["globalTransitId"]?.jsonPrimitive?.content?.let {
            try { Uuid.parse(it) } catch (e: Throwable) { null }
        }
        
        val created = fileMetadataJson["created"]?.jsonPrimitive?.content?.toLongOrNull()
        val updated = fileMetadataJson["updated"]?.jsonPrimitive?.content?.toLongOrNull()
        
        // Try to get uniqueId from appData
        val uniqueId = fileMetadataJson["appData"]?.jsonObject?.get("uniqueId")?.jsonPrimitive?.content?.let {
            try { Uuid.parse(it) } catch (e: Throwable) { null }
        }
        
        val badMessageContent = "Bad Message - Error: Corrupted FileMetadata"
        
        return FileMetadata(
            globalTransitId = globalTransitId,
            created = created?.let { UnixTimeUtc(it) } ?: UnixTimeUtc.ZeroTime,
            updated = updated?.let { UnixTimeUtc(it) } ?: UnixTimeUtc.ZeroTime,
            isEncrypted = false,
            appData = AppFileMetaData(
                uniqueId = uniqueId,
                content = badMessageContent,
                fileType = null,
                dataType = null
            )
        )
    }
}

@Serializable
data class ServerFileWithRawMetadata(
    @Serializable(with = UuidSerializer::class)
    val fileId: Uuid,
    val driveId: Uuid,
    val fileState: FileState,
    val fileSystemType: FileSystemType,
    val sharedSecretEncryptedKeyHeader: EncryptedKeyHeader,
    val fileMetadata: JsonObject, // This is the key difference - keep it as JsonObject
    // Over peer the broker returns serverMetadata=null (it's the owner's private server-side
    // bookkeeping; a member doesn't receive it). Nullable + default so the peer query decodes —
    // createServerFileWithSafeMetadata substitutes an empty ServerMetadata() downstream.
    val serverMetadata: ServerMetadata? = null,
    val priority: Int = 0,
    val fileByteCount: Long = 0
)

//@Serializable
//data class QueryBatchResponseInternal(
//    val name: String? = null,
//    val invalidDrive: Boolean = false,
//    val queryTime: UnixTimeUtc = UnixTimeUtc.ZeroTime,
//    val includeMetadataHeader: Boolean = false,
//    val cursorState: String? = null,
//    val searchResults: List<ServerFile> = emptyList(),
//    val hasMoreRows: Boolean = false
//)

@Serializable
data class QueryBatchResponseInternalRaw(
    val name: String? = null,
    val invalidDrive: Boolean = false,
    val queryTime: UnixTimeUtc = UnixTimeUtc.ZeroTime,
    val includeMetadataHeader: Boolean = false,
    val cursorState: String? = null,
    val searchResults: List<JsonObject> = emptyList(),
    val hasMoreRows: Boolean = false
)

@Serializable
data class ClientDriveData(
    val targetDrive: TargetDrive,
    val name: String? = null,
    val attributes: Map<String, String>? = null,
)
