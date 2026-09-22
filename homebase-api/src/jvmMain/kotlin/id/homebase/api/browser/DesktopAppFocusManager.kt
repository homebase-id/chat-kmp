package id.homebase.api.browser

import co.touchlab.kermit.Logger
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.Window

object DesktopAppFocusManager {

    private const val TAG = "DesktopAppFocusManager"

    private val isMacOs: Boolean =
        System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)

    private var windowProvider: (() -> Window?)? = null

    fun registerWindowProvider(provider: () -> Window?) {
        windowProvider = provider
    }

    // Only legitimate in response to an explicit user action; [reason] names that action
    // so an unexpected restore is attributable from homebase.log alone.
    fun requestFocus(reason: String) {
        Logger.i(tag = TAG) { "requestFocus(reason=$reason)" }
        EventQueue.invokeLater {
            // macOS's Cocoa window server ignores Window.toFront() / isAlwaysOnTop
            // toggling from a background app — only the *application* can activate
            // itself via NSApplication.activate(). Desktop.requestForeground(true)
            // is the cross-platform Java API that maps to that on macOS.
            // Linux WMs are mixed: most ignore it, requestFocus() below covers them.
            // Windows already respects toFront(), so this is a redundant no-op
            // but harmless.
            requestApplicationForeground()

            val window = windowProvider?.invoke() ?: return@invokeLater

            window.isVisible = true
            window.toFront()
            window.requestFocus()
            // Windows' foreground lock ignores toFront() from a background process and
            // the always-on-top flicker is the usual workaround; on macOS it measurably
            // does nothing (activate() above already restored and raised the window), so
            // skip forcing the window over every other app's there.
            if (!isMacOs) {
                window.isAlwaysOnTop = true
                window.isAlwaysOnTop = false
            }
        }
    }

    private fun requestApplicationForeground() {
        try {
            if (!Desktop.isDesktopSupported()) return
            val desktop = Desktop.getDesktop()
            val action = Desktop.Action.APP_REQUEST_FOREGROUND
            if (desktop.isSupported(action)) {
                desktop.requestForeground(true)
            }
        } catch (e: Throwable) {
            Logger.w(throwable = e, tag = TAG) {
                "requestForeground failed: ${e.message}"
            }
        }
    }
}
