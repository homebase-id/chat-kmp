package id.homebase.core.util

/**
 * Opens a URL the user has to be able to come back from, keeping the app reachable.
 *
 * This is the counterpart to [id.homebase.core.ui.auth.rememberAuthBrowserLauncher], which exists
 * for the sign-in flow: it needs a browser session that can hand a token back to the app. A page
 * like sign-up hands nothing back, so it only needs a guaranteed return path — use this instead.
 *
 * - iOS: SFSafariViewController, an in-app browser with no ASWebAuthenticationSession
 *   "…Wants to Use…to Sign In" consent prompt (which is wrong for sign-up).
 * - Android: a WebView in our own activity/task. A Chrome Custom Tab's return path is the default
 *   browser's to define, and on some devices Back lands on the launcher instead (#1089).
 * - Desktop/web: the system browser / a new tab — separate windows, so the app stays reachable.
 */
expect object InAppBrowser {
    fun open(url: String)
}
