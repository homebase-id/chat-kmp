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

    /**
     * Append a breadcrumb message to the Crashlytics on-device log. Attached to
     * the next crash report so the dashboard stack carries the recent log trail.
     */
    fun log(message: String)

    /**
     * Record an exception so it surfaces in the Crashlytics dashboard with a
     * readable stack. Used both for fatal Kotlin/Native & Objective-C crashes
     * (which the SDK's signal handler would otherwise capture only as an opaque
     * native backtrace) and for non-fatal handled exceptions.
     *
     * @param name exception type/name (e.g. the Kotlin class or `NSException.name`)
     * @param reason exception message/reason
     * @param stackTrace symbolic stack frames, outermost-first
     */
    fun recordException(name: String, reason: String, stackTrace: List<String>)
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
