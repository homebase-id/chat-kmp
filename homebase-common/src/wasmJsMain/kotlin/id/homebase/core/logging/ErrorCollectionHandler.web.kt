package id.homebase.core.logging

actual fun setErrorCollectionEnabled(enabled: Boolean) {
    // No-op on web — error collection backend (Sentry, etc.) is not wired here yet.
}
