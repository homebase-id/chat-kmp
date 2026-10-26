@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

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
     * Securely store a value with the given key. Returns once the value is persisted.
     * @param key Unique identifier for the stored value
     * @param value The value to store
     */
    fun put(key: String, value: String)

    /**
     * Retrieve a securely stored value.
     * @param key The key to look up
     * @return The stored value, or null if not found or not readable right now; see [read]
     */
    fun get(key: String): String?

    /** [get] that tells a value that isn't there apart from one that can't be read right now. */
    fun read(key: String): SecureRead

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

sealed interface SecureRead {
    data class Found(val value: String) : SecureRead

    /** Nothing is stored, or what is stored can never be decrypted again. */
    data object Missing : SecureRead

    /** A value may be stored but reading it failed; retry later, never overwrite it. */
    data class Unavailable(val cause: Throwable) : SecureRead
}
