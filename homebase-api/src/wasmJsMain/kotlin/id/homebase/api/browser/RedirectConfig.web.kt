package id.homebase.api.browser

import kotlinx.browser.window

actual object RedirectConfig {
    actual val scheme: String = "homebase-fchat" // unused on web

    // The browser must come back to OUR origin (the web app) so the popup's callback page can
    // postMessage the result to the opener. `clientId` (the app id) is irrelevant here — what
    // matters is that YouAuth redirects to a URL this app serves. The popup lands on
    // `/authorization-code-callback`, which the dev server / host serves as index.html.
    actual fun buildRedirectUri(clientId: String): String =
        window.location.origin + "/authorization-code-callback"
}
