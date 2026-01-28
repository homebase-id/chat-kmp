package id.homebase.api.browser

import kotlin.compareTo

actual object RedirectConfig {
    actual val scheme: String = "http"

    actual fun buildRedirectUri(clientId: String): String {
        // Get the current port if server is already running
        var port = LocalCallbackServer.getPort()

        // If not running, find an available port for when we start
        if (port == 0) {
            port = LocalCallbackServer.findAvailablePort()
            if (port < 0) {
                throw IllegalStateException("No available ports found for OAuth callback server")
            }
        }

        return "http://localhost:$port/authorization-code-callback"
    }
}
