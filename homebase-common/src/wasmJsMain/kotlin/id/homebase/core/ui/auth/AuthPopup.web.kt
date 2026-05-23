package id.homebase.core.ui.auth

import kotlinx.browser.window
import org.w3c.dom.Window

/** The auth popup window opened on the login click gesture; navigated later by the launcher. */
internal var authPopup: Window? = null

private const val POPUP_FEATURES = "width=480,height=720,menubar=no,toolbar=no,location=yes"

actual fun prepareWebAuthPopup() {
    // Opened synchronously in the click handler so popup blockers allow it.
    authPopup = window.open("about:blank", "youauth", POPUP_FEATURES)
}

actual fun closeWebAuthPopup() {
    authPopup?.let { if (!it.closed) it.close() }
    authPopup = null
}
