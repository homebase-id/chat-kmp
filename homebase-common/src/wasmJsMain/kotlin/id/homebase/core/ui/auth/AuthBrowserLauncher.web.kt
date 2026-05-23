package id.homebase.core.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.browser.window

/**
 * Web implementation: navigate the popup that [prepareWebAuthPopup] opened on the click gesture to
 * the YouAuth authorize URL. Using the pre-opened popup (rather than opening here) is what keeps
 * the browser from blocking it — by the time this runs, the original user gesture is gone. If the
 * popup is missing (prepare wasn't called, or it was blocked), fall back to opening one now.
 */
@Composable
actual fun rememberAuthBrowserLauncher(): (url: String) -> Unit {
    return remember {
        { url: String ->
            val popup = authPopup ?: window.open(url, "youauth", "width=480,height=720")
            authPopup = popup
            popup?.location?.href = url
        }
    }
}
