package id.homebase.core.upgrade

actual fun registerDataUpgradeCallbackHandler(handler: () -> Unit) {
    // No-op on Web — handled by AppViewModel.onResumed on tab refocus
}

actual fun unregisterDataUpgradeCallbackHandler() {
    // No-op
}
