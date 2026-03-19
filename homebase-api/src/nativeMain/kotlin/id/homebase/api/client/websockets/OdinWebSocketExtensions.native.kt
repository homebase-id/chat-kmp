package id.homebase.api.client.websockets

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.websocket.WebSockets

actual fun HttpClientConfig<*>.installWebSockets() {
    install(WebSockets)
    // Darwin engine delegates WebSocket to NSURLSession and does not route
    // frames through Ktor's extension pipeline, so WebSocketDeflateExtension
    // is not applicable. Falls back to uncompressed — no crash risk.
}
