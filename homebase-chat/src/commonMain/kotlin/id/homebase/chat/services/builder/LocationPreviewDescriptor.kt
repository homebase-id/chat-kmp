package id.homebase.chat.services.builder

import kotlinx.serialization.Serializable

/**
 * Wire-format metadata for a location-share payload. Stored as JSON in
 * `PayloadDescriptor.descriptorContent` for payloads keyed `PAYLOAD_KEY_LOCATION`. The map PNG
 * lives in the encrypted payload bytes; this descriptor only carries the small fields the
 * receiver needs to render the bubble (and the `geo:` deep-link) without first downloading the
 * PNG.
 */
@Serializable
data class LocationPreviewDescriptor(
    val lat: Double,
    val lon: Double,
    val address: String,
    val hasImage: Boolean,
    val imageWidth: Int?,
    val imageHeight: Int?,
)
