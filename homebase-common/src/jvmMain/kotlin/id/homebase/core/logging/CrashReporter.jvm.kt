package id.homebase.core.logging

actual fun crashlyticsLog(message: String) {
    // No-op on Desktop — no crash-reporting backend is wired here yet.
}

actual fun crashlyticsRecordException(throwable: Throwable) {
    // No-op on Desktop — no crash-reporting backend is wired here yet.
}
