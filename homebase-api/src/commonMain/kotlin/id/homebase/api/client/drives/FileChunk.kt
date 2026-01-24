package id.homebase.api.client.drives

import kotlinx.serialization.Serializable

/**
 * Represents a chunk of a file for range-based operations.
 */
@Serializable
data class FileChunk(
    val start: Int,
    val length: Int
)
