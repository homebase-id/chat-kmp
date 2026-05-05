package id.homebase.core.permission

actual fun registerPermissionCallbackHandler(handler: (canceled: Boolean) -> Unit) {
    // No-op on Desktop — handled by LocalCallbackServer + Main.kt
}

actual fun unregisterPermissionCallbackHandler() {
    // No-op
}
