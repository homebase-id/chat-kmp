package id.homebase.api.client.drives.files

import androidx.compose.runtime.Immutable
import id.homebase.api.client.drives.upload.EmbeddedThumb
import kotlinx.serialization.Serializable

@Serializable
@Immutable
data class ThumbnailDescriptor(
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
    val contentType: String? = null,
    val content: String? = null,
    val bytesWritten: Long? = null
) {
    val hasPositiveSize: Boolean get() = (pixelWidth ?: 0) > 0 && (pixelHeight ?: 0) > 0

    val aspectRatio: Float? get() = if (hasPositiveSize) pixelWidth!!.toFloat() / pixelHeight!! else null

    fun toEmbeddedThumb(): EmbeddedThumb {
        return EmbeddedThumb(
                pixelWidth = pixelWidth ?: 0,
                pixelHeight = pixelHeight ?: 0,
                contentType = contentType ?: "",
                content = content ?: ""
            )

    }

}