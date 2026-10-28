package id.homebase.core.permission

import id.homebase.api.youauth.PermissionCallback

actual fun registerPermissionCallbackHandler(handler: (PermissionCallback) -> Unit) {
    // No-op on Desktop — handled by LocalCallbackServer + Main.kt
}

actual fun unregisterPermissionCallbackHandler() {
    // No-op
}
