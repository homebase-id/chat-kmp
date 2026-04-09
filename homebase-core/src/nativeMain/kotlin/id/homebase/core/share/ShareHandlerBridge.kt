package id.homebase.core.share

import co.touchlab.kermit.Logger
import id.homebase.core.share.ShareHandlerBridge.handleIncomingShare
import id.homebase.core.share.ShareHandlerBridge.setHandler

/**
 * Bridge between Swift (iOSApp.swift onOpenURL) and KMP (AppViewModel).
 * Swift calls [handleIncomingShare] when it receives a `homebase-share://` URL.
 * AppViewModel registers itself via [setHandler] during init.
 */
object ShareHandlerBridge {
    private var handler: ((String) -> Unit)? = null

    /** Called from AppViewModel to register the share handler. */
    fun setHandler(handler: (conversationId: String) -> Unit) {
        this.handler = handler
    }

    /** Called from Swift when the app opens via homebase-share:// URL scheme. */
    fun handleIncomingShare(conversationId: String) {
        Logger.i(tag = "ShareHandlerBridge") { "Incoming share for conversation: $conversationId" }
        handler?.invoke(conversationId)
            ?: Logger.w("ShareHandlerBridge") { "No handler registered for share intent" }
    }

    /** Clear handler when AppViewModel is cleared. */
    fun clearHandler() {
        handler = null
    }
}
