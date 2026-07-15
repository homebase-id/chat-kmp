package id.homebase.api.client.drives

import kotlinx.serialization.Serializable

/**
 * Response body for POST /drives/query-batch-collection. One [QueryBatchResponse] per
 * requested section, matched back by [QueryBatchResponse.name]. A section whose drive
 * didn't exist or wasn't readable comes back with `invalidDrive = true` and empty
 * `searchResults` rather than failing the whole collection.
 */
@Serializable
data class QueryBatchCollectionResponse(
    val results: List<QueryBatchResponse>
)
