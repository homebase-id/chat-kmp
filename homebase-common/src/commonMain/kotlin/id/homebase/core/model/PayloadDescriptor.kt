
package id.homebase.core.model

import kotlinx.serialization.Serializable

@Serializable
data class PayloadDescriptor(
    val key: String,
    val contentType: String? = null,
    val thumbnails: List<ThumbnailDescriptor>? = null,
    val iv: String? = null,
    val bytesWritten: Long? = null,
    val lastModified: Long? = null,
    val descriptorContent: String? = null,
    val previewThumbnail: ThumbnailDescriptor? = null,
    val uid: Long? = null
// Add fields as needed
) {
    fun keyEquals(otherKey: String): Boolean {
        return key.equals(otherKey, ignoreCase = true)
    }
}