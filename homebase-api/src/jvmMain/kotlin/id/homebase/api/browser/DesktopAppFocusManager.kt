package id.homebase.api.browser

import java.awt.EventQueue
import java.awt.Window

object DesktopAppFocusManager {

    private var windowProvider: (() -> Window?)? = null

    fun registerWindowProvider(provider: () -> Window?) {
        windowProvider = provider
    }

    fun requestFocus() {
        EventQueue.invokeLater {
            val window = windowProvider?.invoke() ?: return@invokeLater

            window.isVisible = true
            window.toFront()
            window.requestFocus()
            window.isAlwaysOnTop = true
            window.isAlwaysOnTop = false
        }
    }
}
