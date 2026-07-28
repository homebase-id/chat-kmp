package id.homebase.api.youauth

/** Secure storage keys for YouAuth authentication data. */
object YouAuthStorageKeys {
    /** The authenticated user's identity (e.g., "user.homebase.id") */
    const val IDENTITY = "youauth_identity"

    /** Base64-encoded client authentication token */
    const val CLIENT_AUTH_TOKEN = "youauth_client_auth_token"

    /** Base64-encoded shared secret for request encryption */
    const val SHARED_SECRET = "youauth_shared_secret"

    /** The last used username */
    const val USERNAME = "youauth_username"

    /**
     * Serialized in-flight auth flow state (ECC key pair + password + CSRF state) for the
     * web redirect flow, where a full-page navigation to the authorize endpoint unloads the
     * app and the in-memory callback registry with it. See [YouAuthFlowManager.authorize]
     * with `persistForRedirect = true`.
     */
    const val PENDING_REDIRECT_FLOW = "youauth_pending_redirect_flow"
}
