package id.homebase.core.share

actual fun registerShareHandler(handler: (conversationId: String) -> Unit) {
    // No-op on Web
}

actual fun unregisterShareHandler() {
    // No-op
}
