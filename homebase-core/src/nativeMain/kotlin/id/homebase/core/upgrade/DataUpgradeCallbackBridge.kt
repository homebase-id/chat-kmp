package id.homebase.core.upgrade

import co.touchlab.kermit.Logger

/**
 * Bridge between Swift (iOSApp.swift onOpenURL) and KMP (AppViewModel).
 * Swift calls [handleDataUpgradeCallback] when it receives a
 * `homebase-fchat://data-upgrade-callback` URL. AppViewModel registers
 * itself via [setHandler] during init.
 */
object DataUpgradeCallbackBridge {
    private var handler: (() -> Unit)? = null

    fun setHandler(handler: () -> Unit) {
        this.handler = handler
    }

    fun handleDataUpgradeCallback() {
        Logger.i(tag = "DataUpgradeCallbackBridge") {
            "Data-upgrade deep link received"
        }
        this.handler?.invoke()
            ?: Logger.w(tag = "DataUpgradeCallbackBridge") {
                "No handler registered for data-upgrade callback"
            }
    }

    fun clearHandler() {
        handler = null
    }
}
