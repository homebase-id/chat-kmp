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
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
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
            try {
                val serverFile = OdinSystemSerializer.json.decodeFromString<ServerFile>(serverFileJson.toString())
                serverFile.asHomebaseFile(creds.secret)
            } catch (e: Throwable) {
                createBadMessage(serverFileJson, creds.secret)
            }
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

    private suspend fun createBadMessage(serverFileJson: JsonObject, secret: SecureByteArray): HomebaseFile {
        val fileId = serverFileJson["fileId"]?.jsonPrimitive?.content?.let { 
            try { Uuid.parse(it) } catch (e: Throwable) { null }
        } ?: Uuid.random()
        
        val driveId = serverFileJson["driveId"]?.jsonPrimitive?.content?.let { 
            try { Uuid.parse(it) } catch (e: Throwable) { null }
        } ?: Uuid.random()
        
        val uniqueId = serverFileJson["fileMetadata"]?.jsonObject?.get("appData")?.jsonObject?.get("uniqueId")?.jsonPrimitive?.content?.let {
            try { Uuid.parse(it) } catch (e: Throwable) { null }
        }
        
        val badMessageContent = "Bad Message - FileId: ${fileId}, Original Error: Unable to deserialize"
        
        val badFileMetadata = FileMetadata(
            globalTransitId = uniqueId,
            appData = AppFileMetaData(
                uniqueId = uniqueId,
                content = badMessageContent,
                fileType = null,
                dataType = null
            ),
            isEncrypted = false
        )
        
        val badServerFile = ServerFile(
            fileId = fileId,
            driveId = driveId,
            fileState = FileState.Active,
            fileSystemType = FileSystemType.Standard,
            sharedSecretEncryptedKeyHeader = EncryptedKeyHeader.empty(),
            fileMetadata = badFileMetadata,
            serverMetadata = ServerMetadata(),
            priority = 0,
            fileByteCount = badMessageContent.length.toLong()
        )
        
        return badServerFile.asHomebaseFile(secret)
    }
}

@Serializable
data class QueryBatchResponseInternal(
    val name: String? = null,
    val invalidDrive: Boolean = false,
    val queryTime: UnixTimeUtc = UnixTimeUtc.ZeroTime,
    val includeMetadataHeader: Boolean = false,
    val cursorState: String? = null,
    val searchResults: List<ServerFile> = emptyList(),
    val hasMoreRows: Boolean = false
)

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
