package id.homebase.api.client.drives.query

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResponse
import id.homebase.api.client.drives.ServerFile
import id.homebase.api.client.drives.files.ValidationUtil
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

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

        val internal = deserialize<QueryBatchResponseInternal>(apiResponse.body)

        if (internal.invalidDrive) {
            return QueryBatchResponse.fromInvalidDrive(internal.name ?: "")
        }

        val files = internal.searchResults.map { encryptedFile ->
            encryptedFile.asHomebaseFile(creds.secret)
        }

        return QueryBatchResponse(
            name = internal.name,
            invalidDrive = internal.invalidDrive,
            queryTime = internal.queryTime,
            includeMetadataHeader = internal.includeMetadataHeader,
            cursorState = internal.cursorState,
            searchResults = files,
            // TODO: The SERVER response should include the server-side hasMoreRows boolean,
            //  this is not right
            hasMoreRows = files.count() >= request.resultOptionsRequest.maxRecords
        )
    }
}

@Serializable
data class QueryBatchResponseInternal(
    val name: String? = null,
    val invalidDrive: Boolean = false,
    val queryTime: UnixTimeUtc = UnixTimeUtc.ZeroTime,
    val includeMetadataHeader: Boolean = false,
    val cursorState: String? = null,
    val searchResults: List<ServerFile> = emptyList()
)
