package id.homebase.api.client.drives.files

import id.homebase.api.serialization.Base64Bytes
import id.homebase.api.serialization.LenientBase64BytesSerializer
import androidx.compose.runtime.Immutable
import id.homebase.api.client.drives.upload.EmbeddedThumb
import kotlinx.serialization.Serializable

@Serializable
@Immutable
data class ThumbnailDescriptor(
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
    val contentType: String? = null,
    @Serializable(with = LenientBase64BytesSerializer::class) val content: Base64Bytes? = null,
    val bytesWritten: Long? = null
) {
    fun toEmbeddedThumb(): EmbeddedThumb {
        return EmbeddedThumb(
                pixelWidth = pixelWidth ?: 0,
                pixelHeight = pixelHeight ?: 0,
                contentType = contentType ?: "",
                content = content
            )

    }

}