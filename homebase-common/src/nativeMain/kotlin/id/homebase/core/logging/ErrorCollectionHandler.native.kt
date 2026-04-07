package id.homebase.core.logging

actual fun setErrorCollectionEnabled(enabled: Boolean) {
    CrashlyticsBridgeHolder.getBridge()?.setCrashlyticsCollectionEnabled(enabled)
}