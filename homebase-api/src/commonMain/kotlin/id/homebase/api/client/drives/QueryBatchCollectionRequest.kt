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
    val queries: List<CollectionQueryParamSection>,

    /**
     * Budget for the whole call, not per section: sections are filled greedily in request order and
     * those the budget doesn't reach come back [QueryBatchSectionStatus.BudgetExhausted] with their
     * cursor echoed, to be re-sent next round. Must be at least 1 or the server rejects the call.
     */
    val maxRecords: Int
)

/**
 * One named section of a [QueryBatchCollectionRequest]. `name` must be unique within
 * the request (the server rejects duplicates with a 400) and is echoed back on the
 * matching [QueryBatchCollectionSection] so results can be correlated.
 */
@Serializable
data class CollectionQueryParamSection(
    val name: String,
    @Serializable(with = UuidSerializer::class)
    val driveId: Uuid,
    val queryParams: FileQueryParams,
    val resultOptionsRequest: CollectionSectionResultOptions,

    /** Null falls back to the request-level file system, so one collection can mix Standard and Comment. */
    val fileSystemType: FileSystemType? = null
)

/**
 * Per-section result options. Without `maxRecords` — the budget is set once for the whole call on
 * [QueryBatchCollectionRequest.maxRecords], and a per-section value would be silently ignored.
 */
@Serializable
data class CollectionSectionResultOptions(
    val cursorState: String? = null,
    val includeMetadataHeader: Boolean = false,
    val includeTransferHistory: Boolean = false,
    val ordering: QueryBatchSortOrder = QueryBatchSortOrder.OldestFirst,
    val sorting: QueryBatchSortField = QueryBatchSortField.AnyChangeDate
)
