package id.homebase.core.util

import kotlinx.browser.window

actual object InAppBrowser {
    // A plain new tab, not the sign-in popup: the SPA stays loaded in this tab, so the user gets
    // back by switching tabs. Nothing is posted back from the page.
    actual fun open(url: String) {
        window.open(url, "_blank")
    }
}
