package id.homebase.api.browser

actual object RedirectConfig {
    actual val scheme: String = "homebase-fchat"

    actual fun buildRedirectUri(clientId: String): String {
        return "homebase-fchat://$clientId/authorization-code-callback"
    }
}
