package id.homebase.homebasekmppoc.prototype.lib.drives.query

import kotlinx.serialization.Serializable

/**
 * Paging and cursor models for API responses.
 *
 * Ported from TypeScript paging interfaces
 */

@Serializable
data class PagedResult<T>(
    val totalPages: Int,
    val results: List<T>
)

@Serializable
data class CursoredResult<T>(
    val results: T,
    val cursorState: String
)

@Serializable
data class NumberCursoredResult<T>(
    val results: List<T>,
    val cursor: String? = null
)

@Serializable
data class MultiRequestCursoredResult<T>(
    val results: T,
    val cursorState: Map<String, String>
)

@Serializable
data class PagingOptions(
    val pageNumber: Int,
    val pageSize: Int
)
