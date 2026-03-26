package id.homebase.api.share

/**
 * Bridge for sharing auth status with the iOS share extension.
 * On iOS, writes to the shared keychain so the extension can check
 * if the user is logged in. On other platforms, this is a no-op.
 */
expect object ShareAuthBridge {
    fun setAuthenticated(isAuthenticated: Boolean, userDomain: String)
    fun clearAuth()
}
