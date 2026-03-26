package id.homebase.core.share

actual fun registerShareHandler(handler: (conversationId: String) -> Unit) {
    ShareHandlerBridge.setHandler(handler)
}

actual fun unregisterShareHandler() {
    ShareHandlerBridge.clearHandler()
}
