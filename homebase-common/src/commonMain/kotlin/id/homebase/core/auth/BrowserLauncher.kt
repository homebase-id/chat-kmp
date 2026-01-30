package id.homebase.core.auth

import kotlinx.coroutines.CoroutineScope

/**
 * Platform-specific browser launching for OAuth/authentication flows.
 *
 * Platform implementations:
 * - Android: Chrome Custom Tabs
 * - iOS: ASWebAuthenticationSession
 * - Desktop: System browser with local callback server
 */
expect object BrowserLauncher {
    /**
     * Launch browser for OAuth/authentication flow.
     *
     * @param url The authorization URL to open
     * @param scope CoroutineScope for async callback handling (iOS/Desktop need this)
     */
    fun launchAuthBrowser(url: String, scope: CoroutineScope, onOpenUrl: (String) -> Unit)
}

