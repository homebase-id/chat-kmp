package id.homebase.core.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI

/**
 * Desktop (JVM) implementation of the auth browser launcher.
 * Uses java.awt.Desktop first, then falls back to xdg-open / open,
 * and finally copies the URL to clipboard if everything fails.
 *
 * This fixes the "BROWSE action is not supported on the current platform"
 * crash that happens on many Linux setups (KDE, flatpak, WSL, etc.).
 */
@Composable
actual fun rememberAuthBrowserLauncher(): (String) -> Unit {
    return remember {
        { url -> launchBrowserSafely(url) }
    }
}

private fun launchBrowserSafely(url: String) {
    val uri = try { URI.create(url) } catch (e: Exception) {
        println("Invalid URL: $url")
        return
    }

    // 1. Try the official Desktop API (works on Windows/macOS and some Linux)
    if (Desktop.isDesktopSupported()) {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.BROWSE)) {
            try {
                desktop.browse(uri)
                return
            } catch (e: Exception) {
                // e.g. security exception, or still not really supported
            }
        }
    }

    // 2. Direct system command fallback (most reliable on Linux)
    val osName = System.getProperty("os.name").lowercase()
    val runtime = Runtime.getRuntime()

    try {
        when {
            "mac" in osName   -> runtime.exec(arrayOf("open", url))
            "nix" in osName || "nux" in osName -> runtime.exec(arrayOf("xdg-open", url))
            else -> throw UnsupportedOperationException("No known browser launcher for this OS")
        }
        return
    } catch (e: Exception) {
        // 3. Last resort: copy URL to clipboard so user can paste it manually
        copyUrlToClipboard(url)
        println("Could not open browser automatically. URL copied to clipboard:\n$url")
        // TODO: replace println with a nice Compose dialog / snackbar
        // e.g. showErrorDialog("Browser could not be opened. URL copied to clipboard.")
    }
}

private fun copyUrlToClipboard(url: String) {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    clipboard.setContents(StringSelection(url), null)
}
