package id.homebase.homebasekmppoc.lib.youauth
import id.homebase.api.storage.SharedPreferences

/**
 * Handles username storage operations using SecureStorage.
 * Provides functionality to load and save the last used username
 * for convenient user experience between app sessions.
 */
class UsernameStorage {

    companion object {
        private const val TAG = "UsernameStorage"
    }

    /**
     * Load the last used username from secure storage.
     * Returns empty string if no username is found in storage.
     */
    fun loadUsername(): String {
        val s = SharedPreferences.getString(YouAuthStorageKeys.USERNAME)

        return if (s.isNullOrEmpty())
            ""
        else
            s
    }

    /**
     * Save the username to secure storage.
     */
    fun saveUsername(username: String) {
        if (username.isNotBlank()) {
            SharedPreferences.putString(YouAuthStorageKeys.USERNAME, username)
        }
    }

    /**
     * Delete the saved username from secure storage.
     */
    fun deleteUsername() {
        SharedPreferences.remove(YouAuthStorageKeys.USERNAME)
    }
}