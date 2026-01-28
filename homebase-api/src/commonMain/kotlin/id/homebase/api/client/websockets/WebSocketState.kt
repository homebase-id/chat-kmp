package id.homebase.api.client.websockets

/**
 * Represents the connection state of the WebSocket
 */
sealed class WebSocketState {
    data object Disconnected : WebSocketState()
    data object Connecting : WebSocketState()
    data object Connected : WebSocketState()

    data class Error(val message: String) : WebSocketState()
}