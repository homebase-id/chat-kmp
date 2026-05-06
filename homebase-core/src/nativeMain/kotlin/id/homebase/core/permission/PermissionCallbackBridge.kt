package id.homebase.core.permission

import co.touchlab.kermit.Logger

/**
 * Bridge between Swift (iOSApp.swift onOpenURL) and KMP (AppViewModel).
 * Swift calls [handlePermissionCallback] when it receives a
 * `homebase-fchat://permission-callback?status=...` URL. AppViewModel registers
 * itself via [setHandler] during init. Mirrors [id.homebase.core.share.ShareHandlerBridge].
 */
object PermissionCallbackBridge {
    private var handler: ((canceled: Boolean) -> Unit)? = null

    /** Called from AppViewModel to register the permission-callback handler. */
    fun setHandler(handler: (canceled: Boolean) -> Unit) {
        this.handler = handler
    }

    /**
     * Called from Swift when the app opens via the
     * `homebase-fchat://permission-callback` URL scheme. The `canceled` flag
     * reflects the `status=canceled` query parameter.
     */
    fun handlePermissionCallback(canceled: Boolean) {
        Logger.i(tag = "PermissionCallbackBridge") {
            "Permission-extend deep link (canceled=$canceled)"
        }
        this.handler?.invoke(canceled)
            ?: Logger.w(tag = "PermissionCallbackBridge") {
                "No handler registered for permission callback"
            }
    }

    /** Clear handler when AppViewModel is cleared. */
    fun clearHandler() {
        handler = null
    }
}
