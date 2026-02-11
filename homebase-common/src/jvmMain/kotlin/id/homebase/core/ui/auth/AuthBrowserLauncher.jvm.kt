package id.homebase.core.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import co.touchlab.kermit.Logger
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
    try {
        openSystemBrowser(url)
    } catch (e: Exception) {
        // Last resort: copy URL to clipboard so user can paste it manually
        copyUrlToClipboard(url)
        println("Could not open browser automatically. URL copied to clipboard:\n$url")
        // TODO: replace println with a nice Compose dialog / snackbar
        // e.g. showErrorDialog("Browser could not be opened. URL copied to clipboard.")
    }
}

private fun openSystemBrowser(url: String) {
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
    ) {
        Desktop.getDesktop().browse(URI(url))
    } else {
        Logger.i("AuthBrowserLauncher") { "Java AWT Desktop not supported, trying fallback browser launch" }
        try {
            val os = System.getProperty("os.name").lowercase()
            val cmd =
                when {
                    os.contains("win") ->
                        arrayOf("rundll32", "url.dll,FileProtocolHandler", url)
                    os.contains("mac") -> arrayOf("open", url)
                    os.contains("nix") || os.contains("nux") -> arrayOf("xdg-open", url)
                    else ->
                        throw UnsupportedOperationException(
                            "Unsupported OS for browser fallback: $os"
                        )
                }

            Logger.d("AuthBrowserLauncher") {
                "Attempting fallback browser launch with: ${cmd.joinToString(" ")}"
            }
            Runtime.getRuntime().exec(cmd)
            Logger.i("AuthBrowserLauncher") { "Fallback browser launch initiated successfully" }
        } catch (e: Exception) {
            Logger.e("AuthBrowserLauncher", e) { "Fallback browser launch failed: ${e.message}" }
            throw e
        }
    }
}

private fun copyUrlToClipboard(url: String) {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    clipboard.setContents(StringSelection(url), null)
}
