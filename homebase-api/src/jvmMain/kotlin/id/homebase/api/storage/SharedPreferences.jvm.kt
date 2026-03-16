package id.homebase.api.storage

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

/** Desktop (JVM) implementation of SharedPreferences using Java Properties file. */
actual object SharedPreferences {
    private const val PREFS_FILE = "shared_preferences.properties"

    private val storageDir: File by lazy {
        val userHome = System.getProperty("user.home")
        val appDir = File(userHome, ".homebase-chat")
        appDir.mkdirs()
        appDir
    }

    private val prefsFile: File by lazy { File(storageDir, PREFS_FILE) }

    private val properties: Properties by lazy {
        Properties().apply {
            if (prefsFile.exists()) {
                FileInputStream(prefsFile).use { load(it) }
            }
        }
    }

    private fun save() {
        FileOutputStream(prefsFile).use { properties.store(it, null) }
    }

    actual fun putString(key: String, value: String) {
        properties.setProperty(key, value)
        save()
    }

    actual fun getString(key: String): String? {
        return properties.getProperty(key)
    }

    actual fun putInt(key: String, value: Int) {
        properties.setProperty(key, value.toString())
        save()
    }

    actual fun getInt(key: String, defaultValue: Int): Int {
        return properties.getProperty(key)?.toIntOrNull() ?: defaultValue
    }

    actual fun putLong(key: String, value: Long) {
        properties.setProperty(key, value.toString())
        save()
    }

    actual fun getLong(key: String, defaultValue: Long): Long {
        return properties.getProperty(key)?.toLongOrNull() ?: defaultValue
    }

    actual fun putBoolean(key: String, value: Boolean) {
        properties.setProperty(key, value.toString())
        save()
    }

    actual fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return properties.getProperty(key)?.toBooleanStrictOrNull() ?: defaultValue
    }

    actual fun putFloat(key: String, value: Float) {
        properties.setProperty(key, value.toString())
        save()
    }

    actual fun getFloat(key: String, defaultValue: Float): Float {
        return properties.getProperty(key)?.toFloatOrNull() ?: defaultValue
    }

    actual fun putDouble(key: String, value: Double) {
        properties.setProperty(key, value.toString())
        save()
    }

    actual fun getDouble(key: String, defaultValue: Double): Double {
        return properties.getProperty(key)?.toDoubleOrNull() ?: defaultValue
    }

    actual fun remove(key: String) {
        properties.remove(key)
        save()
    }

    actual fun contains(key: String): Boolean {
        return properties.containsKey(key)
    }

    actual fun clear() {
        properties.clear()
        save()
    }
}
