package id.homebase.api.storage

/**
 * Secure key-value storage using platform-native secure storage mechanisms.
 *
 * Platform implementations:
 * - Android: Android KeyStore with AES-GCM encryption
 * - iOS: Keychain Services
 * - Desktop: Java KeyStore with AES-GCM encryption
 */
expect object SecureStorage {
    /**
     * Securely store a value with the given key.
     * @param key Unique identifier for the stored value
     * @param value The value to store
     */
    fun put(key: String, value: String)

    /**
     * Retrieve a securely stored value.
     * @param key The key to look up
     * @return The stored value, or null if not found
     */
    fun get(key: String): String?

    /**
     * Remove a stored value.
     * @param key The key to remove
     */
    fun remove(key: String)

    /**
     * Check if a key exists in secure storage.
     * @param key The key to check
     * @return true if the key exists, false otherwise
     */
    fun contains(key: String): Boolean

    /** Clear all stored values from secure storage. */
    fun clear()
}
