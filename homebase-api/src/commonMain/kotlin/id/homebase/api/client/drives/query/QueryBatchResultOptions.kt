package id.homebase.homebasekmppoc.prototype.lib.drives.query

import id.homebase.homebasekmppoc.prototype.lib.drives.QueryBatchSortField
import id.homebase.homebasekmppoc.prototype.lib.drives.QueryBatchSortOrder
import kotlinx.serialization.Serializable

/**
 * Internal query batch result options with cursor and pagination settings
 *
 * Ported from C# Odin.Services.Drives.DriveCore.Query.QueryBatchResultOptions
 */
@Serializable
data class QueryBatchResultOptions(
    val cursor: id.homebase.homebasekmppoc.prototype.lib.drives.query.QueryBatchCursor = _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.query.QueryBatchCursor(),
    val maxRecords: Int = 100,
    val includeHeaderContent: Boolean = false,
    val excludePreviewThumbnail: Boolean = false,
    val excludeServerMetaData: Boolean = false,
    val includeTransferHistory: Boolean = false,
    val ordering: id.homebase.homebasekmppoc.prototype.lib.drives.QueryBatchSortOrder = _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.QueryBatchSortOrder.Default,
    val sorting: id.homebase.homebasekmppoc.prototype.lib.drives.QueryBatchSortField = _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.QueryBatchSortField.CreatedDate
)
