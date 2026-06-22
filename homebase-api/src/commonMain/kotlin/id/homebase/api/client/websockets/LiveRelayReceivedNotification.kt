package id.homebase.api.client.websockets

import id.homebase.api.common.OdinId
import kotlinx.serialization.Serializable

/**
 * HOP 3 payload: the `data` field of a [ClientNotificationType.liveRelay] websocket notification.
 *
 * [senderOdinId] is authoritative (the server knows it from the peer cert). [receivedAt] is the
 * server-received time in ms — use it for staleness. [blob] is the opaque base64 payload (a
 * base64-encoded [id.homebase.api.client.liverelay.LiveLocationPoint] for the live-location case).
 */
@Serializable
data class LiveRelayReceivedNotification(
    val senderOdinId: OdinId,
    val channelKey: String,
    val blob: String,
    val receivedAt: Long,
)
