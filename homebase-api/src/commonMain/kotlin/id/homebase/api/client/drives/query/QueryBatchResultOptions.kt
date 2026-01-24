package id.homebase.api.client.drives.query

import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import kotlinx.serialization.Serializable

/**
 * Internal query batch result options with cursor and pagination settings
 *
 * Ported from C# Odin.Services.Drives.DriveCore.Query.QueryBatchResultOptions
 */
@Serializable
data class QueryBatchResultOptions(
    val cursor: QueryBatchCursor = QueryBatchCursor(),
    val maxRecords: Int = 100,
    val includeHeaderContent: Boolean = false,
    val excludePreviewThumbnail: Boolean = false,
    val excludeServerMetaData: Boolean = false,
    val includeTransferHistory: Boolean = false,
    val ordering: QueryBatchSortOrder = QueryBatchSortOrder.Default,
    val sorting: QueryBatchSortField = QueryBatchSortField.CreatedDate
)
