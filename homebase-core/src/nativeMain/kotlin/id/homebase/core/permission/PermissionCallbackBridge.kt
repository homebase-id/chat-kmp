package id.homebase.core.permission

import id.homebase.api.youauth.PermissionCallback
import co.touchlab.kermit.Logger

/**
 * Bridge between Swift (iOSApp.swift onOpenURL) and KMP (AppViewModel).
 * Swift calls [handlePermissionCallback] when it receives a
 * `homebase-fchat://permission-callback?...` URL. AppViewModel registers
 * itself via [setHandler] during init. Mirrors [id.homebase.core.share.ShareHandlerBridge].
 */
object PermissionCallbackBridge {
    private var handler: ((PermissionCallback) -> Unit)? = null

    /** Called from AppViewModel to register the permission-callback handler. */
    fun setHandler(handler: (PermissionCallback) -> Unit) {
        this.handler = handler
    }

    /**
     * Called from Swift with the `homebase-fchat://permission-callback` URL the app opened with.
     */
    fun handlePermissionCallback(url: String) {
        val callback = PermissionCallback.parse(url)
        Logger.i(tag = "PermissionCallbackBridge") { "Permission-extend deep link ($callback)" }
        this.handler?.invoke(callback)
            ?: Logger.w(tag = "PermissionCallbackBridge") {
                "No handler registered for permission callback"
            }
    }

    /** Clear handler when AppViewModel is cleared. */
    fun clearHandler() {
        handler = null
    }
}
