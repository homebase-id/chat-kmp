package id.homebase.api.client.link

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
@Immutable
data class LinkPreview(
    val title: String,
    val url: String,
    val description: String,
    val imageUrl: String?,
    val imageHeight: Int?,
    val imageWidth: Int?,
) {
    fun getThumbUrl(): String {
        return ""
    }
}