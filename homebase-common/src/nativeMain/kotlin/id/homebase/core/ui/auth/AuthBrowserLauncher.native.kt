package id.homebase.core.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * iOS implementation - returns no-op since ASWebAuthenticationSession handles both launching and
 * callback in BrowserLauncher.onAuthBrowserOpened().
 *
 * The browser is launched from BrowserLauncher.native.kt due to iOS API constraints
 * (ASWebAuthenticationSession couples launching with callback handling).
 */
@Composable
actual fun rememberAuthBrowserLauncher(): (url: String) -> Boolean {
    return remember {
        { _: String ->
            // No-op: iOS uses ASWebAuthenticationSession which is launched
            // from BrowserLauncher.onAuthBrowserOpened() because the API
            // couples browser launching with callback handling
            true
        }
    }
}
