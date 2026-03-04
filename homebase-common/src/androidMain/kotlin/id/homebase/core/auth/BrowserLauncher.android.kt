package id.homebase.core.auth

/**
 * Android implementation of BrowserLauncher.
 *
 * Android uses deep links to handle OAuth callbacks, so no callback server is needed. The actual
 * Custom Tabs launching is handled by [id.homebase.core.ui.auth.rememberAuthBrowserLauncher].
 */
actual object BrowserLauncher {
    actual fun onAuthBrowserOpened(url: String, onCallbackUrl: (String) -> Unit) {
        // Android uses deep link callback - no server setup needed
        // Custom Tabs launching is handled by AuthBrowserLauncher composable
    }
}
