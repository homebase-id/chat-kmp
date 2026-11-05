package id.homebase.core.permission

import id.homebase.api.youauth.PermissionCallback

actual fun registerPermissionCallbackHandler(handler: (PermissionCallback) -> Unit) {
    // No-op on Android — handled directly in MainActivity.handleIntent
}

actual fun unregisterPermissionCallbackHandler() {
    // No-op
}
