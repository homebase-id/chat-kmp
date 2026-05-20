package id.homebase.core.upgrade

actual fun registerDataUpgradeCallbackHandler(handler: () -> Unit) {
    DataUpgradeCallbackBridge.setHandler(handler)
}

actual fun unregisterDataUpgradeCallbackHandler() {
    DataUpgradeCallbackBridge.clearHandler()
}
