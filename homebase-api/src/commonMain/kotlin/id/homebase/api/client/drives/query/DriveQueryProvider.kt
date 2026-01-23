package id.homebase.api.client.drives.query

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.homebasekmppoc.prototype.lib.core.time.UnixTimeUtc
import id.homebase.homebasekmppoc.prototype.lib.drives.QueryBatchRequest
import id.homebase.homebasekmppoc.prototype.lib.drives.QueryBatchResponse
import id.homebase.homebasekmppoc.prototype.lib.drives.ServerFile
import id.homebase.homebasekmppoc.prototype.lib.drives.files.ValidationUtil
import id.homebase.homebasekmppoc.prototype.lib.serialization.OdinSystemSerializer
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
            return QueryBatchResponse.Companion.fromInvalidDrive(internal.name ?: "")
        }

        val files = internal.searchResults.map { encryptedFile ->
            encryptedFile.asHomebaseFile(creds.secret)
        }

        return _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.QueryBatchResponse(
            name = internal.name,
            invalidDrive = internal.invalidDrive,
            queryTime = internal.queryTime,
            includeMetadataHeader = internal.includeMetadataHeader,
            cursorState = internal.cursorState,
            searchResults = files
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
