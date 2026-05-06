package id.homebase.core.permission

actual fun registerPermissionCallbackHandler(handler: (canceled: Boolean) -> Unit) {
    // No-op on Android — handled directly in MainActivity.handleIntent
}

actual fun unregisterPermissionCallbackHandler() {
    // No-op
}
