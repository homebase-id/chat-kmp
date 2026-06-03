package id.homebase.core.logging

actual fun crashlyticsLog(message: String) {
    // No-op on Web — error collection backend (Sentry, etc.) is not wired here yet.
}

actual fun crashlyticsRecordException(throwable: Throwable) {
    // No-op on Web — error collection backend (Sentry, etc.) is not wired here yet.
}
