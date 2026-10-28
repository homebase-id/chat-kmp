package id.homebase.core.permission

import id.homebase.api.youauth.PermissionCallback

actual fun registerPermissionCallbackHandler(handler: (PermissionCallback) -> Unit) {
    PermissionCallbackBridge.setHandler(handler)
}

actual fun unregisterPermissionCallbackHandler() {
    PermissionCallbackBridge.clearHandler()
}
