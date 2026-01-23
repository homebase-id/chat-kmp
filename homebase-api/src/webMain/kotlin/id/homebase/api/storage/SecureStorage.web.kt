package id.homebase.api.storage

actual object SecureStorage {
    actual fun put(key: String, value: String) {
    }

    actual fun get(key: String): String? {
        TODO("Not yet implemented")
    }

    actual fun remove(key: String) {
    }

    actual fun contains(key: String): Boolean {
        TODO("Not yet implemented")
    }

    actual fun clear() {
    }
}