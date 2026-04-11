package id.homebase.api.client.drives.query

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
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

/** Drive query provider for querying files from a drive */
class DriveQueryProvider(
    httpClient: HttpClient,
    credentialsManager: CredentialsManager
) : OdinApiProviderBase(httpClient, credentialsManager) {

    suspend fun queryBatch(
        driveId: Uuid,
        request: QueryBatchRequest,
    ): QueryBatchResponse {

        ValidationUtil.requireValidUuid(driveId, "driveId")

        val creds = requireCreds()
        val url = apiUrl(
            creds.domain,
            "/drives/$driveId/files/query-batch"
        )

        val jsonRequest = OdinSystemSerializer.serialize(request)

        val apiResponse = encryptedPostJson(
            url = url,
            token = creds.accessToken,
            jsonBody = jsonRequest,
            secret = creds.secret
        )

        throwForFailure(apiResponse)

        val internal = deserialize<QueryBatchResponseInternalRaw>(apiResponse.body)

        if (internal.invalidDrive) {
            return QueryBatchResponse.fromInvalidDrive(internal.name ?: "")
        }

        val files = internal.searchResults.map { serverFileJson ->
            createServerFileWithSafeMetadata(serverFileJson, creds.secret)
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
            serverMetadata = serverFileWithRawMetadata.serverMetadata,
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
    val serverMetadata: ServerMetadata,
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
