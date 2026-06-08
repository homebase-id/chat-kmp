package id.homebase.core.ui.auth

import androidx.compose.runtime.Composable

/**
 * Composable that remembers a platform-specific auth browser launcher. Returns a function that
 * launches the browser for OAuth flows and reports whether the launch succeeded.
 *
 * The function returns `true` if the auth browser/popup was opened (or dispatched), and `false`
 * only on web when the browser blocked the popup — i.e. when `window.open` returns `null` because
 * it was called outside a fresh user gesture (e.g. after the async identity ping). Callers use the
 * `false` result to surface a "Continue" affordance that re-opens the popup from a real click.
 * Non-web platforms always return `true`.
 *
 * Platform implementations:
 * - Android: Chrome Custom Tabs via LocalContext
 * - iOS: No-op (handled by BrowserLauncher.onAuthBrowserOpened)
 * - Desktop: System browser via Desktop.browse()
 * - Web: window.open popup (kept on web so the SPA/Wasm context isn't navigated away)
 *
 * Usage:
 * ```
 * val launchAuthBrowser = rememberAuthBrowserLauncher()
 * if (launchAuthBrowser(authUrl)) { /* opened */ } else { /* blocked — show Continue */ }
 * ```
 */
@Composable expect fun rememberAuthBrowserLauncher(): (url: String) -> Boolean
