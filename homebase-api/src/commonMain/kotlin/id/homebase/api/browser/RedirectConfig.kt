package id.homebase.api.browser

/**
 * Platform-specific redirect URI configuration for OAuth flows.
 *
 * Platform implementations:
 * - Android/iOS: Custom URL scheme (youauth://)
 * - Desktop: Localhost HTTP server (http://localhost:PORT)
 */
expect object RedirectConfig {
    /**
     * The URL scheme for auth redirects.
     * - Mobile: "youauth"
     * - Desktop: "http"
     */
    val scheme: String

    /**
     * Build the full redirect URI for the given client/app ID.
     *
     * @param clientId The OAuth client/app identifier
     * @return Full redirect URI (e.g., "youauth://clientId/authorization-code-callback")
     */
    fun buildRedirectUri(clientId: String): String
}
