package id.homebase.chat.services.builder

import kotlinx.serialization.Serializable

@Serializable
data class LinkPreviewDescriptor(
        val url: String,
        val hasImage: Boolean,
        val imageWidth: Int?,
        val imageHeight: Int?,
        val description: String,
        val title: String
)
