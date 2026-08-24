package id.homebase.api.client.drives

import id.homebase.api.common.time.UnixTimeUtc
import kotlinx.serialization.Serializable

/**
 * Response body for POST /drives/query-batch-collection. One [QueryBatchCollectionSection] per
 * requested section, in request order, matched back by name. A section-level fault never fails the
 * collection — it comes back with a [QueryBatchSectionStatus] saying why.
 */
@Serializable
data class QueryBatchCollectionResponse(
    val results: List<QueryBatchCollectionSection>
)

/**
 * One section of a [QueryBatchCollectionResponse]. Distinct from [QueryBatchResponse], which stays
 * the shape of the single-query endpoints and carries none of the status fields.
 */
@Serializable
data class QueryBatchCollectionSection(
    val name: String? = null,

    /** True for every drive-level failure. Kept for callers that predate [status]; prefer [status]. */
    val invalidDrive: Boolean = false,

    /** Null when the server predates per-section status, or sent a value this client doesn't know. */
    val status: QueryBatchSectionStatus? = null,

    val errorMessage: String? = null,

    /** Server error code as its wire string; the client has no enum for the full set. */
    val errorCode: String? = null,

    val queryTime: UnixTimeUtc = UnixTimeUtc.ZeroTime,

    val includeMetadataHeader: Boolean = false,

    val cursorState: String? = null,

    val searchResults: List<HomebaseFile> = emptyList(),

    val hasMoreRows: Boolean = false
) {
    /** True when this section carries no data because something went wrong, rather than being up to date. */
    val isFailure: Boolean
        get() = status?.isFailure ?: invalidDrive

    /** Re-send this section next round: either it was skipped for budget, or it has more to page. */
    val needsAnotherRound: Boolean
        get() = hasMoreRows

    /** One greppable line per degraded section — a degraded-to-empty section must not read as "no changes". */
    fun describeFailure(): String =
        "section=${name ?: "?"} status=${status?.name ?: "unknown"}" +
            (errorCode?.let { " code=$it" } ?: "") +
            (errorMessage?.let { " message=$it" } ?: "")
}
