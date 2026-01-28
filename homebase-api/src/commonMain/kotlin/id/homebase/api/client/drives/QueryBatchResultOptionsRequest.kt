package id.homebase.api.client.drives

import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.drives.query.QueryBatchResultOptions
import kotlinx.serialization.Serializable

/**
 * Query batch result options request model
 *
 */
@Serializable
data class QueryBatchResultOptionsRequest(
    /**
     * Base64 encoded value of the cursor state used when paging/chunking through records
     */
    val cursorState: String? = null,

    /**
     * Max number of records to return
     */
    val maxRecords: Int = 100,

    /**
     * Specifies if the result set includes the metadata header (assuming the file has one)
     */
    val includeMetadataHeader: Boolean = false,

    /**
     * If true, the transfer history with-in the server metadata will be including
     */
    val includeTransferHistory: Boolean = false,

    val ordering: QueryBatchSortOrder? = null,

    val sorting: QueryBatchSortField? = null
) {
    fun toQueryBatchResultOptions(): QueryBatchResultOptions {
        return QueryBatchResultOptions(
            cursor = if (cursorState.isNullOrEmpty()) {
                QueryBatchCursor()
            } else {
                QueryBatchCursor.Companion.fromJson(
                    cursorState
                )
            },
            maxRecords = maxRecords,
            includeHeaderContent = includeMetadataHeader,
            includeTransferHistory = includeTransferHistory,
            ordering = ordering
                ?: QueryBatchSortOrder.Default,
            sorting = sorting
                ?: QueryBatchSortField.CreatedDate
        )
    }

    companion object {
        val Default = QueryBatchResultOptionsRequest(
                maxRecords = 10,
                includeMetadataHeader = true
            )
    }
}