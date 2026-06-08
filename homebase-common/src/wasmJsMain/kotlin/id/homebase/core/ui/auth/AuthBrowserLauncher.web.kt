package id.homebase.core.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.browser.window

private const val POPUP_FEATURES = "width=480,height=720,menubar=no,toolbar=no,location=yes"

/**
 * Web implementation: open the YouAuth authorize URL in a popup window. A popup (rather than
 * navigating the top window) keeps the SPA/Wasm app alive so the token exchange can finish in the
 * opener once the popup posts the callback back (see `BrowserLauncher.web`).
 *
 * Identity validation (format + ping) runs *before* this is called, so the popup only opens for a
 * reachable identity. The trade-off: because the open now happens after an `await`, the browser may
 * block it when the user-gesture activation has lapsed — `window.open` then returns `null`. We
 * report that as `false` so the caller can show a "Continue" button that re-opens from a fresh
 * click (which always carries activation).
 */
@Composable
actual fun rememberAuthBrowserLauncher(): (url: String) -> Boolean {
    return remember {
        { url: String ->
            val popup = window.open(url, "youauth", POPUP_FEATURES)
            popup != null && !popup.closed
        }
    }
}
