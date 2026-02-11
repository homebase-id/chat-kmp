package id.homebase.core.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import id.homebase.core.util.BrowserUtils

/**
 * Desktop (JVM) implementation of the auth browser launcher.
 * Uses shared BrowserUtils for robust browser launching with clipboard fallback.
 *
 * This fixes the "BROWSE action is not supported on the current platform"
 * crash that happens on many Linux setups (KDE, flatpak, WSL, etc.).
 */
@Composable
actual fun rememberAuthBrowserLauncher(): (String) -> Unit {
    return remember {
        { url -> 
            BrowserUtils.openSystemBrowser(url, enableClipboardFallback = true)
        }
    }
}
