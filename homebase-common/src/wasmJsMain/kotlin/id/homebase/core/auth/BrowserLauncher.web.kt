package id.homebase.core.auth

import kotlinx.coroutines.CoroutineScope

/**
 * Web implementation of BrowserLauncher.
 *
 * Web uses redirect-based OAuth, so no callback server setup is needed. Browser navigation is
 * handled by [id.homebase.core.ui.auth.rememberAuthBrowserLauncher].
 */
actual object BrowserLauncher {
    actual fun onAuthBrowserOpened(url: String, scope: CoroutineScope) {
        // Web handles navigation via window.location redirect
        // No callback server needed
    }
}
