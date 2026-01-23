package id.homebase.homebasekmppoc.prototype.lib.drives
import id.homebase.homebasekmppoc.prototype.lib.drives.query.FileQueryParams
import kotlinx.serialization.Serializable

/**
 * Query batch request containing query parameters and result options
 *
 */
@Serializable
data class QueryBatchRequest(
    val queryParams: id.homebase.homebasekmppoc.prototype.lib.drives.query.FileQueryParams,
    val resultOptionsRequest: id.homebase.homebasekmppoc.prototype.lib.drives.QueryBatchResultOptionsRequest
)