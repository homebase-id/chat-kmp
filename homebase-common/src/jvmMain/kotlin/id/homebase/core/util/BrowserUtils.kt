package id.homebase.core.util

import co.touchlab.kermit.Logger
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI

/**
 * Shared utility for launching browsers across the JVM platform.
 * Provides robust fallback handling with clipboard copy as last resort.
 */
object BrowserUtils {
    private const val TAG = "BrowserUtils"

    /**
     * Opens a URL in the system browser with comprehensive fallback handling.
     * 
     * @param url The URL to open
     * @param enableClipboardFallback Whether to copy URL to clipboard as last resort
     * @throws Exception If all launch methods fail and clipboard fallback is disabled
     */
    fun openSystemBrowser(url: String, enableClipboardFallback: Boolean = false) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(URI(url))
                return
            } catch (e: Exception) {
                Logger.w(throwable = e, tag = TAG) { "Desktop.browse() failed, trying fallback" }
            }
        } else {
            Logger.i(tag = TAG) { "Java AWT Desktop not supported, trying fallback browser launch" }
        }

        try {
            val os = System.getProperty("os.name").lowercase()
            val cmd = when {
                os.contains("win") -> arrayOf("rundll32", "url.dll,FileProtocolHandler", url)
                os.contains("mac") -> arrayOf("open", url)
                os.contains("nix") || os.contains("nux") -> arrayOf("xdg-open", url)
                else -> throw UnsupportedOperationException("Unsupported OS for browser fallback: $os")
            }

            Logger.d(tag = TAG) { "Attempting fallback browser launch with: ${cmd.joinToString(" ")}" }
            Runtime.getRuntime().exec(cmd)
            Logger.i(tag = TAG) { "Fallback browser launch initiated successfully" }
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Fallback browser launch failed: ${e.message}" }

            if (enableClipboardFallback) {
                copyUrlToClipboard(url)
                Logger.i(tag = TAG) { "URL copied to clipboard as fallback: $url" }
            } else {
                throw e
            }
        }
    }

    /**
     * Copies a URL to the system clipboard.
     */
    private fun copyUrlToClipboard(url: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(url), null)
    }
}