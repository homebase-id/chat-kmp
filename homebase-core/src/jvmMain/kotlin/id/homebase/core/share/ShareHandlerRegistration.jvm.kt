package id.homebase.core.share

actual fun registerShareHandler(handler: (conversationId: String) -> Unit) {
    // No-op on Desktop
}

actual fun unregisterShareHandler() {
    // No-op
}

actual fun registerMomentShareHandler(handler: () -> Unit) {
    // No-op on Desktop
}

actual fun unregisterMomentShareHandler() {
    // No-op
}
