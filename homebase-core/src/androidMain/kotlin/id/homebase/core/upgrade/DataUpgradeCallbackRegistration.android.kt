package id.homebase.core.upgrade

actual fun registerDataUpgradeCallbackHandler(handler: () -> Unit) {
    // No-op on Android — handled directly in MainActivity.handleIntent
}

actual fun unregisterDataUpgradeCallbackHandler() {
    // No-op
}
