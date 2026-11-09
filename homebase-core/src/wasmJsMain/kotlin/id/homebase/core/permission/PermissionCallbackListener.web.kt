package id.homebase.core.permission

import id.homebase.api.browser.guardJsCallback
import kotlinx.browser.window
import org.w3c.dom.MessageEvent

fun installPermissionCallbackListener(onUrl: (String) -> Unit) {
    window.addEventListener("message") { event ->
        guardJsCallback("permission.message") {
            val msg = event as? MessageEvent ?: return@guardJsCallback
            if (msg.origin != window.location.origin) return@guardJsCallback
            val url = extractPermissionCallbackUrl(msg.data) ?: return@guardJsCallback
            onUrl(url)
        }
    }
}

private fun extractPermissionCallbackUrl(data: JsAny?): String? =
    js("(data && data.type === 'permission-callback' && typeof data.url === 'string') ? data.url : null")
