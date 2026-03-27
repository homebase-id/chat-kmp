package id.homebase.api.share

/** No-op on Desktop. */
actual object ShareAuthBridge {
    actual fun setAuthenticated(isAuthenticated: Boolean, userDomain: String) {}
    actual fun clearAuth() {}
}
