package id.homebase.api.client.location

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Sender-side location preview returned by [LocationPreviewProvider]. Mirrors the shape of
 * `LinkPreview` so the chat send pipeline can treat both uniformly. The map image is carried
 * inline as a `data:image/png;base64,...` URI; `LocationPreviewPayloadBuilder` decodes it to
 * bytes for the encrypted drive payload before the message goes on the wire.
 */
@Serializable
@Immutable
data class LocationPreview(
    val lat: Double,
    val lon: Double,
    val address: String,
    val imageUrl: String?,
    val imageWidth: Int?,
    val imageHeight: Int?,
)
