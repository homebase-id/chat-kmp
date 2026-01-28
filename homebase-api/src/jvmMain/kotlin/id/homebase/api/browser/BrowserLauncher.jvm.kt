package id.homebase.api.browser


import co.touchlab.kermit.Logger
import java.awt.Desktop
import java.net.URI
import kotlinx.coroutines.CoroutineScope

/** Desktop implementation of BrowserLauncher using system browser and LocalCallbackServer. */
actual object BrowserLauncher {
    private const val TAG = "BrowserLauncher.desktop"

    actual fun launchAuthBrowser(url: String, scope: CoroutineScope) {
        try {
            // Start the local callback server before opening the browser
            if (!LocalCallbackServer.isRunning()) {
                // Extract the port from the redirect URI in the URL to ensure consistency
                val portMatch = Regex("localhost%3A(\\d+)").find(url)
                val preferredPort = portMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

                val actualPort = LocalCallbackServer.start(scope, preferredPort)
                if (actualPort < 0) {
                    Logger.e(TAG) {
                        "Failed to start OAuth callback server. No available ports found."
                    }
                    return
                }

                if (preferredPort > 0 && actualPort != preferredPort) {
                    Logger.w(TAG) {
                        "Server started on port $actualPort instead of preferred $preferredPort"
                    }
                }
            }

            // Open the authorization URL in the system browser
            openSystemBrowser(url)
        } catch (e: Exception) {
            Logger.e(TAG, e) { "Failed to launch browser: ${e.message}" }
        }
    }

    actual fun openUrl(url: String) {
        try {
            openSystemBrowser(url)
        } catch (e: Exception) {
            Logger.e(TAG, e) { "Failed to open URL: ${e.message}" }
        }
    }

    private fun openSystemBrowser(url: String) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
        ) {
            Desktop.getDesktop().browse(URI(url))
        } else {
            Logger.i(TAG) { "Java AWT Desktop not supported, trying fallback browser launch" }
            try {
                val os = System.getProperty("os.name").lowercase()
                val cmd =
                    when {
                        os.contains("win") ->
                            arrayOf("rundll32", "url.dll,FileProtocolHandler", url)
                        os.contains("mac") -> arrayOf("open", url)
                        os.contains("nix") || os.contains("nux") -> arrayOf("xdg-open", url)
                        else ->
                            throw UnsupportedOperationException(
                                "Unsupported OS for browser fallback: $os"
                            )
                    }

                Logger.d(TAG) {
                    "Attempting fallback browser launch with: ${cmd.joinToString(" ")}"
                }
                Runtime.getRuntime().exec(cmd)
                Logger.i(TAG) { "Fallback browser launch initiated successfully" }
            } catch (e: Exception) {
                Logger.e(TAG, e) { "Fallback browser launch failed: ${e.message}" }
                throw e
            }
        }
    }
}
