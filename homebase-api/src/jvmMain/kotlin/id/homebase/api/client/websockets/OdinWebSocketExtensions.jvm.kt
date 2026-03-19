package id.homebase.api.client.websockets

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.websocket.WebSocketDeflateExtension
import io.ktor.client.plugins.websocket.WebSockets

actual fun HttpClientConfig<*>.installWebSockets() {
    install(WebSockets) {
        extensions {
            install(WebSocketDeflateExtension)
        }
    }
}
