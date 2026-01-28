package id.homebase.api.client.drives
import id.homebase.api.client.drives.query.FileQueryParams
import kotlinx.serialization.Serializable

/**
 * Query batch request containing query parameters and result options
 *
 */
@Serializable
data class QueryBatchRequest(
    val queryParams: FileQueryParams,
    val resultOptionsRequest: QueryBatchResultOptionsRequest
)