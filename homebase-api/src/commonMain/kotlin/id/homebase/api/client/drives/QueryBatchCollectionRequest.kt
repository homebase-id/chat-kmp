package id.homebase.api.client.drives

import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.serialization.UuidSerializer
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Request body for POST /drives/query-batch-collection — runs multiple named
 * query-batch sections against one or more drives in a single round trip.
 */
@Serializable
data class QueryBatchCollectionRequest(
    val queries: List<CollectionQueryParamSection>
)

/**
 * One named section of a [QueryBatchCollectionRequest]. `name` must be unique within
 * the request (the server rejects duplicates with a 400) and is echoed back on the
 * matching [QueryBatchResponse] so results can be correlated.
 */
@Serializable
data class CollectionQueryParamSection(
    val name: String,
    @Serializable(with = UuidSerializer::class)
    val driveId: Uuid,
    val queryParams: FileQueryParams,
    val resultOptionsRequest: QueryBatchResultOptionsRequest
)
