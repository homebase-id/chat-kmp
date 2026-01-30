package id.homebase.api.storage

import android.content.Context
import androidx.core.content.edit
import android.content.SharedPreferences as AndroidSharedPreferences

/** Android implementation of SharedPreferences using native Android SharedPreferences. */
actual object SharedPreferences {
    private const val PREFS_NAME = "homebase_shared_prefs"

    private lateinit var applicationContext: Context

    /**
     * Initialize SharedPreferences with application context. Must be called before using any other
     * methods (typically in Application.onCreate()).
     */
    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    private fun getPrefs(): AndroidSharedPreferences {
        check(::applicationContext.isInitialized) {
            "SharedPreferences not initialized. Call SharedPreferences.initialize(context) first."
        }
        return applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    actual fun putString(key: String, value: String) {
        getPrefs().edit { putString(key, value) }
    }

    actual fun getString(key: String): String? {
        return getPrefs().getString(key, null)
    }

    actual fun putInt(key: String, value: Int) {
        getPrefs().edit { putInt(key, value) }
    }

    actual fun getInt(key: String, defaultValue: Int): Int {
        return getPrefs().getInt(key, defaultValue)
    }

    actual fun putLong(key: String, value: Long) {
        getPrefs().edit { putLong(key, value) }
    }

    actual fun getLong(key: String, defaultValue: Long): Long {
        return getPrefs().getLong(key, defaultValue)
    }

    actual fun putBoolean(key: String, value: Boolean) {
        getPrefs().edit { putBoolean(key, value) }
    }

    actual fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return getPrefs().getBoolean(key, defaultValue)
    }

    actual fun putFloat(key: String, value: Float) {
        getPrefs().edit { putFloat(key, value) }
    }

    actual fun getFloat(key: String, defaultValue: Float): Float {
        return getPrefs().getFloat(key, defaultValue)
    }

    actual fun putDouble(key: String, value: Double) {
        // Android SharedPreferences doesn't have putDouble, so we use putLong with bit conversion
        getPrefs().edit { putLong(key, value.toRawBits()) }
    }

    actual fun getDouble(key: String, defaultValue: Double): Double {
        val prefs = getPrefs()
        return if (prefs.contains(key)) {
            Double.fromBits(prefs.getLong(key, 0L))
        } else {
            defaultValue
        }
    }

    actual fun remove(key: String) {
        getPrefs().edit { remove(key) }
    }

    actual fun contains(key: String): Boolean {
        return getPrefs().contains(key)
    }

    actual fun clear() {
        getPrefs().edit { clear() }
    }
}
