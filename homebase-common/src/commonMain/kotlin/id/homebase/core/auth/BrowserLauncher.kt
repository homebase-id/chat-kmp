package id.homebase.core.auth

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
     * @param onCallbackUrl Callback for url handling. On iOS, desktop and web it can come on any thread and after the
     *   screen that opened the browser is gone, so it is finished in an app-lifetime scope; Android returns through
     *   MainActivity's deep link instead and never calls it.
     */
    fun onAuthBrowserOpened(url: String, onCallbackUrl: (String) -> Unit)
}
