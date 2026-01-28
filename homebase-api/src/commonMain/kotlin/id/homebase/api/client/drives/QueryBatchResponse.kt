package id.homebase.api.client.drives

import id.homebase.api.common.time.UnixTimeUtc
import kotlinx.serialization.Serializable

/**
 * Query batch response
 * Ported from C# Odin.Services.Drives.QueryBatchResponse
 */
@Serializable
data class QueryBatchResponse(
    /**
     * Name of this result when used in a batch-collection
     */
    val name: String? = null,

    /**
     * When true, the targetDrive queried for this section was not accessible due to permissions or did not exist
     */
    val invalidDrive: Boolean = false,

    /**
     * Indicates when this result was generated
     */

    val queryTime: UnixTimeUtc = UnixTimeUtc.Companion.ZeroTime,

    val includeMetadataHeader: Boolean = false,

    val cursorState: String? = null,

    val searchResults: List<HomebaseFile> = emptyList(),

    val hasMoreRows: Boolean = false
) {

    companion object {
        fun fromInvalidDrive(name: String): QueryBatchResponse {
            return QueryBatchResponse(
                name = name,
                invalidDrive = true,
                searchResults = emptyList()
            )
        }
    }
}