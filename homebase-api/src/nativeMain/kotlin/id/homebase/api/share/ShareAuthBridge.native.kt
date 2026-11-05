package id.homebase.api.share

import co.touchlab.kermit.Logger
import id.homebase.api.storage.KeychainStore

/**
 * iOS implementation that writes auth status to the shared keychain
 * so the share extension can check if the user is logged in.
 */
actual object ShareAuthBridge {

    private val keychain = KeychainStore("id.homebase.share.auth", APP_GROUP_ID)
    private const val KEY_AUTH_ACTIVE = "share_auth_active"
    private const val KEY_USER_DOMAIN = "share_user_domain"

    actual fun setAuthenticated(isAuthenticated: Boolean, userDomain: String) {
        try {
            keychain.put(KEY_AUTH_ACTIVE, if (isAuthenticated) "true" else "false")
            keychain.put(KEY_USER_DOMAIN, userDomain)
        } catch (e: Exception) {
            Logger.e(tag = "ShareAuthBridge") { "Failed to set auth status: ${e.message}" }
        }
    }

    actual fun clearAuth() {
        try {
            keychain.delete(KEY_AUTH_ACTIVE)
            keychain.delete(KEY_USER_DOMAIN)
        } catch (e: Exception) {
            Logger.e(tag = "ShareAuthBridge") { "Failed to clear auth status: ${e.message}" }
        }
    }
}
