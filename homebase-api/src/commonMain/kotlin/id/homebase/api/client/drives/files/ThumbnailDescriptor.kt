package id.homebase.homebasekmppoc.prototype.lib.drives.files

import kotlinx.serialization.Serializable

@Serializable
data class ThumbnailDescriptor(
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
    val contentType: String? = null,
    val content: String? = null,
    val bytesWritten: Long? = null
)