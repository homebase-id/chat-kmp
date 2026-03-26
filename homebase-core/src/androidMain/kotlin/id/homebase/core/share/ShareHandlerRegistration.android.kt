package id.homebase.core.share

actual fun registerShareHandler(handler: (conversationId: String) -> Unit) {
    // No-op on Android — sharing is handled in-process by ShareReceiverActivity
}

actual fun unregisterShareHandler() {
    // No-op
}
