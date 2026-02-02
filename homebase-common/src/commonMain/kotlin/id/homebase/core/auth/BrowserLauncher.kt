package id.homebase.core.auth

import kotlinx.coroutines.CoroutineScope

/**
 * Platform-specific auth flow orchestration for OAuth/authentication flows.
 *
 * This handles callback setup, NOT browser launching. Browser launching is delegated to the UI
 * layer via [id.homebase.core.ui.auth.rememberAuthBrowserLauncher].
 *
 * Platform implementations:
 * - Android: No-op (deep link handles callback)
 * - iOS: ASWebAuthenticationSession (still launches browser here due to API constraints)
 * - Desktop: Local callback server setup
 * - Web: No-op (redirect handles callback)
 */
expect object BrowserLauncher {
    /**
     * Called when auth browser is about to be opened. Platform implementations may need to set up
     * callback handlers.
     *
     * @param url The authorization URL being opened
     * @param scope CoroutineScope for async callback handling
     */
    fun onAuthBrowserOpened(url: String, scope: CoroutineScope)
}
