package id.homebase.api.storage

/**
 * Cross-platform key-value storage for non-sensitive data.
 *
 * Platform implementations:
 * - Android: Android SharedPreferences
 * - iOS: NSUserDefaults
 * - Desktop: Java Properties file
 *
 * For storing sensitive data (passwords, tokens, etc.), use [SecureStorage] instead.
 */
expect object SharedPreferences {
    /**
     * Store a string value with the given key.
     * @param key Unique identifier for the stored value
     * @param value The value to store
     */
    fun putString(key: String, value: String)

    /**
     * Retrieve a stored string value.
     * @param key The key to look up
     * @return The stored value, or null if not found
     */
    fun getString(key: String): String?

    /**
     * Store an integer value with the given key.
     * @param key Unique identifier for the stored value
     * @param value The value to store
     */
    fun putInt(key: String, value: Int)

    /**
     * Retrieve a stored integer value.
     * @param key The key to look up
     * @param defaultValue The value to return if key is not found
     * @return The stored value, or defaultValue if not found
     */
    fun getInt(key: String, defaultValue: Int = 0): Int

    /**
     * Store a long value with the given key.
     * @param key Unique identifier for the stored value
     * @param value The value to store
     */
    fun putLong(key: String, value: Long)

    /**
     * Retrieve a stored long value.
     * @param key The key to look up
     * @param defaultValue The value to return if key is not found
     * @return The stored value, or defaultValue if not found
     */
    fun getLong(key: String, defaultValue: Long = 0L): Long

    /**
     * Store a boolean value with the given key.
     * @param key Unique identifier for the stored value
     * @param value The value to store
     */
    fun putBoolean(key: String, value: Boolean)

    /**
     * Retrieve a stored boolean value.
     * @param key The key to look up
     * @param defaultValue The value to return if key is not found
     * @return The stored value, or defaultValue if not found
     */
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean

    /**
     * Store a float value with the given key.
     * @param key Unique identifier for the stored value
     * @param value The value to store
     */
    fun putFloat(key: String, value: Float)

    /**
     * Retrieve a stored float value.
     * @param key The key to look up
     * @param defaultValue The value to return if key is not found
     * @return The stored value, or defaultValue if not found
     */
    fun getFloat(key: String, defaultValue: Float = 0f): Float

    /**
     * Store a double value with the given key.
     * @param key Unique identifier for the stored value
     * @param value The value to store
     */
    fun putDouble(key: String, value: Double)

    /**
     * Retrieve a stored double value.
     * @param key The key to look up
     * @param defaultValue The value to return if key is not found
     * @return The stored value, or defaultValue if not found
     */
    fun getDouble(key: String, defaultValue: Double = 0.0): Double

    /**
     * Remove a stored value.
     * @param key The key to remove
     */
    fun remove(key: String)

    /**
     * Check if a key exists in storage.
     * @param key The key to check
     * @return true if the key exists, false otherwise
     */
    fun contains(key: String): Boolean

    /** Clear all stored values. */
    fun clear()
}
