package id.homebase.api.browser

actual object RedirectConfig {
    actual val scheme: String = "youauth"

    actual fun buildRedirectUri(clientId: String): String {
        return "https://$clientId/authorization-code-callback"
    }
}
