package id.homebase.api.client.link

import kotlinx.serialization.Serializable

@Serializable
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