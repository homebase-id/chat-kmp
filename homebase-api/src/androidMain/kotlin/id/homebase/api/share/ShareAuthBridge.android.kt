package id.homebase.api.share

/** No-op on Android — share activity runs in same process and checks auth via Koin DI. */
actual object ShareAuthBridge {
    actual fun setAuthenticated(isAuthenticated: Boolean, userDomain: String) {}
    actual fun clearAuth() {}
}
