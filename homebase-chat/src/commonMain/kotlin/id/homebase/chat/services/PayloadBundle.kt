package id.homebase.chat.services

import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.EmbeddedThumb

// Aggregated output consumed by MessageSenderService
data class PayloadBundle(
    val payloads: List<PayloadFile>,
    val thumbnails: List<ThumbnailFile>,
    val previewThumbs: List<EmbeddedThumb>
)