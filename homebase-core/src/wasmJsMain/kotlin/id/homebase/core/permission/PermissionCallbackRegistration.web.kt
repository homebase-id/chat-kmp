package id.homebase.core.permission

actual fun registerPermissionCallbackHandler(handler: (canceled: Boolean) -> Unit) {
    // No-op on Web
}

actual fun unregisterPermissionCallbackHandler() {
    // No-op
}
