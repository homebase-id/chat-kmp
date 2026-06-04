package id.homebase.api.storage

import kotlinx.browser.localStorage

/**
 * Web implementation backed by the browser's `localStorage` (same store as
 * [SecureStorage] — there is no OS keystore in the browser). Non-string values are
 * encoded as their string form. Persists across reloads for the page's origin.
 */
actual object SharedPreferences {
    actual fun putString(key: String, value: String) { localStorage.setItem(key, value) }
    actual fun getString(key: String): String? = localStorage.getItem(key)

    actual fun putInt(key: String, value: Int) { localStorage.setItem(key, value.toString()) }
    actual fun getInt(key: String, defaultValue: Int): Int =
        localStorage.getItem(key)?.toIntOrNull() ?: defaultValue

    actual fun putLong(key: String, value: Long) { localStorage.setItem(key, value.toString()) }
    actual fun getLong(key: String, defaultValue: Long): Long =
        localStorage.getItem(key)?.toLongOrNull() ?: defaultValue

    actual fun putBoolean(key: String, value: Boolean) { localStorage.setItem(key, value.toString()) }
    actual fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        localStorage.getItem(key)?.toBooleanStrictOrNull() ?: defaultValue

    actual fun putFloat(key: String, value: Float) { localStorage.setItem(key, value.toString()) }
    actual fun getFloat(key: String, defaultValue: Float): Float =
        localStorage.getItem(key)?.toFloatOrNull() ?: defaultValue

    actual fun putDouble(key: String, value: Double) { localStorage.setItem(key, value.toString()) }
    actual fun getDouble(key: String, defaultValue: Double): Double =
        localStorage.getItem(key)?.toDoubleOrNull() ?: defaultValue

    actual fun remove(key: String) { localStorage.removeItem(key) }
    actual fun contains(key: String): Boolean = localStorage.getItem(key) != null
    actual fun clear() { localStorage.clear() }
}
