package id.homebase.api.client.websockets

import kotlinx.serialization.Serializable

/**
 * Represents a WebSocket message sent to the server
 * (ported from TypeScript WebsocketCommand interface)
 */
@Serializable
data class WebsocketCommand(
    val command: String, // TS: WebSocketCommands
    val data: String
)