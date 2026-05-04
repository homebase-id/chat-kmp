package id.homebase.api.browser

import co.touchlab.kermit.Logger
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.Window

object DesktopAppFocusManager {

    private const val TAG = "DesktopAppFocusManager"

    private var windowProvider: (() -> Window?)? = null

    fun registerWindowProvider(provider: () -> Window?) {
        windowProvider = provider
    }

    fun requestFocus() {
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
            // The always-on-top toggle is a Windows-specific trick to nudge focus.
            // On macOS it's neutralised by the activate() call above; on Linux it's
            // a best-effort.
            window.isAlwaysOnTop = true
            window.isAlwaysOnTop = false
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
