package id.homebase.chat.services.builder

import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.serialization.OdinSystemSerializer
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

fun PayloadDescriptor.linkPreviews(): List<LinkPreviewDescriptor>? =
    appContent()?.let { runCatching { OdinSystemSerializer.deserialize<List<LinkPreviewDescriptor>>(it) }.getOrNull() }

fun PayloadDescriptor.locationPreviews(): List<LocationPreviewDescriptor>? =
    appContent()?.let { runCatching { OdinSystemSerializer.deserialize<List<LocationPreviewDescriptor>>(it) }.getOrNull() }
