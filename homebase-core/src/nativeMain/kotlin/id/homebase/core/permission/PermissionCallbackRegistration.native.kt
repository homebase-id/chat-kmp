package id.homebase.core.permission

actual fun registerPermissionCallbackHandler(handler: (canceled: Boolean) -> Unit) {
    PermissionCallbackBridge.setHandler(handler)
}

actual fun unregisterPermissionCallbackHandler() {
    PermissionCallbackBridge.clearHandler()
}
