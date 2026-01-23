package id.homebase.homebasekmppoc.prototype.lib.core

import id.homebase.homebasekmppoc.prototype.lib.drives.query.QueryBatchCursor

/**
 * Generic batch result wrapper for paginated queries.
 *
 * This is a type-safe alternative to QueryBatchResult that can wrap any domain model type
 * (ChatMessageData, ConversationData, etc.).
 *
 * @param T The type of records in this batch
 * @property records The list of records in this batch
 * @property hasMoreRows Whether there are more records available for pagination
 * @property cursor The cursor for fetching the next batch
 */
data class BatchResult<T>(
        val records: List<T>,
        val hasMoreRows: Boolean,
        val cursor: QueryBatchCursor
) {
    companion object {
        /** Creates an empty BatchResult with no records. */
        fun <T> empty(): BatchResult<T> =
                BatchResult(records = emptyList(), hasMoreRows = false, cursor = QueryBatchCursor())
    }

    /** Maps the records to a different type using the provided transform function. */
    fun <R> map(transform: (T) -> R): BatchResult<R> =
            BatchResult(
                    records = records.map(transform),
                    hasMoreRows = hasMoreRows,
                    cursor = cursor
            )

    /** Returns true if there are no records in this batch. */
    fun isEmpty(): Boolean = records.isEmpty()

    /** Returns the number of records in this batch. */
    val size: Int
        get() = records.size
}
