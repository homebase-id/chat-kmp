package id.homebase.core.share

actual fun registerShareHandler(handler: (conversationId: String) -> Unit) {
    // No-op on Desktop
}

actual fun unregisterShareHandler() {
    // No-op
}
