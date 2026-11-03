package id.homebase.core.permission

import id.homebase.api.youauth.PermissionCallback
import id.homebase.api.browser.guardJsCallback
import kotlinx.browser.window
import org.w3c.dom.MessageEvent
import org.w3c.dom.events.Event

private var listener: ((Event) -> Unit)? = null

actual fun registerPermissionCallbackHandler(handler: (PermissionCallback) -> Unit) {
    unregisterPermissionCallbackHandler()
    val onMessage: (Event) -> Unit = { event ->
        guardJsCallback("permission.message") {
            val msg = event as? MessageEvent ?: return@guardJsCallback
            if (msg.origin != window.location.origin) return@guardJsCallback
            val url = extractPermissionCallbackUrl(msg.data) ?: return@guardJsCallback
            handler(PermissionCallback.parse(url))
        }
    }
    listener = onMessage
    window.addEventListener("message", onMessage)
}

actual fun unregisterPermissionCallbackHandler() {
    listener?.let { window.removeEventListener("message", it) }
    listener = null
}

private fun extractPermissionCallbackUrl(data: JsAny?): String? =
    js("(data && data.type === 'permission-callback' && typeof data.url === 'string') ? data.url : null")
