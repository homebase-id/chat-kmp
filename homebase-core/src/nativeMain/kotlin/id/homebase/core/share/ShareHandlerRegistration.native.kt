package id.homebase.core.share

actual fun registerShareHandler(handler: (conversationId: String) -> Unit) {
    ShareHandlerBridge.setHandler(handler)
}

actual fun unregisterShareHandler() {
    ShareHandlerBridge.clearHandler()
}

actual fun registerMomentShareHandler(handler: () -> Unit) {
    ShareHandlerBridge.setMomentHandler(handler)
}

actual fun unregisterMomentShareHandler() {
    // Cleared together with the conversation handler in ShareHandlerBridge.clearHandler().
}
