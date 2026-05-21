package id.homebase.core.upgrade

actual fun registerDataUpgradeCallbackHandler(handler: () -> Unit) {
    // No-op on Desktop — handled by LocalCallbackServer + Main.kt
}

actual fun unregisterDataUpgradeCallbackHandler() {
    // No-op
}
