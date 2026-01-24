package id.homebase.api.browser

actual object RedirectConfig {
    actual val scheme: String = "youauth"

    actual fun buildRedirectUri(clientId: String): String {
        return "youauth://$clientId/authorization-code-callback"
    }
}
