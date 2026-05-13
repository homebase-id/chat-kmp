package id.homebase.core.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.browser.window

/** Web implementation using window.location redirect. */
@Composable
actual fun rememberAuthBrowserLauncher(): (url: String) -> Unit {
    return remember { { url: String -> window.location.href = url } }
}
