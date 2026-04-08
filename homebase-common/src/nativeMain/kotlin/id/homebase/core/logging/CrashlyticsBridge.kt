package id.homebase.core.logging

/**
 * Bridge interface for Firebase Crashlytics operations. Implemented by Swift in the iOS app
 * and injected at startup.
 */
interface CrashlyticsBridge {
    /**
     * Enable or disable Crashlytics collection.
     * @param enabled true to enable, false to disable
     */
    fun setCrashlyticsCollectionEnabled(enabled: Boolean)
}

/**
 * Holder for the CrashlyticsBridge implementation. Must be set from Swift before any
 * error collection toggling is performed.
 */
object CrashlyticsBridgeHolder {
    private var _bridge: CrashlyticsBridge? = null

    fun setBridge(bridge: CrashlyticsBridge) {
        _bridge = bridge
    }

    fun getBridge(): CrashlyticsBridge? {
        return _bridge
    }
}
