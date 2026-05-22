package id.homebase.api.browser

import kotlinx.browser.document

actual object RedirectConfig {
    actual val scheme: String = "homebase-fchat" // unused on web

    // The browser must come back to OUR origin (the web app) so the popup's callback page can
    // postMessage the result to the opener. `clientId` (the app id) is irrelevant here — what
    // matters is that YouAuth redirects to a URL this app serves. The popup lands on
    // `<base>authorization-code-callback`, which the dev server / host serves as index.html.
    //
    // `document.baseURI` reflects the <base href> set in index.html (build-time substituted from
    // the -PpublicPath Gradle property; defaults to origin + "/"). It always ends with "/", so
    // appending a path-relative segment gives the correct absolute URL regardless of the mount.
    actual fun buildRedirectUri(clientId: String): String =
        document.baseURI + "authorization-code-callback"
}
