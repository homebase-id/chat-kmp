package id.homebase.core.upgrade

expect fun registerDataUpgradeCallbackHandler(handler: () -> Unit)

expect fun unregisterDataUpgradeCallbackHandler()
