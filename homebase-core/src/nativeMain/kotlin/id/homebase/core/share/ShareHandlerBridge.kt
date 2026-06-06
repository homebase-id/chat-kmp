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
    private var momentHandler: (() -> Unit)? = null

    /** Called from AppViewModel to register the share handler. */
    fun setHandler(handler: (conversationId: String) -> Unit) {
        this.handler = handler
    }

    /** Called from Swift when the app opens via homebase-share:// URL scheme. */
    fun handleIncomingShare(conversationId: String) {
        Logger.i(tag = "ShareHandlerBridge") { "Incoming share for conversation: $conversationId" }
        this.handler?.invoke(conversationId)
            ?: Logger.w(tag = "ShareHandlerBridge") { "No handler registered for share intent" }
    }

    /** Called from AppViewModel to register the "New Moment" share handler. */
    fun setMomentHandler(handler: () -> Unit) {
        this.momentHandler = handler
    }

    /**
     * Called from Swift when the app opens via `homebase-share://moment`. The
     * extension has already written the shared media descriptor to the App
     * Group; the handler reads it, seeds the moments composer draft, and
     * navigates there.
     */
    fun handleIncomingMomentShare() {
        Logger.i(tag = "ShareHandlerBridge") { "Incoming share for a new moment" }
        this.momentHandler?.invoke()
            ?: Logger.w(tag = "ShareHandlerBridge") { "No handler registered for moment share intent" }
    }

    /** Clear handlers when AppViewModel is cleared. */
    fun clearHandler() {
        handler = null
        momentHandler = null
    }
}
