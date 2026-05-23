@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package id.homebase.core.auth

import kotlinx.browser.window
import org.w3c.dom.MessageEvent
import org.w3c.dom.events.Event

/**
 * Web implementation of BrowserLauncher.
 *
 * YouAuth runs in a popup window opened from the login click gesture (see
 * `prepareWebAuthPopup` / `rememberAuthBrowserLauncher`). The popup navigates to the owner
 * console, then lands back on our own `/authorization-code-callback` page, which posts the
 * full callback URL to this (the opener) window and closes itself — keeping the in-memory
 * `callbackRegistry` in `YouAuthFlowManager` alive so the token exchange can finish here.
 *
 * `onAuthBrowserOpened` registers a one-shot, same-origin `message` listener that forwards
 * that callback URL to [onCallbackUrl].
 */
actual object BrowserLauncher {
    private var listener: ((Event) -> Unit)? = null

    actual fun onAuthBrowserOpened(url: String, onCallbackUrl: (String) -> Unit) {
        // Drop any stale listener from a previous (cancelled) attempt.
        removeListener()

        val handler: (Event) -> Unit = handler@{ event ->
            val msg = event as? MessageEvent ?: return@handler
            // Only trust messages from our own origin (the popup's callback page).
            if (msg.origin != window.location.origin) return@handler
            val callbackUrl = extractCallbackUrl(msg.data) ?: return@handler
            // One-shot — tear down before delivering.
            removeListener()
            onCallbackUrl(callbackUrl)
        }
        listener = handler
        window.addEventListener("message", handler)
    }

    private fun removeListener() {
        listener?.let { window.removeEventListener("message", it) }
        listener = null
    }
}

/** Returns the `url` field iff `data` is a `{ type: "youauth-callback", url: string }` message. */
private fun extractCallbackUrl(data: JsAny?): String? =
    js("(data && data.type === 'youauth-callback' && typeof data.url === 'string') ? data.url : null")
