import Foundation

/// Checks whether the user is authenticated by reading from the shared keychain.
/// The main app writes these values via the KMP ShareAuthBridge after login/logout.
struct ShareAuthChecker {

    static func isAuthenticated() -> Bool {
        return SharedKeychainHelper.getString(key: "share_auth_active") == "true"
    }

    static func getUserDomain() -> String? {
        return SharedKeychainHelper.getString(key: "share_user_domain")
    }
}
