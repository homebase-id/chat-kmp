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
     * Append a message to the Crashlytics log buffer. The buffer is written to
     * Crashlytics' on-disk crash context, so anything logged before a crash is
     * included in the report. Used by the crash handlers to attach the Kotlin /
     * ObjC stack to the fatal report (which otherwise shows only a bare abort()).
     */
    fun log(message: String)

    /**
     * Set a custom key/value pair on the next Crashlytics report. Like [log],
     * keys live in the mmap'd crash context, so values set immediately before a
     * crash are included. Used to record the uncaught exception's class and
     * message so the fatal report is identifiable instead of "abort in libsystem".
     */
    fun setCustomKey(key: String, value: String)
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
