package id.homebase.core.auth

import co.touchlab.kermit.Logger
import id.homebase.api.browser.LocalCallbackServer

/**
 * Desktop (JVM) implementation of BrowserLauncher.
 *
 * Sets up local callback server to receive OAuth redirects. The actual system browser launching is
 * handled by [id.homebase.core.ui.auth.rememberAuthBrowserLauncher].
 */
actual object BrowserLauncher {
    private const val TAG = "BrowserLauncher.desktop"

    actual fun onAuthBrowserOpened(url: String, onCallbackUrl: (String) -> Unit) {
        try {
            // Start the local callback server before browser opens
            if (!LocalCallbackServer.isRunning()) {
                // Extract the port from the redirect URI in the URL to ensure consistency
                val portMatch = Regex("localhost%3A(\\d+)").find(url)
                val preferredPort = portMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

                val actualPort = LocalCallbackServer.start(onCallbackUrl, preferredPort)
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
            } else {
                // Update callback
                LocalCallbackServer.setCallBack(onCallbackUrl)
            }
            // System browser is launched by UI layer via AuthBrowserLauncher
        } catch (e: Exception) {
            Logger.e(TAG, e) { "Failed to set up callback server: ${e.message}" }
        }
    }
}
