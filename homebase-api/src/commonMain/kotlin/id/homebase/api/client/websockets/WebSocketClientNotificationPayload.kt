package id.homebase.api.client.websockets

import kotlinx.serialization.Serializable

/**
 * Represents a WebSocket (encrypted) payload received from the server
 */
@Serializable
data class WebSocketClientNotificationPayload(
    val isEncrypted: Boolean,
    val payload: String
)