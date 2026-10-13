package id.homebase.api.browser

import co.touchlab.kermit.Logger
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Window

object DesktopAppFocusManager {

    private const val TAG = "DesktopAppFocusManager"

    private val isMacOs: Boolean =
        System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)

    const val MAIN_WINDOW = WindowKeys.MAIN

    // A raise for a window that has not composed yet is held this long and replayed on registration.
    private const val PENDING_RAISE_TTL_MS = 5_000L

    private val windowProviders = java.util.concurrent.ConcurrentHashMap<String, () -> Window?>()
    private val pendingRaises = java.util.concurrent.ConcurrentHashMap<String, PendingRaise>()

    private class PendingRaise(val reason: String, val atMs: Long)

    fun registerWindowProvider(provider: () -> Window?) = registerWindowProvider(MAIN_WINDOW, provider)

    fun registerWindowProvider(key: String, provider: () -> Window?) {
        windowProviders[key] = provider
        val pending = pendingRaises.remove(key) ?: return
        if (System.currentTimeMillis() - pending.atMs <= PENDING_RAISE_TTL_MS) requestFocus(pending.reason, key)
    }

    fun windowFor(key: String): Window? = windowProviders[key]?.invoke()

    fun unregisterWindowProvider(key: String) {
        windowProviders.remove(key)
    }

    // Only legitimate in response to an explicit user action; [reason] names that action
    // so an unexpected restore is attributable from homebase.log alone.
    fun requestFocus(reason: String, key: String = MAIN_WINDOW) {
        Logger.i(tag = TAG) { "requestFocus(reason=$reason, key=$key)" }
        EventQueue.invokeLater {
            // A non-main window that is still composing must not fall through to the app-wide
            // activation below, which would raise the main window instead.
            if (key != MAIN_WINDOW && windowProviders[key] == null) {
                pendingRaises[key] = PendingRaise(reason, System.currentTimeMillis())
                return@invokeLater
            }
            // macOS's Cocoa window server ignores Window.toFront() / isAlwaysOnTop
            // toggling from a background app — only the *application* can activate
            // itself via NSApplication.activate(). Desktop.requestForeground(true)
            // is the cross-platform Java API that maps to that on macOS.
            // Linux WMs are mixed: most ignore it, requestFocus() below covers them.
            // Windows already respects toFront(), so this is a redundant no-op
            // but harmless.
            requestApplicationForeground()

            val window = windowProviders[key]?.invoke() ?: return@invokeLater

            if (key != MAIN_WINDOW && window is Frame) {
                window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
            }
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

    // Shows an already-composed window without activating the app, so a ring never takes the keyboard from whatever the user is typing in.
    fun revealWithoutFocus(key: String) {
        EventQueue.invokeLater {
            val window = windowProviders[key]?.invoke() ?: return@invokeLater
            if (window is Frame) window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
            window.isVisible = true
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
