package id.homebase.homebasekmppoc.prototype.lib.drives

import id.homebase.homebasekmppoc.prototype.lib.drives.query.QueryBatchCursor
import id.homebase.homebasekmppoc.prototype.lib.drives.query.QueryBatchResultOptions
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

    val ordering: id.homebase.homebasekmppoc.prototype.lib.drives.QueryBatchSortOrder? = null,

    val sorting: id.homebase.homebasekmppoc.prototype.lib.drives.QueryBatchSortField? = null
) {
    fun toQueryBatchResultOptions(): id.homebase.homebasekmppoc.prototype.lib.drives.query.QueryBatchResultOptions {
        return _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.query.QueryBatchResultOptions(
            cursor = if (cursorState.isNullOrEmpty()) {
                _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.query.QueryBatchCursor()
            } else {
                _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.query.QueryBatchCursor.Companion.fromJson(
                    cursorState
                )
            },
            maxRecords = maxRecords,
            includeHeaderContent = includeMetadataHeader,
            includeTransferHistory = includeTransferHistory,
            ordering = ordering
                ?: _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.QueryBatchSortOrder.Default,
            sorting = sorting
                ?: _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.QueryBatchSortField.CreatedDate
        )
    }

    companion object {
        val Default =
            _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.QueryBatchResultOptionsRequest(
                maxRecords = 10,
                includeMetadataHeader = true
            )
    }
}