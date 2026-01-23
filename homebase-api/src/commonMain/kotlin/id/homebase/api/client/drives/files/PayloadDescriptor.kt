package id.homebase.homebasekmppoc.prototype.lib.drives.files

import kotlinx.serialization.Serializable

@Serializable
data class PayloadDescriptor(
    val key: String,
    val contentType: String? = null,
    val thumbnails: List<id.homebase.homebasekmppoc.prototype.lib.drives.files.ThumbnailDescriptor>? = null,
    val iv: String? = null,
    val bytesWritten: Long? = null,
    val lastModified: Long? = null,
    val descriptorContent: String? = null,
    val previewThumbnail: id.homebase.homebasekmppoc.prototype.lib.drives.files.ThumbnailDescriptor? = null,
    val uid: Long? = null
// Add fields as needed
) {
    fun keyEquals(otherKey: String): Boolean {
        return key.equals(otherKey, ignoreCase = true)
    }
}