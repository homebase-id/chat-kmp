package id.homebase.api.storage

import platform.Foundation.NSUserDefaults

/** iOS implementation of SharedPreferences using NSUserDefaults. */
actual object SharedPreferences {
    private val defaults: NSUserDefaults
        get() = NSUserDefaults.standardUserDefaults

    actual fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
        defaults.synchronize()
    }

    actual fun getString(key: String): String? {
        return defaults.stringForKey(key)
    }

    actual fun putInt(key: String, value: Int) {
        defaults.setInteger(value.toLong(), forKey = key)
        defaults.synchronize()
    }

    actual fun getInt(key: String, defaultValue: Int): Int {
        return if (defaults.objectForKey(key) != null) {
            defaults.integerForKey(key).toInt()
        } else {
            defaultValue
        }
    }

    actual fun putLong(key: String, value: Long) {
        defaults.setInteger(value, forKey = key)
        defaults.synchronize()
    }

    actual fun getLong(key: String, defaultValue: Long): Long {
        return if (defaults.objectForKey(key) != null) {
            defaults.integerForKey(key)
        } else {
            defaultValue
        }
    }

    actual fun putBoolean(key: String, value: Boolean) {
        defaults.setBool(value, forKey = key)
        defaults.synchronize()
    }

    actual fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            defaultValue
        }
    }

    actual fun putFloat(key: String, value: Float) {
        defaults.setFloat(value, forKey = key)
        defaults.synchronize()
    }

    actual fun getFloat(key: String, defaultValue: Float): Float {
        return if (defaults.objectForKey(key) != null) {
            defaults.floatForKey(key)
        } else {
            defaultValue
        }
    }

    actual fun putDouble(key: String, value: Double) {
        defaults.setDouble(value, forKey = key)
        defaults.synchronize()
    }

    actual fun getDouble(key: String, defaultValue: Double): Double {
        return if (defaults.objectForKey(key) != null) {
            defaults.doubleForKey(key)
        } else {
            defaultValue
        }
    }

    actual fun remove(key: String) {
        defaults.removeObjectForKey(key)
        defaults.synchronize()
    }

    actual fun contains(key: String): Boolean {
        return defaults.objectForKey(key) != null
    }

    actual fun clear() {
        val dictionary = defaults.dictionaryRepresentation()
        dictionary.keys.forEach { key -> defaults.removeObjectForKey(key as String) }
        defaults.synchronize()
    }
}
