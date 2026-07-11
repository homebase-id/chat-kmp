package id.homebase.core.util

/**
 * Opens a URL in an in-app browser, keeping the user inside the app.
 *
 * iOS spawns the system SFSafariViewController — a real in-app browser with no
 * ASWebAuthenticationSession "…Wants to Use…to Sign In" consent prompt (wrong for a sign-up
 * flow). Other targets already open URLs in-app via [id.homebase.core.ui.auth.rememberAuthBrowserLauncher]
 * in the UI layer, so only iOS needs this and their actual is a no-op.
 */
expect object InAppBrowser {
    fun open(url: String)
}
