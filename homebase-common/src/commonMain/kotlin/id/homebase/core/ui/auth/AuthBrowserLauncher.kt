package id.homebase.core.ui.auth

import androidx.compose.runtime.Composable

/**
 * Composable that remembers a platform-specific auth browser launcher. Returns a function that
 * launches the browser for OAuth flows.
 *
 * Platform implementations:
 * - Android: Chrome Custom Tabs via LocalContext
 * - iOS: No-op (handled by BrowserLauncher.onAuthBrowserOpened)
 * - Desktop: System browser via Desktop.browse()
 * - Web: window.location redirect
 *
 * Usage:
 * ```
 * val launchAuthBrowser = rememberAuthBrowserLauncher()
 * launchAuthBrowser(authUrl)
 * ```
 */
@Composable expect fun rememberAuthBrowserLauncher(): (url: String) -> Unit
