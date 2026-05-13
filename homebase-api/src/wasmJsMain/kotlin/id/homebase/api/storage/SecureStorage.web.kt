package id.homebase.api.storage

import kotlinx.browser.localStorage

actual object SecureStorage {

    actual fun put(key: String, value: String) {
        localStorage.setItem(key, value)
    }

    actual fun get(key: String): String? {
        return localStorage.getItem(key)
    }

    actual fun remove(key: String) {
        localStorage.removeItem(key)
    }

    actual fun contains(key: String): Boolean {
        return localStorage.getItem(key) != null
    }

    actual fun clear() {
        localStorage.clear()
    }
}
