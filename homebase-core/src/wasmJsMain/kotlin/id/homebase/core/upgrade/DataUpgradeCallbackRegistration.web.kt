package id.homebase.core.upgrade

actual fun registerDataUpgradeCallbackHandler(handler: () -> Unit) {
    // No-op on Web
}

actual fun unregisterDataUpgradeCallbackHandler() {
    // No-op
}
